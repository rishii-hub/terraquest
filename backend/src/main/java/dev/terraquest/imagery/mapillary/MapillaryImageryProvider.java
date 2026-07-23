package dev.terraquest.imagery.mapillary;

import dev.terraquest.imagery.ImageryProvider;
import dev.terraquest.shared.GeoPoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Mapillary adapter.
 *
 * <p>Everything Mapillary-specific is contained here. Nothing outside this
 * package should reference Mapillary by name -- an ArchUnit rule enforces that.
 *
 * <p>The adapter's main job is hiding the degree-based bounding-box constraint
 * behind the SPI's radius-in-metres contract. Since 2026-01-16 the API rejects
 * bbox queries of 0.01 degrees or larger, so requests wider than roughly 500 m
 * are clamped rather than split: the harvester probes many points cheaply, and
 * tiling one oversized query into dozens of sub-requests would burn rate limit
 * for imagery we would discard anyway.
 *
 * <p>Wired only when {@code terraquest.harvest.enabled} is true. This provider is
 * a harvest-time dependency -- it is injected solely into the harvester, and the
 * round path attributes imagery from the stored {@code Location}, never from here
 * -- so a round-serving deployment neither creates it nor needs a Mapillary token.
 */
@Component
@ConditionalOnProperty(name = "terraquest.harvest.enabled", havingValue = "true", matchIfMissing = false)
public class MapillaryImageryProvider implements ImageryProvider {

    public static final String PROVIDER_ID = "mapillary";

    /** Just under the API ceiling of 0.01 degrees, to avoid boundary rejections. */
    private static final double MAX_BBOX_DEGREES = 0.0098;

    /** Metres per degree of latitude. Longitude varies with latitude; see below. */
    private static final double METRES_PER_DEGREE_LAT = 111_320.0;

    private static final Capabilities CAPABILITIES = new Capabilities(
            true,   // panoramas available, though a minority of the corpus
            true,   // sequences exposed, enabling future movement
            true,   // CC-BY-SA: contributor credit is mandatory
            (MAX_BBOX_DEGREES / 2) * METRES_PER_DEGREE_LAT
    );

    private final MapillaryClient client;

    public MapillaryImageryProvider(MapillaryClient client) {
        this.client = client;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public Capabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public List<SourceImage> findNear(GeoPoint centre, double radiusMetres, int limit) {
        double effectiveRadius = Math.min(radiusMetres, CAPABILITIES.maxRadiusMetres());

        // Longitude degrees shrink toward the poles. Without this correction a
        // fixed degree window is far wider in metres at Oslo than at Nairobi,
        // which would skew both coverage sampling and rate-limit consumption.
        double latDelta = effectiveRadius / METRES_PER_DEGREE_LAT;
        double lonDelta = effectiveRadius
                / (METRES_PER_DEGREE_LAT * Math.max(0.01, Math.cos(Math.toRadians(centre.lat()))));

        return client.searchBbox(
                        centre.lat() - latDelta, centre.lon() - lonDelta,
                        centre.lat() + latDelta, centre.lon() + lonDelta,
                        limit)
                .stream()
                .map(this::toSourceImage)
                .toList();
    }

    @Override
    public Optional<String> resolveImageUrl(String externalId, ImageSize size) {
        String field = switch (size) {
            case THUMBNAIL -> "thumb_256_url";
            case STANDARD  -> "thumb_1024_url";
            case ORIGINAL  -> "thumb_original_url";
        };
        return client.fetchField(externalId, field);
    }

    @Override
    public Attribution attributionFor(SourceImage image) {
        // All Mapillary imagery is CC-BY-SA. Contributor credit is a licence
        // obligation, not a courtesy; omitting it makes our use non-compliant.
        String contributor = image.contributorName() != null
                ? image.contributorName()
                : "Mapillary contributor";

        return new Attribution(
                contributor,
                image.contributorId() != null
                        ? "https://www.mapillary.com/app/user/" + image.contributorId()
                        : null,
                "CC-BY-SA-4.0",
                "https://creativecommons.org/licenses/by-sa/4.0/",
                "https://www.mapillary.com/app?pKey=" + image.externalId()
        );
    }

    private SourceImage toSourceImage(MapillaryImage img) {
        return new SourceImage(
                PROVIDER_ID,
                img.id(),
                img.sequenceId(),
                new GeoPoint(img.lat(), img.lon()),
                img.compassAngle(),
                img.isPanoramic(),
                img.capturedAt(),
                img.creatorUsername(),
                img.creatorId()
        );
    }
}
