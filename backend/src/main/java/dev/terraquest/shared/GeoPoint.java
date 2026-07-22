package dev.terraquest.shared;

/**
 * A WGS84 coordinate. The one value type every feature module shares, so it
 * lives in the shared kernel and depends on nothing internal (enforced by
 * ArchitectureTest).
 *
 * <p>Validated in the compact constructor: an out-of-range coordinate is a
 * programming error, not a recoverable condition, so it fails fast rather than
 * propagating a nonsensical point into a geography column or a distance query.
 */
public record GeoPoint(double lat, double lon) {

    public GeoPoint {
        if (Double.isNaN(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude out of range [-90, 90]: " + lat);
        }
        if (Double.isNaN(lon) || lon < -180.0 || lon > 180.0) {
            throw new IllegalArgumentException("Longitude out of range [-180, 180]: " + lon);
        }
    }
}
