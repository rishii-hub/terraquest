package dev.terraquest.location;

import dev.terraquest.admin.HarvestStats;
import dev.terraquest.admin.HarvestStatsService;
import dev.terraquest.admin.HarvestStatsServiceImpl;
import dev.terraquest.shared.Geo;
import dev.terraquest.shared.GeoPoint;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostGIS-backed coverage for the pool-bootstrap queries: country resolution via
 * {@code ST_Covers}, the retry-aware unprobed queue, ingest-failure accounting,
 * and the harvest-stats aggregates. None of these can be proven against a mock or
 * H2 -- point-in-polygon and {@code FILTER} aggregates need a real PostGIS.
 *
 * <p>Skipped without Docker so {@code mvn verify} stays green on a Docker-less
 * box; runs for real in CI, which is the environment that must catch a bad
 * migration or a wrong predicate.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import({LocationRepositoryImpl.class, CandidatePointRepositoryImpl.class,
        CountryResolverImpl.class, HarvestStatsServiceImpl.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class PoolBootstrapIT {

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @Autowired private CountryResolver countryResolver;
    @Autowired private CandidatePointRepository candidates;
    @Autowired private LocationRepository locations;
    @Autowired private HarvestStatsService stats;
    @Autowired private TestEntityManager em;

    // ---------------------------------------------------------------
    // Country resolution
    // ---------------------------------------------------------------

    @Test
    void resolver_covers_points_inside_and_on_the_boundary_but_not_outside() {
        // A one-degree square around Berlin, stored as a MultiPolygon.
        em.getEntityManager().createNativeQuery("""
                        INSERT INTO country_boundary (iso2, name, geom) VALUES
                        ('DE', 'Germany', ST_Multi(ST_GeomFromText(
                            'POLYGON((13 52, 14 52, 14 53, 13 53, 13 52))', 4326)))
                        """).executeUpdate();
        em.flush();

        assertThat(countryResolver.resolve(new GeoPoint(52.5, 13.4)))
                .as("a point well inside resolves").contains("DE");
        assertThat(countryResolver.resolve(new GeoPoint(52.5, 13.0)))
                .as("ST_Covers includes boundary points; ST_Contains would not")
                .contains("DE");
        assertThat(countryResolver.resolve(new GeoPoint(0.0, 0.0)))
                .as("a point in no polygon is offshore/disputed, resolved to nothing")
                .isEmpty();
    }

    // ---------------------------------------------------------------
    // Retry-aware unprobed queue
    // ---------------------------------------------------------------

    @Test
    void find_unprobed_excludes_probed_and_retry_exhausted_points() {
        insertCandidate(52.5, 13.4, null, 0);            // fresh, retriable
        insertCandidate(52.6, 13.5, null, 2);            // failed twice, still retriable
        insertCandidate(52.7, 13.6, Instant.now(), 0);   // already probed
        insertCandidate(52.8, 13.7, null, 3);            // retries exhausted
        em.flush();

        List<CandidatePoint> unprobed = candidates.findUnprobed(10);

        assertThat(unprobed)
                .as("only the fresh and still-retriable points are queued")
                .hasSize(2);
        assertThat(unprobed).allMatch(c -> !c.isProbed() && !c.hasExhaustedRetries(3));
    }

    // ---------------------------------------------------------------
    // Ingest-failure accounting
    // ---------------------------------------------------------------

    @Test
    void record_ingest_failure_increments_the_attempt_counter() {
        insertCountry("BR", "BRA", "Brazil", "South America");
        UUID id = persistLocation("BR", true, true).getId();
        em.flush();

        locations.recordIngestFailure(id);
        locations.recordIngestFailure(id);
        em.clear();

        assertThat(em.find(Location.class, id).getIngestAttempts()).isEqualTo(2);
    }

    // ---------------------------------------------------------------
    // Harvest-stats aggregates
    // ---------------------------------------------------------------

    @Test
    void harvest_stats_reports_pools_candidates_and_assets() {
        insertCountry("DE", "DEU", "Germany", "Europe");

        Location pano1 = persistLocation("DE", true, true);   // playable panorama
        persistLocation("DE", true, true);                    // playable panorama
        persistLocation("DE", false, true);                   // playable flat
        persistLocation("DE", false, false);                  // not ready, untried
        Location failed = persistLocation("DE", false, false); // not ready, ingest failed
        em.flush();

        locations.recordIngestFailure(failed.getId());
        insertImageAsset(pano1.getId(), true); // one ingested asset

        insertCandidate(52.5, 13.4, null, 0);          // unprobed
        insertCandidate(52.6, 13.5, Instant.now(), 0); // probed
        insertCandidate(52.7, 13.6, null, 3);          // retry-exhausted
        em.flush();

        HarvestStats result = stats.compute();

        HarvestStats.CountryPool de = result.pools().stream()
                .filter(p -> p.countryCode().equals("DE")).findFirst().orElseThrow();
        assertThat(de.panoramic()).isEqualTo(2);
        assertThat(de.flat()).isEqualTo(1);
        assertThat(result.countriesAbovePanoFloor())
                .as("2 panoramas is below the floor of 15").isZero();

        assertThat(result.candidates().total()).isEqualTo(3);
        assertThat(result.candidates().probed()).isEqualTo(1);
        assertThat(result.candidates().unprobed()).isEqualTo(1);
        assertThat(result.candidates().retryExhausted()).isEqualTo(1);

        assertThat(result.assets().ingested()).isEqualTo(1);
        assertThat(result.assets().awaitingIngest()).as("one not-ready, untried").isEqualTo(1);
        assertThat(result.assets().failed()).as("one not-ready, attempted").isEqualTo(1);
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private Location persistLocation(String country, boolean pano, boolean assetReady) {
        return em.persist(Location.builder()
                .mapillaryImageId("mly-" + UUID.randomUUID())
                .position(Geo.toPoint(52.5, 13.4))
                .countryCode(country)
                .panoramic(pano)
                .qualityScore(0.9f)
                .active(true)
                .assetReady(assetReady)
                .build());
    }

    private void insertCandidate(double lat, double lon, Instant probedAt, int failureCount) {
        em.getEntityManager().createNativeQuery("""
                        INSERT INTO candidate_point (position, source, probed_at, failure_count)
                        VALUES (ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                                'test', CAST(:probedAt AS timestamptz), :failureCount)
                        """)
                .setParameter("lon", lon)
                .setParameter("lat", lat)
                .setParameter("probedAt", probedAt)
                .setParameter("failureCount", failureCount)
                .executeUpdate();
    }

    private void insertImageAsset(UUID locationId, boolean exifStripped) {
        em.getEntityManager().createNativeQuery("""
                        INSERT INTO image_asset
                            (location_id, storage_key, projection, width, height, bytes, exif_stripped)
                        VALUES (:locationId, :key, 'EQUIRECTANGULAR'::projection_type,
                                2048, 1024, 1024, :exif)
                        """)
                .setParameter("locationId", locationId)
                .setParameter("key", "test/" + UUID.randomUUID())
                .setParameter("exif", exifStripped)
                .executeUpdate();
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
