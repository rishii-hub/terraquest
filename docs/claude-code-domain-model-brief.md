# Domain model brief

## Goal

Make `mvn verify` pass. The repo has services, a controller, a sampler and tests
that reference JPA entities and repositories which do not exist. Write them.
Do not redesign anything above them.

## Ground rules

- The callers are the specification. Every method signature you need is already
  called somewhere. Read GameService, GameController, LocationHarvester,
  CountryQuotaSampler, ImageAssetService and LocationHarvesterTest and derive the
  contracts from them. Where a caller and the schema disagree, the schema in
  db/migration/ wins and you flag it.
- Do not modify existing services, the controller, the sampler, the SPI, or the
  migrations. If something cannot be satisfied without changing one, stop and
  explain rather than editing around it.
- Do not weaken ArchitectureTest. Those rules encode deliberate decisions. A
  failing rule is an architecture discussion, not a test to edit. If your code
  trips one, the code is wrong.
- Flyway owns the schema. ddl-auto is validate. Entities must match V1 and V2
  exactly or the app will not start.

## What to write

### 1. shared/GeoPoint
record GeoPoint(double lat, double lon), range-validated in a compact
constructor. Must depend on nothing internal - ArchitectureTest enforces that
shared is a leaf.

### 2. Entities
Country, CandidatePoint, Location, AppUser, Game, Round, DailyChallenge,
PassportStamp, ImageAsset - mapped to V1 and V2.

- GEOGRAPHY(POINT, 4326) maps via org.locationtech.jts.geom.Point with
  hibernate-spatial. Expose lat/lon accessors on Location since GameService calls
  getLatitude()/getLongitude(); keep the JTS type internal to the entity rather
  than leaking it into service signatures.
- Postgres enums (game_mode, game_status, projection_type) need
  @JdbcTypeCode(SqlTypes.NAMED_ENUM) or a converter. Plain @Enumerated will not
  bind against a native enum type.
- UUID primary keys are database-generated (DEFAULT uuid_generate_v4()).
- Entities carry behaviour the services already assume: Game.addScore,
  Game.complete, Game.assertInProgress, Round.isAnswered, Location.primaryAsset,
  CandidatePoint.markProbed(Instant), CandidatePoint.of(GeoPoint, String, String).
  Read the call sites for exact signatures.
- ImageAsset.attributionFor(Location) is called by GameService. Attribution is
  provider-specific and lives behind ImageryProvider.attributionFor, so this needs
  a sensible seam - propose one rather than hardcoding Mapillary URLs into the
  entity. Flag your choice in the PR description.

### 3. Repositories

- GameRepository.findOwnedOrThrow(UUID gameId, UUID userId) - must not leak
  another user's game; throw rather than return empty.
- RoundRepository.findByGameAndIndex, isAnswered(UUID gameId, int index)
- RoundRepository.recordGuessIfUnanswered(...) - MUST be a conditional
  UPDATE ... WHERE guessed_at IS NULL returning the affected row count. This is
  the single-shot guess guarantee. A read-then-write is a race and is wrong.
- LocationRepository.countActiveByCountry(float minQuality) -> Map<String,Integer>
- LocationRepository.sampleWithinCountry(String country, float minQuality,
  Set<UUID> exclude, long seed) - MUST be deterministic given the seed, because
  daily challenges and multiplayer rooms reproduce identical draws across nodes.
  ORDER BY random() is not deterministic; use setseed() or hash the seed against
  the row id. Must respect the asset_ready and is_active flags.
- LocationRepository.upsertFromSource(SourceImage, String countryCode, float
  quality) - idempotent on the provider's external id.
- LocationRepository.markAssetReady(UUID)
- CandidatePointRepository.findUnprobed(int limit), saveAll
- ImageAssetRepository.save

### 4. Test fakes
LocationHarvesterTest already references InMemoryCandidateRepository,
InMemoryLocationRepository and RecordingAssetService. Write them so that test
class compiles and passes unchanged. Do not alter the test to fit the fakes.
This implies the repositories the harvester depends on need to be interfaces the
fakes can implement - if that requires introducing an interface where a Spring
Data repository is used directly, do it, and say so.

## 5. One integration test
LocationRepositoryIT using Testcontainers against postgis/postgis:16-3.4, proving:
- Flyway migrations apply
- a location round-trips with its geography column intact
- sampleWithinCountry returns the same row twice for the same seed
- recordGuessIfUnanswered returns 1 on first call and 0 on second

## Done
- mvn verify green
- ArchitectureTest passes unmodified
- LocationHarvesterTest passes unmodified
- No changes to migrations, services, controller, sampler, or SPI
- PR description lists any caller/schema disagreements and your attributionFor
  seam decision

## Scope discipline
Do not add seed importers, country resolution, the stats endpoint, security
config, or the viewer. If you finish early, stop.
