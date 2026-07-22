-- TerraQuest core schema
-- Requires: PostGIS (geography type for distance math)

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ---------------------------------------------------------------
-- Countries: reference data, drives the Passport system and
-- country-accuracy stats. Seeded separately from Natural Earth.
-- ---------------------------------------------------------------
CREATE TABLE country (
    iso2         CHAR(2)      PRIMARY KEY,
    iso3         CHAR(3)      NOT NULL,
    name         TEXT         NOT NULL,
    continent    TEXT         NOT NULL,
    centroid     GEOGRAPHY(POINT, 4326) NOT NULL
);

-- ---------------------------------------------------------------
-- Candidate points: seed grid we probe Mapillary against.
-- Populated from GeoNames (cities15000 / cities5000, CC BY 4.0).
-- Probed once, then marked so the harvester never re-probes.
-- ---------------------------------------------------------------
CREATE TABLE candidate_point (
    id           BIGSERIAL    PRIMARY KEY,
    position     GEOGRAPHY(POINT, 4326) NOT NULL,
    country_code CHAR(2)      REFERENCES country(iso2),
    source       TEXT         NOT NULL,          -- 'geonames' | 'osm_place' | 'manual'
    probed_at    TIMESTAMPTZ,
    hit_count    INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_candidate_unprobed ON candidate_point (probed_at) WHERE probed_at IS NULL;

-- ---------------------------------------------------------------
-- Locations: the playable pool. Never hit Mapillary at request time.
-- ---------------------------------------------------------------
CREATE TABLE location (
    id                 UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    mapillary_image_id TEXT         NOT NULL UNIQUE,
    sequence_id        TEXT,
    position           GEOGRAPHY(POINT, 4326) NOT NULL,
    country_code       CHAR(2)      NOT NULL REFERENCES country(iso2),
    compass_angle      REAL,
    is_panoramic       BOOLEAN      NOT NULL DEFAULT FALSE,
    captured_at        TIMESTAMPTZ,

    -- Attribution: CC-BY-SA requires we surface these in the UI.
    creator_username   TEXT,
    creator_id         TEXT,

    -- Curation
    quality_score      REAL         NOT NULL DEFAULT 0.5,  -- 0..1, heuristic
    difficulty         SMALLINT     NOT NULL DEFAULT 3,    -- 1..5
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    report_count       INT          NOT NULL DEFAULT 0,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_location_country_active ON location (country_code) WHERE is_active;
CREATE INDEX idx_location_position       ON location USING GIST (position);
-- Cheap random sampling without ORDER BY random() over the whole table.
CREATE INDEX idx_location_sampling       ON location (is_active, quality_score);

-- ---------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------
CREATE TABLE app_user (
    id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         TEXT        UNIQUE,
    username      TEXT        NOT NULL UNIQUE,
    oauth_provider TEXT,
    oauth_subject TEXT,
    password_hash TEXT,
    xp            BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (oauth_provider, oauth_subject)
);

-- ---------------------------------------------------------------
-- Games and rounds
-- ---------------------------------------------------------------
CREATE TYPE game_mode   AS ENUM ('CLASSIC', 'DAILY', 'COUNTRY_STREAK', 'MULTIPLAYER');
CREATE TYPE game_status AS ENUM ('IN_PROGRESS', 'COMPLETED', 'ABANDONED');

CREATE TABLE game (
    id           UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID        NOT NULL REFERENCES app_user(id),
    mode         game_mode   NOT NULL,
    status       game_status NOT NULL DEFAULT 'IN_PROGRESS',
    total_score  INT         NOT NULL DEFAULT 0,
    round_count  SMALLINT    NOT NULL DEFAULT 5,
    time_limit_s INT,
    daily_key    DATE,                 -- set only for DAILY, enforces one per day
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_one_daily_per_user ON game (user_id, daily_key) WHERE mode = 'DAILY';

CREATE TABLE round (
    id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    game_id       UUID        NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    round_index   SMALLINT    NOT NULL,
    location_id   UUID        NOT NULL REFERENCES location(id),

    guess         GEOGRAPHY(POINT, 4326),
    guess_country CHAR(2),
    distance_m    DOUBLE PRECISION,
    score         INT,
    elapsed_ms    INT,
    guessed_at    TIMESTAMPTZ,

    UNIQUE (game_id, round_index)
);

-- ---------------------------------------------------------------
-- Daily challenge: same five locations for everyone, chosen ahead
-- of time by a scheduled job so it is deterministic and cacheable.
-- ---------------------------------------------------------------
CREATE TABLE daily_challenge (
    challenge_date DATE     PRIMARY KEY,
    location_ids   UUID[]   NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------
-- Passport
-- ---------------------------------------------------------------
CREATE TABLE passport_stamp (
    user_id      UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    country_code CHAR(2)     NOT NULL REFERENCES country(iso2),
    first_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    correct_count INT        NOT NULL DEFAULT 1,
    PRIMARY KEY (user_id, country_code)
);
