package dev.terraquest.location;

import dev.terraquest.game.Game;
import dev.terraquest.game.GameMode;
import dev.terraquest.game.GameRepository;
import dev.terraquest.game.Round;
import dev.terraquest.game.RoundRepository;
import dev.terraquest.identity.AppUser;
import dev.terraquest.shared.Geo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Repository behaviour against a real PostGIS, proving the properties that a
 * mocked or H2-backed test could not: that the migrations apply, that a
 * geography column round-trips, that the in-country draw is reproducible for a
 * seed, and that the guard on the guess write makes it single-shot.
 *
 * <p>Skipped when Docker is unavailable rather than failing, so {@code mvn
 * verify} stays green on a developer box without Docker while still running in
 * CI, which is the environment that must catch a bad migration.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({LocationRepositoryImpl.class, CandidatePointRepositoryImpl.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class LocationRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @Autowired
    private LocationRepository locations;

    @Autowired
    private RoundRepository rounds;

    @Autowired
    private GameRepository games;

    @Autowired
    private TestEntityManager em;

    @Test
    void flyway_migrations_apply() {
        Number applied = (Number) em.getEntityManager()
                .createNativeQuery("select count(*) from flyway_schema_history where success")
                .getSingleResult();
        // V1 core schema + V2 imagery proxy, at minimum.
        assertThat(applied.intValue()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void a_location_round_trips_with_its_geography_column_intact() {
        insertCountry("FR", "FRA", "France", "Europe");

        UUID id = em.persistAndGetId(
                Location.builder()
                        .mapillaryImageId("mly-roundtrip-1")
                        .position(Geo.toPoint(48.8566, 2.3522)) // Paris
                        .countryCode("FR")
                        .qualityScore(0.9f)
                        .active(true)
                        .assetReady(true)
                        .build(),
                UUID.class);
        em.flush();
        em.clear();

        Location reloaded = em.find(Location.class, id);
        assertThat(reloaded.getLatitude()).isCloseTo(48.8566, offset(1e-6));
        assertThat(reloaded.getLongitude()).isCloseTo(2.3522, offset(1e-6));
    }

    @Test
    void sample_within_country_returns_the_same_row_twice_for_the_same_seed() {
        insertCountry("DE", "DEU", "Germany", "Europe");
        for (int i = 0; i < 6; i++) {
            em.persist(Location.builder()
                    .mapillaryImageId("mly-de-" + i)
                    .position(Geo.toPoint(52.5 + i * 0.01, 13.4 + i * 0.01))
                    .countryCode("DE")
                    .qualityScore(0.9f)
                    .active(true)
                    .assetReady(true)
                    .build());
        }
        em.flush();

        long seed = 12_345L;
        UUID first = locations.sampleWithinCountry("DE", 0.4f, Set.of(), false, seed)
                .orElseThrow().getId();
        UUID second = locations.sampleWithinCountry("DE", 0.4f, Set.of(), false, seed)
                .orElseThrow().getId();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void panoramas_only_filter_excludes_flat_locations_from_both_reads() {
        insertCountry("SE", "SWE", "Sweden", "Europe");
        // Three panoramic, two flat -- all otherwise playable.
        for (int i = 0; i < 3; i++) {
            em.persist(Location.builder()
                    .mapillaryImageId("mly-se-pano-" + i)
                    .position(Geo.toPoint(59.3 + i * 0.01, 18.0 + i * 0.01))
                    .countryCode("SE").panoramic(true)
                    .qualityScore(0.9f).active(true).assetReady(true)
                    .build());
        }
        for (int i = 0; i < 2; i++) {
            em.persist(Location.builder()
                    .mapillaryImageId("mly-se-flat-" + i)
                    .position(Geo.toPoint(59.4 + i * 0.01, 18.1 + i * 0.01))
                    .countryCode("SE").panoramic(false)
                    .qualityScore(0.9f).active(true).assetReady(true)
                    .build());
        }
        em.flush();

        assertThat(locations.countActiveByCountry(0.4f, false).get("SE"))
                .as("the full pool counts all five locations")
                .isEqualTo(5);
        assertThat(locations.countActiveByCountry(0.4f, true).get("SE"))
                .as("panoramas-only counts only the three panoramic locations")
                .isEqualTo(3);

        // Every panoramas-only draw must land on a panoramic row. Vary the seed
        // so we exercise different orderings, not just one lucky hash.
        for (long seed = 0; seed < 20; seed++) {
            Location drawn = locations.sampleWithinCountry("SE", 0.4f, Set.of(), true, seed)
                    .orElseThrow();
            assertThat(drawn.isPanoramic())
                    .as("panoramas-only draw returned a flat location for seed %d", seed)
                    .isTrue();
        }
    }

    @Test
    void record_guess_if_unanswered_returns_one_then_zero() {
        insertCountry("JP", "JPN", "Japan", "Asia");

        AppUser user = em.persist(new AppUser("player-" + UUID.randomUUID(), null));
        Location location = em.persist(Location.builder()
                .mapillaryImageId("mly-jp-1")
                .position(Geo.toPoint(35.6762, 139.6503)) // Tokyo
                .countryCode("JP")
                .qualityScore(0.9f)
                .active(true)
                .assetReady(true)
                .build());
        Game game = em.persist(Game.builder()
                .userId(user.getId())
                .mode(GameMode.CLASSIC)
                .roundCount(5)
                .build());
        Round round = em.persist(Round.builder()
                .game(game)
                .roundIndex((short) 0)
                .location(location)
                .build());
        em.flush();

        int first = rounds.recordGuessIfUnanswered(
                round.getId(), 35.0, 139.0, 75_000.0, 3200, 8_000L);
        int second = rounds.recordGuessIfUnanswered(
                round.getId(), 10.0, 10.0, 5_000_000.0, 10, 9_000L);

        assertThat(first).as("first guess is recorded").isEqualTo(1);
        assertThat(second).as("second guess is rejected by the guessed_at guard").isEqualTo(0);
    }

    @Test
    void find_by_game_id_returns_prior_rounds_locations_for_exclusion() {
        insertCountry("DE", "DEU", "Germany", "Europe");
        insertCountry("SE", "SWE", "Sweden", "Europe");
        AppUser user = em.persist(new AppUser("player-" + UUID.randomUUID(), null));

        Location de = em.persist(Location.builder()
                .mapillaryImageId("mly-excl-de").position(Geo.toPoint(52.5, 13.4))
                .countryCode("DE").panoramic(true).qualityScore(0.9f).active(true).assetReady(true)
                .build());
        Location se = em.persist(Location.builder()
                .mapillaryImageId("mly-excl-se").position(Geo.toPoint(59.3, 18.0))
                .countryCode("SE").panoramic(true).qualityScore(0.9f).active(true).assetReady(true)
                .build());
        Game game = em.persist(Game.builder().userId(user.getId()).mode(GameMode.CLASSIC).roundCount(5).build());
        em.persist(Round.builder().game(game).roundIndex((short) 0).location(de).build());
        em.persist(Round.builder().game(game).roundIndex((short) 1).location(se).build());
        em.flush();

        // These are exactly the location ids and countries GameService feeds into
        // the sampler's exclusion sets so a game never repeats a place.
        assertThat(rounds.findByGameId(game.getId()))
                .extracting(r -> r.getLocation().getId())
                .containsExactlyInAnyOrder(de.getId(), se.getId());
        assertThat(rounds.findByGameId(game.getId()))
                .extracting(r -> r.getLocation().getCountryCode())
                .containsExactlyInAnyOrder("DE", "SE");
    }

    private void insertCountry(String iso2, String iso3, String name, String continent) {
        em.getEntityManager().createNativeQuery("""
                        INSERT INTO country (iso2, iso3, name, continent, centroid)
                        VALUES (:iso2, :iso3, :name, :continent,
                                ST_SetSRID(ST_MakePoint(0, 0), 4326)::geography)
                        """)
                .setParameter("iso2", iso2)
                .setParameter("iso3", iso3)
                .setParameter("name", name)
                .setParameter("continent", continent)
                .executeUpdate();
    }
}
