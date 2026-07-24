# TerraQuest

A free, open-source geography game built on OpenStreetMap and Mapillary.
No ads, no paywalls, no Google imagery.

**Status:** pre-alpha. Location pipeline and scoring implemented; nothing playable yet.

---

## The core technical problem

Everything else in this project is ordinary CRUD. The one hard part is **sourcing
playable locations**, and it shapes the architecture.

GeoGuessr works because Google Street View has near-total road coverage. Mapillary
does not. Coverage is crowdsourced, so it is dense in Sweden, Germany, Japan, and
parts of the US, and sparse-to-absent across much of Africa, Central Asia, and rural
India. A naive "pick a random lat/lon, fetch imagery" loop fails: 71% of the planet
is ocean, and most remaining land has no coverage.

Compounding this, since 2026-01-16 the Mapillary API rejects bbox queries larger
than 0.01 degrees square (~1.1 km). You cannot search a country and sample from it.

**Approach:** build the location pool offline.

```
GeoNames cities15000  →  candidate_point  →  probe 0.01° bbox  →  location pool
       (seed)              (unprobed queue)      (harvester job)      (playable)
```

A game round is then a single indexed SELECT. Mapillary is never in the request path.

### Consequences to accept up front

- **Coverage bias is real and permanent.** Mitigate with per-country sampling quotas
  so the pool does not become 60% Northern Europe, but do not pretend it is solved.
- **Seeding from cities means urban bias.** Rural rounds need a second seed source
  (OSM highway nodes). Deferred to Phase 3.
- **CC-BY-SA attribution is mandatory.** Every round must show the contributor's
  username, linked to their Mapillary profile, plus the licence. This is a UI
  requirement, not a footnote.

---

## Phases

Ordered so each phase produces something you can actually play.

### Phase 1 — Location pipeline *(in progress)*
- [x] Schema: countries, candidate points, locations, games, rounds
- [x] Mapillary client respecting the bbox ceiling
- [x] Harvester job with quality filtering, transient-failure retry
- [x] Scoring (haversine + exponential decay)
- [x] GeoNames seed importer (idempotent, streaming)
- [x] Country reverse-geocode from Natural Earth polygons (PostGIS `ST_Covers`)
- [x] Admin harvest-stats endpoint
- [ ] Target: 20k locations across 60+ countries

### Phase 2 — MVP Gameplay
- [ ] `POST /games`, `POST /games/{id}/rounds/{n}/guess`
- [ ] React + MapLibre guess map
- [ ] Custom equirectangular viewer (three.js), attribution on result screen
- [ ] Result screen with the true location and distance line

### Phase 3 — Accounts and persistence
- [ ] OAuth (Google, GitHub) + JWT
- [ ] Match history, stats aggregation
- [ ] Passport stamps

### Phase 4 — Daily Challenge and leaderboards
- [ ] Scheduled daily location selection
- [ ] Redis sorted sets for global/weekly/monthly boards
- [ ] Streak tracking

### Phase 5 — Multiplayer
- [ ] STOMP over WebSocket, room lifecycle
- [ ] Live scoreboard, Battle Royale elimination

Landmark Mode, flag/capital quizzes, AI challenges, community maps, replays and
heatmaps are all out of scope until Phase 5 ships.

---

## Stack

| Layer     | Choice                                    |
|-----------|-------------------------------------------|
| Frontend  | React, TypeScript, Tailwind, MapLibre GL  |
| Backend   | Spring Boot 3, Java 21                    |
| Database  | PostgreSQL 16 + PostGIS                   |
| Cache     | Redis (leaderboards, sessions, matchmaking) |
| Imagery   | Mapillary (CC-BY-SA)                      |
| Basemap   | OpenStreetMap via MapLibre                |

PostGIS is not optional — country reverse-geocoding and distance queries both need it.

## Local setup

```bash
docker compose up -d postgres redis
export MAPILLARY_ACCESS_TOKEN=<token from mapillary.com/developer>
./mvnw spring-boot:run
```

## Seeding the pool

The candidate grid and country boundaries are loaded from external open datasets
via a one-off CLI runner — never on boot. Download the data first (neither file
is committed to git):

```bash
# GeoNames cities (CC BY 4.0) — tab-separated; columns 4/5 are lat/lon.
curl -O https://download.geonames.org/export/dump/cities15000.zip
unzip cities15000.zip            # -> cities15000.txt

# Natural Earth Admin 0 countries (public domain). Convert the shapefile to
# GeoJSON once (requires GDAL's ogr2ogr), so the loader can stream it:
#   ogr2ogr -f GeoJSON ne_admin0.geojson ne_110m_admin_0_countries.shp
```

Then load boundaries first (this also seeds the `country` reference table the
harvester's foreign key needs), then the candidate points. Both runners are
idempotent, so re-running is safe:

```bash
java -jar target/terraquest-backend-*.jar --terraquest.import.boundaries=./ne_admin0.geojson
java -jar target/terraquest-backend-*.jar --terraquest.import.geonames=./cities15000.txt
```

Candidate points are stored without a country; each location's country is
resolved from the boundary polygons at harvest time (`ST_Covers` on the image's
own coordinates), so a point that falls in no polygon is excluded from play
rather than mislabelled.

## Monitoring the pool

`GET /api/v1/admin/harvest-stats` (HTTP Basic, `ADMIN_USERNAME` / `ADMIN_PASSWORD`)
returns pool size by country split panoramic vs flat, candidate-queue state
(probed / unprobed / retry-exhausted) and ingest state (ingested / awaiting /
failed). It is the input for tuning the sampler once real numbers exist.

## Data attribution

- **GeoNames** city data — [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/),
  © GeoNames (<https://www.geonames.org>).
- **Natural Earth** Admin 0 country boundaries — public domain
  (<https://www.naturalearthdata.com>).

## Licence

Code: AGPL-3.0. Imagery: CC-BY-SA 4.0, attributed per-image.
