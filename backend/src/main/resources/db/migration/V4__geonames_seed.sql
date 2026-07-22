-- GeoNames seed import: idempotency key.
--
-- The importer streams cities15000.txt (CC BY 4.0) into candidate_point.
-- Re-running it must not duplicate rows, and dedupe is on rounded coordinates,
-- not name: GeoNames has many same-named places, and two distinct cities do not
-- collide at ~110 m (three decimal degrees) resolution.
--
-- import_key is the rounded-coordinate string ("%.3f:%.3f", lat, lon). Manual
-- and future OSM-sourced rows carry a NULL import_key and are unaffected: NULLs
-- are distinct in a unique index, so any number of them coexist. The importer
-- inserts with ON CONFLICT (import_key) DO NOTHING, which infers this index.

ALTER TABLE candidate_point
    ADD COLUMN import_key TEXT;

CREATE UNIQUE INDEX uq_candidate_import_key ON candidate_point (import_key);
