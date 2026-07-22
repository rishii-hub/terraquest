package dev.terraquest.imagery;

import dev.terraquest.shared.GeoPoint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Service provider interface for street-level imagery sources.
 *
 * <p>The game engine must never know which service an image came from. Mapillary
 * is Meta-owned and changed its API terms as recently as January 2026; KartaView
 * is a live alternative; user-contributed imagery is on the roadmap. Any of those
 * could become the primary source, or run alongside the others.
 *
 * <p><b>Design constraint that matters:</b> {@link #findNear} takes a radius in
 * <i>metres</i>. Mapillary caps bounding-box queries at 0.01 degrees square, but
 * that is an implementation detail of one adapter. Expressing it in the interface
 * would force every future provider to inherit a constraint irrelevant to it, and
 * would leak provider internals into the harvester -- which is precisely what this
 * abstraction exists to prevent. Adapters translate radius to whatever their
 * backend wants, and chunk the request if their limits require it.
 */
public interface ImageryProvider {

    /** Stable identifier, persisted alongside harvested locations. */
    String id();

    /**
     * What this provider can actually do. Providers differ substantially --
     * some serve panoramas, some only flat frames, some expose sequence
     * adjacency for movement. The harvester and the round builder query this
     * rather than assuming Mapillary's feature set.
     */
    Capabilities capabilities();

    /**
     * Find candidate imagery near a point.
     *
     * @param centre       search origin
     * @param radiusMetres search radius; adapters may narrow this to satisfy
     *                     backend limits but must never silently widen it
     * @param limit        maximum images to return
     * @return matching images, possibly empty; never null
     */
    List<SourceImage> findNear(GeoPoint centre, double radiusMetres, int limit);

    /**
     * Resolve a downloadable URL for an image at the requested size.
     *
     * <p>Returns a URL rather than bytes so adapters can hand back a CDN link
     * without proxying through our process. The ingestion pipeline downloads,
     * strips metadata and re-hosts; that responsibility stays out of here.
     */
    Optional<String> resolveImageUrl(String externalId, ImageSize size);

    /**
     * Licence attribution for an image.
     *
     * <p>Provider-specific by necessity: Mapillary imagery is CC-BY-SA and
     * requires contributor credit, a curated first-party dataset may require
     * none, and a future provider may impose different terms entirely. Getting
     * this wrong is a licensing violation, so it is not optional and has no
     * default implementation.
     */
    Attribution attributionFor(SourceImage image);

    // ---------------------------------------------------------------

    /**
     * @param supportsPanoramic  provider serves 360 equirectangular imagery
     * @param supportsSequences  frames are linked, enabling movement between them
     * @param requiresAttribution licence obliges visible credit
     * @param maxRadiusMetres    largest single query the backend will accept
     */
    record Capabilities(
            boolean supportsPanoramic,
            boolean supportsSequences,
            boolean requiresAttribution,
            double maxRadiusMetres
    ) {}

    /**
     * A candidate image as described by its source, before ingestion.
     *
     * <p>Intentionally minimal: only fields the harvester needs in order to
     * decide whether an image is playable and to attribute it correctly.
     *
     * @param externalId    provider-scoped ID; never exposed to clients
     * @param sequenceId    grouping key for movement, null if unsupported
     * @param position      capture location
     * @param compassAngle  bearing in degrees, null if unknown
     * @param panoramic     true for equirectangular 360 imagery
     * @param capturedAt    capture timestamp, null if unknown
     */
    record SourceImage(
            String providerId,
            String externalId,
            String sequenceId,
            GeoPoint position,
            Float compassAngle,
            boolean panoramic,
            Instant capturedAt,
            String contributorName,
            String contributorId
    ) {}

    enum ImageSize { THUMBNAIL, STANDARD, ORIGINAL }

    /**
     * @param licence   SPDX-style identifier, e.g. "CC-BY-SA-4.0"
     * @param sourceUrl canonical link back to the image at its source
     */
    record Attribution(
            String contributor,
            String contributorUrl,
            String licence,
            String licenceUrl,
            String sourceUrl
    ) {}
}
