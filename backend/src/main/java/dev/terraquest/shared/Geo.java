package dev.terraquest.shared;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Conversions between {@link GeoPoint} and the JTS {@link Point} that
 * hibernate-spatial persists into a {@code geography(Point,4326)} column.
 *
 * <p>Entities keep the JTS type internal and expose {@code GeoPoint} (or plain
 * lat/lon) across their public surface, so the JTS dependency never leaks into
 * service signatures. This helper is the single place that bridges the two.
 *
 * <p><b>Axis order.</b> JTS is x/y, i.e. longitude first, latitude second.
 * Getting this backwards silently places every point in the wrong hemisphere,
 * so it is centralised here rather than open-coded at each call site.
 */
public final class Geo {

    private static final int SRID_WGS84 = 4326;
    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private Geo() {}

    public static Point toPoint(GeoPoint p) {
        return toPoint(p.lat(), p.lon());
    }

    public static Point toPoint(double lat, double lon) {
        Point point = FACTORY.createPoint(new Coordinate(lon, lat));
        point.setSRID(SRID_WGS84);
        return point;
    }

    public static GeoPoint toGeoPoint(Point p) {
        return new GeoPoint(p.getY(), p.getX());
    }
}
