package dev.terraquest.imagery.mapillary;

import java.time.Instant;

/**
 * A single image as returned by the Mapillary Graph API, parsed from the JSON
 * response. Mapillary-specific and confined to this adapter package; the SPI
 * translates it into the provider-neutral {@code SourceImage}.
 */
record MapillaryImage(
        String id,
        String sequenceId,
        double lat,
        double lon,
        Float compassAngle,
        boolean isPanoramic,
        Instant capturedAt,
        String creatorUsername,
        String creatorId
) {
}
