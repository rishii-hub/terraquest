-- Imagery proxy + anti-cheat round issuance.
--
-- Design note: the client must never receive `mapillary_image_id`. Anyone can
-- resolve that ID to exact coordinates via graph.mapillary.com in a single
-- request, and this repository is public. Rounds therefore reference an
-- `image_asset` row whose storage key is opaque and served via short-lived
-- signed URLs.

-- ---------------------------------------------------------------
-- Cached, EXIF-stripped copies of Mapillary imagery in R2.
-- Populated eagerly by the harvester: the curated pool is small
-- (target ~20k) and storage is negligible, so paying at harvest
-- time buys us a zero-latency request path.
-- ---------------------------------------------------------------
CREATE TYPE projection_type AS ENUM ('EQUIRECTANGULAR', 'FLAT');

CREATE TABLE image_asset (
    id            UUID            PRIMARY KEY DEFAULT uuid_generate_v4(),
    location_id   UUID            NOT NULL REFERENCES location(id) ON DELETE CASCADE,

    -- Opaque R2 object key. Deliberately carries no semantic information:
    -- no country, no coordinates, no Mapillary ID, no capture date.
    storage_key   TEXT            NOT NULL UNIQUE,

    projection    projection_type NOT NULL,
    width         INT             NOT NULL,
    height        INT             NOT NULL,
    bytes         BIGINT          NOT NULL,

    -- Set true only after the re-encode pass that drops all EXIF, including
    -- GPS tags. Assets that fail this are never served.
    exif_stripped BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Reserved for sequence-based movement (Phase 3). Position of this frame
    -- within its Mapillary sequence; null for standalone captures.
    sequence_index INT,

    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_asset_location ON image_asset (location_id);

-- Only fully-processed assets are eligible for play. Enforced here rather than
-- in service code so a bug upstream cannot leak an unstripped image.
CREATE INDEX idx_asset_servable ON image_asset (location_id)
    WHERE exif_stripped;

ALTER TABLE location
    ADD COLUMN asset_ready BOOLEAN NOT NULL DEFAULT FALSE;

-- A location is only playable once its imagery has been cached and cleaned.
DROP INDEX IF EXISTS idx_location_sampling;
CREATE INDEX idx_location_sampling ON location (quality_score)
    WHERE is_active AND asset_ready;

-- ---------------------------------------------------------------
-- Round issuance: server-authoritative timing.
--
-- `issued_at` is stamped when the round is handed to the client. Elapsed time
-- is computed as (guessed_at - issued_at) server-side; any client-supplied
-- duration is ignored. Without this, timed modes and the Battle Royale clock
-- are trivially forged.
-- ---------------------------------------------------------------
ALTER TABLE round
    ADD COLUMN asset_id  UUID REFERENCES image_asset(id),
    ADD COLUMN issued_at TIMESTAMPTZ;

-- Guesses are idempotent: a round may be answered exactly once. Enforced by
-- the partial unique index below plus an optimistic UPDATE ... WHERE guessed_at
-- IS NULL in GameService.
ALTER TABLE round
    ADD CONSTRAINT chk_guess_complete
    CHECK (
        (guessed_at IS NULL AND score IS NULL AND distance_m IS NULL)
        OR
        (guessed_at IS NOT NULL AND score IS NOT NULL AND distance_m IS NOT NULL)
    );
