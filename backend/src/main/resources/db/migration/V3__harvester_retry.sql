-- Harvester retry + ingest-failure accounting.
--
-- The coverage spike measured a 5.4% transient HTTP 500 rate on Mapillary
-- probes. The original harvester marked a candidate probed on every path,
-- success or failure, silently discarding roughly one seed in twenty on a
-- server hiccup. This migration adds the counters that let the harvester
-- distinguish "probed, nothing here" (a real result) from "never successfully
-- probed" (a transient failure worth retrying).

-- Consecutive failed probes for a candidate. Reset conceptually by a success
-- (which stamps probed_at instead). Points that exhaust their retries drop out
-- of the unprobed queue via the redefined partial index below.
ALTER TABLE candidate_point
    ADD COLUMN failure_count INT NOT NULL DEFAULT 0;

-- The queue now excludes retry-exhausted points as well as probed ones. The
-- literal 3 mirrors LocationHarvester.MAX_CONSECUTIVE_FAILURES; a partial-index
-- predicate must be immutable, so the constant is duplicated here deliberately.
DROP INDEX IF EXISTS idx_candidate_unprobed;
CREATE INDEX idx_candidate_unprobed ON candidate_point (id)
    WHERE probed_at IS NULL AND failure_count < 3;

-- Ingest attempts on a location. A failed ingest leaves the location with
-- asset_ready = false; this counter separates "ingest failed at least once"
-- from "not yet attempted" for the harvest-stats endpoint and a later retry
-- sweep.
ALTER TABLE location
    ADD COLUMN ingest_attempts INT NOT NULL DEFAULT 0;
