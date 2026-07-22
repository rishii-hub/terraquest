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
- [x] Harvester job with quality filtering
- [x] Scoring (haversine + exponential decay)
- [ ] GeoNames seed importer
- [ ] Country reverse-geocode from Natural Earth polygons
- [ ] Target: 20k locations across 60+ countries

### Phase 2 — Classic Mode, single player
- [ ] `POST /games`, `POST /games/{id}/rounds/{n}/guess`
- [ ] React + MapLibre guess map
- [ ] MapillaryJS viewer with attribution overlay
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

## Licence

Code: AGPL-3.0. Imagery: CC-BY-SA 4.0, attributed per-image.
