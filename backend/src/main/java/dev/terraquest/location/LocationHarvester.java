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

    /**
     * Capture dates before this are corrupt metadata, not old imagery: Mapillary
     * did not exist before 2008, so a 1970 (epoch-zero) or 1989 (broken EXIF)
     * timestamp is a broken value to reject deliberately, not an old photo.
     */
    private static final Instant EARLIEST_VALID_CAPTURE = Instant.parse("2008-01-01T00:00:00Z");

    /** Cap per point so one well-mapped city cannot flood the pool. */
    private static final int MAX_KEEP_PER_POINT = 3;

    /**
     * Consecutive transient failures before a candidate is given up on. Mirrors
     * the literal in the {@code idx_candidate_unprobed} partial index and in
     * {@link CandidatePointRepositoryImpl}.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final int BATCH_SIZE = 50;
    private static final int PROBE_LIMIT = 20;

    private final List<ImageryProvider> providers;
    private final CandidatePointRepository candidates;
    private final LocationRepository locations;
    private final CountryResolver countries;
    private final ImageAssetService assets;
    private final Clock clock;

    public LocationHarvester(List<ImageryProvider> providers,
                             CandidatePointRepository candidates,
                             LocationRepository locations,
                             CountryResolver countries,
                             ImageAssetService assets,
                             Clock clock) {
        if (providers.isEmpty()) {
            // Fail at startup, not at 3am when the first batch silently does nothing.
            throw new IllegalStateException("At least one ImageryProvider must be configured");
        }
        this.providers = List.copyOf(providers);
        this.candidates = candidates;
        this.locations = locations;
        this.countries = countries;
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
        int failed = 0;
        for (CandidatePoint point : batch) {
            PointOutcome outcome = harvestPoint(point);
            if (outcome.probeSucceeded()) {
                // A definitive answer -- even zero imagery -- retires the point.
                point.recordSuccess(clock.instant(), outcome.kept());
                kept += outcome.kept();
            } else {
                // Transient failure: keep it in the queue until it succeeds or
                // exhausts its retries, so a server hiccup does not discard a seed.
                point.recordFailure();
                failed++;
            }
        }
        candidates.saveAll(batch);
        log.info("Harvest batch: {} candidates, {} kept, {} transient failures",
                batch.size(), kept, failed);
    }

    /**
     * Probe one candidate and persist what survives.
     *
     * <p>Visible for testing; the scheduler entry point above is just batching.
     *
     * @return the number of locations kept, and whether the probe itself
     *         succeeded (a successful probe that found nothing is still a success)
     */
    PointOutcome harvestPoint(CandidatePoint point) {
        ProbeResult probe = probeAllProviders(point.position());
        if (!probe.anyProviderResponded()) {
            // Every attempted provider threw: no result at all, retry later.
            return new PointOutcome(0, false);
        }

        List<SourceImage> chosen = probe.images().stream()
                .filter(this::isPlayable)
                .sorted(Comparator.comparingDouble(this::qualityScore).reversed())
                .limit(MAX_KEEP_PER_POINT)
                .toList();

        int stored = 0;
        for (SourceImage image : chosen) {
            if (persistWithAsset(image)) {
                stored++;
            }
        }
        return new PointOutcome(stored, true);
    }

    /** Result of harvesting one point: how many kept, and whether the probe ran. */
    record PointOutcome(int kept, boolean probeSucceeded) {}

    /**
     * Probe every configured provider and pool the results.
     *
     * <p>Failure isolation is the point of the loop shape: one provider timing
     * out or rate-limiting must degrade coverage for this point, not abort the
     * batch. Duplicates are impossible across providers (external IDs are
     * provider-scoped) but we dedupe within each provider's response anyway,
     * since at least one backend returns the same frame twice at bbox edges.
     */
    private ProbeResult probeAllProviders(GeoPoint position) {
        Map<String, SourceImage> byKey = new LinkedHashMap<>();
        boolean anyResponded = false;
        for (ImageryProvider provider : providers) {
            try {
                for (SourceImage img : provider.findNear(position, PROBE_RADIUS_M, PROBE_LIMIT)) {
                    byKey.putIfAbsent(provider.id() + ":" + img.externalId(), img);
                }
                // Returning without throwing -- even an empty list -- is a
                // definitive answer for this point from this provider.
                anyResponded = true;
            } catch (Exception e) {
                log.warn("Provider '{}' failed at {}: {}", provider.id(), position, e.getMessage());
            }
        }
        return new ProbeResult(List.copyOf(byKey.values()), anyResponded);
    }

    /**
     * Pooled imagery plus whether the probe reached a verdict. {@code
     * anyProviderResponded} is false only when every attempted provider threw --
     * the transient-failure case that must not stamp the candidate probed.
     */
    private record ProbeResult(List<SourceImage> images, boolean anyProviderResponded) {}

    /**
     * Filters imagery that makes for a bad round. Deliberately conservative: a
     * smaller, cleaner pool beats a large one full of tunnel interiors and
     * windscreen glare.
     */
    boolean isPlayable(SourceImage img) {
        if (img.capturedAt() == null) {
            return false;
        }
        // Reject corrupt capture metadata outright. The coverage spike found
        // images dated 1970 (epoch zero) and 1989 (broken EXIF); the age filter
        // below only rejected them by accident. Anything before Mapillary existed
        // is a broken value, not old imagery.
        if (img.capturedAt().isBefore(EARLIEST_VALID_CAPTURE)) {
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
     *
     * <p>Country is resolved from the image's own coordinates, not the seed's
     * declared code. If it falls in no country polygon (offshore, disputed) the
     * location is not persisted at all: an unresolvable point is a real result,
     * excluded from play, not a reason to admit a null country.
     */
    private boolean persistWithAsset(SourceImage image) {
        Optional<String> countryCode = countries.resolve(image.position());
        if (countryCode.isEmpty()) {
            log.debug("Skipping location at {}: no country polygon covers it", image.position());
            return false;
        }

        ImageryProvider provider = providerById(image.providerId());

        Optional<String> url = provider.resolveImageUrl(image.externalId(), ImageSize.STANDARD);
        if (url.isEmpty()) {
            return false;
        }

        Location location = locations.upsertFromSource(image, countryCode.get(), qualityScore(image));

        boolean ingested = assets.ingest(location.getId(), url.get(), image.panoramic())
                .map(asset -> {
                    locations.markAssetReady(location.getId());
                    return true;
                })
                .orElse(false);

        if (!ingested) {
            // Location row persisted but its asset did not; leave it unplayable
            // and count the attempt so a later sweep and the stats endpoint can
            // tell a failed ingest from one never tried.
            locations.recordIngestFailure(location.getId());
        }
        return ingested;
    }

    private ImageryProvider providerById(String id) {
        return providers.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown provider: " + id));
    }
}
