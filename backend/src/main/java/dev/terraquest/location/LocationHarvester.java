package dev.terraquest.location;

import dev.terraquest.imagery.ImageAssetService;
import dev.terraquest.imagery.ImageryProvider;
import dev.terraquest.imagery.ImageryProvider.ImageSize;
import dev.terraquest.imagery.ImageryProvider.SourceImage;
import dev.terraquest.shared.GeoPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Builds the playable location pool ahead of time, from any number of imagery
 * providers.
 *
 * <p>Runs as a background job, never in the request path. A game round is a
 * plain SELECT against {@code location}; no imagery backend is contacted while
 * a player waits.
 *
 * <p>Provider-agnostic by construction: this class depends only on
 * {@link ImageryProvider} and compiles unchanged whether the deployment wires
 * up Mapillary, KartaView, a curated first-party set, or all three. When
 * multiple providers cover the same point, results are pooled and the quality
 * heuristic decides what survives -- no provider gets structural priority.
 *
 * <p>Pipeline per candidate point:
 * <pre>
 *   probe each provider -> filter unplayable -> rank by quality
 *     -> keep top N -> persist location -> ingest asset (EXIF strip, R2)
 *     -> mark asset_ready
 * </pre>
 * A location only becomes playable once its asset is cached and cleaned;
 * {@code asset_ready} is the gate the sampler's partial index enforces.
 */
@Service
public class LocationHarvester {

    private static final Logger log = LoggerFactory.getLogger(LocationHarvester.class);

    /** Search radius per candidate point, in metres. Providers clamp as needed. */
    private static final double PROBE_RADIUS_M = 500.0;

    /** Images older than ~7 years are usually low-resolution or badly degraded. */
    private static final long MAX_AGE_DAYS = 2555;

    /** Cap per point so one well-mapped city cannot flood the pool. */
    private static final int MAX_KEEP_PER_POINT = 3;

    private static final int BATCH_SIZE = 50;
    private static final int PROBE_LIMIT = 20;

    private final List<ImageryProvider> providers;
    private final CandidatePointRepository candidates;
    private final LocationRepository locations;
    private final ImageAssetService assets;
    private final Clock clock;

    public LocationHarvester(List<ImageryProvider> providers,
                             CandidatePointRepository candidates,
                             LocationRepository locations,
                             ImageAssetService assets,
                             Clock clock) {
        if (providers.isEmpty()) {
            // Fail at startup, not at 3am when the first batch silently does nothing.
            throw new IllegalStateException("At least one ImageryProvider must be configured");
        }
        this.providers = List.copyOf(providers);
        this.candidates = candidates;
        this.locations = locations;
        this.assets = assets;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${terraquest.harvest.interval-ms:60000}")
    @Transactional
    public void harvestBatch() {
        List<CandidatePoint> batch = candidates.findUnprobed(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        int kept = 0;
        for (CandidatePoint point : batch) {
            kept += harvestPoint(point);
            // Marked probed on every path, success or failure, so the queue
            // converges instead of retrying dead regions forever.
            point.markProbed(clock.instant());
        }
        candidates.saveAll(batch);
        log.info("Harvest batch: {} candidates probed, {} locations kept", batch.size(), kept);
    }

    /** Visible for testing; the scheduler entry point above is just batching. */
    int harvestPoint(CandidatePoint point) {
        List<SourceImage> pooled = probeAllProviders(point.position());
        if (pooled.isEmpty()) {
            return 0;
        }

        List<SourceImage> chosen = pooled.stream()
                .filter(this::isPlayable)
                .sorted(Comparator.comparingDouble(this::qualityScore).reversed())
                .limit(MAX_KEEP_PER_POINT)
                .toList();

        int stored = 0;
        for (SourceImage image : chosen) {
            if (persistWithAsset(image, point.countryCode())) {
                stored++;
            }
        }
        return stored;
    }

    /**
     * Probe every configured provider and pool the results.
     *
     * <p>Failure isolation is the point of the loop shape: one provider timing
     * out or rate-limiting must degrade coverage for this point, not abort the
     * batch. Duplicates are impossible across providers (external IDs are
     * provider-scoped) but we dedupe within each provider's response anyway,
     * since at least one backend returns the same frame twice at bbox edges.
     */
    private List<SourceImage> probeAllProviders(GeoPoint position) {
        Map<String, SourceImage> byKey = new LinkedHashMap<>();
        for (ImageryProvider provider : providers) {
            try {
                for (SourceImage img : provider.findNear(position, PROBE_RADIUS_M, PROBE_LIMIT)) {
                    byKey.putIfAbsent(provider.id() + ":" + img.externalId(), img);
                }
            } catch (Exception e) {
                log.warn("Provider '{}' failed at {}: {}", provider.id(), position, e.getMessage());
            }
        }
        return List.copyOf(byKey.values());
    }

    /**
     * Filters imagery that makes for a bad round. Deliberately conservative: a
     * smaller, cleaner pool beats a large one full of tunnel interiors and
     * windscreen glare.
     */
    boolean isPlayable(SourceImage img) {
        if (img.capturedAt() == null) {
            return false;
        }
        if (img.capturedAt().isBefore(clock.instant().minus(MAX_AGE_DAYS, ChronoUnit.DAYS))) {
            return false;
        }
        // Without a compass angle a flat image cannot be oriented sensibly.
        // Panoramas orient themselves -- the player just looks around.
        return img.panoramic() || img.compassAngle() != null;
    }

    /**
     * Quality heuristic in [0,1]. Panoramas dominate: look-around is the
     * difference between a geography game and a photo quiz. Recency is a weak
     * secondary signal.
     */
    float qualityScore(SourceImage img) {
        float score = img.panoramic() ? 0.7f : 0.3f;
        if (img.capturedAt() != null) {
            long ageDays = ChronoUnit.DAYS.between(img.capturedAt(), clock.instant());
            score += 0.3f * Math.max(0f, 1f - (ageDays / (float) MAX_AGE_DAYS));
        }
        return Math.min(1f, score);
    }

    /**
     * Persist the location, then eagerly ingest its asset.
     *
     * <p>Eager rather than lazy on purpose: the pool is curated and small
     * (target ~20k), storage is negligible, and paying the download/strip cost
     * at harvest time keeps the request path free of external calls. If
     * ingestion fails the location row remains with {@code asset_ready=false},
     * invisible to the sampler and retryable by a later sweep.
     */
    private boolean persistWithAsset(SourceImage image, String countryCode) {
        ImageryProvider provider = providerById(image.providerId());

        Optional<String> url = provider.resolveImageUrl(image.externalId(), ImageSize.STANDARD);
        if (url.isEmpty()) {
            return false;
        }

        Location location = locations.upsertFromSource(image, countryCode, qualityScore(image));

        return assets.ingest(location.getId(), url.get(), image.panoramic())
                .map(asset -> {
                    locations.markAssetReady(location.getId());
                    return true;
                })
                .orElse(false);
    }

    private ImageryProvider providerById(String id) {
        return providers.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown provider: " + id));
    }
}
