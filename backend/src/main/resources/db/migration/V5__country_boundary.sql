-- Country boundaries for self-owned reverse geocoding.
--
-- GeoNames country codes are unreliable near borders, and a location's country
-- drives Country Streak scoring and Passport stamps. We resolve it ourselves
-- from Natural Earth Admin 0 polygons (public domain) with ST_Covers, rather
-- than trusting the seed's declared code.
--
-- geometry, not geography: point-in-polygon with ST_Covers uses the GiST index
-- directly on a planar geometry. The candidate/location positions are
-- geography(Point,4326) and are cast with ::geometry at query time; both sides
-- are SRID 4326 so the cast is lossless.

CREATE TABLE country_boundary (
    iso2 CHAR(2)                       PRIMARY KEY,
    name TEXT                          NOT NULL,
    geom geometry(MultiPolygon, 4326)  NOT NULL
);

CREATE INDEX idx_country_boundary_geom ON country_boundary USING GIST (geom);
