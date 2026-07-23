package dev.terraquest.location.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostGIS-backed coverage for the seed loaders: GeoNames import idempotency and
 * Natural Earth boundary/country seeding. Both write via native PostGIS SQL, so
 * only a real database proves they behave.
 *
 * <p>Skipped without Docker; runs in CI.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class SeedImportIT {

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;
    private GeoNamesImporter importer;
    private NaturalEarthLoader loader;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        importer = new GeoNamesImporter(jdbc);
        loader = new NaturalEarthLoader(jdbc, new ObjectMapper());
    }

    // ---------------------------------------------------------------
    // GeoNames importer
    // ---------------------------------------------------------------

    @Test
    void geonames_import_is_idempotent_and_dedupes_on_rounded_coordinates(@TempDir Path dir)
            throws IOException {
        // Three rows: two distinct cities, plus a near-duplicate of the first
        // that rounds to the same key. Column 8 (country) is present but ignored.
        Path file = dir.resolve("cities15000.txt");
        Files.writeString(file, String.join("\n",
                geoNamesRow("2950159", "Berlin", 52.52437, 13.41053, "DE"),
                geoNamesRow("6545310", "Berlin Mitte", 52.52401, 13.41090, "DE"), // same key
                geoNamesRow("2988507", "Paris", 48.85341, 2.34880, "FR")
        ), StandardCharsets.UTF_8);

        GeoNamesImporter.ImportSummary first = importer.importFrom(file);
        assertThat(first.parsed()).isEqualTo(3);
        assertThat(first.inserted())
                .as("the near-duplicate is deduped on its rounded coordinate key")
                .isEqualTo(2);
        assertThat(countGeonames()).isEqualTo(2);

        GeoNamesImporter.ImportSummary second = importer.importFrom(file);
        assertThat(second.inserted()).as("re-running inserts nothing").isZero();
        assertThat(countGeonames()).as("row count is stable across runs").isEqualTo(2);
    }

    // ---------------------------------------------------------------
    // Natural Earth loader
    // ---------------------------------------------------------------

    @Test
    void natural_earth_load_seeds_boundaries_and_countries_and_skips_unidentified(@TempDir Path dir)
            throws IOException {
        Path file = dir.resolve("ne_admin0.geojson");
        Files.writeString(file, """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature",
                   "properties":{"ISO_A2":"DE","ISO_A3":"DEU","NAME":"Germany","CONTINENT":"Europe"},
                   "geometry":{"type":"Polygon","coordinates":[[[13,52],[14,52],[14,53],[13,53],[13,52]]]}},
                  {"type":"Feature",
                   "properties":{"ISO_A2":"-99","ISO_A3":"-99","NAME":"Disputed","CONTINENT":"Asia"},
                   "geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}}
                ]}
                """, StandardCharsets.UTF_8);

        NaturalEarthLoader.LoadSummary summary = loader.loadFrom(file);

        assertThat(summary.loaded()).isEqualTo(1);
        assertThat(summary.skipped()).as("the -99 disputed feature is skipped").isEqualTo(1);

        assertThat(count("SELECT count(*) FROM country_boundary")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM country WHERE iso2 = 'DE'", String.class)).isEqualTo("Germany");
        assertThat(count("SELECT count(*) FROM country WHERE iso2 = '-99'")).isZero();

        // The seeded centroid must fall inside the polygon (ST_PointOnSurface).
        assertThat(count("""
                SELECT count(*) FROM country c
                JOIN country_boundary b ON b.iso2 = c.iso2
                WHERE ST_Covers(b.geom, c.centroid::geometry)
                """)).isEqualTo(1);
    }

    @Test
    void de_facto_iso_fields_resolve_sovereigns_without_dependencies_clobbering_them(@TempDir Path dir)
            throws IOException {
        // Property shapes copied verbatim from ne_10m_admin_0_countries.geojson.
        // Two things the real data exposes and this asserts:
        //   1. France / Norway have ISO_A2 = ISO_A3 = "-99" (real code only in
        //      *_EH); Taiwan has ISO_A2 = "CN-TW" (5 chars). All must resolve.
        //   2. Clipperton (Dependency) and Brazilian I. (Indeterminate) carry the
        //      PARENT's code in ISO_A2_EH (FR, BR). Listed AFTER France/Brazil, a
        //      naive fallback would let them overwrite those countries' boundaries
        //      via ON CONFLICT DO UPDATE. They must be skipped, not honoured.
        Path file = dir.resolve("ne_admin0.geojson");
        Files.writeString(file, """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature",
                   "properties":{"TYPE":"Sovereign country","ISO_A2":"BR","ISO_A3":"BRA","NAME":"Brazil","CONTINENT":"South America"},
                   "geometry":{"type":"Polygon","coordinates":[[[-50,-15],[-49,-15],[-49,-14],[-50,-14],[-50,-15]]]}},
                  {"type":"Feature",
                   "properties":{"TYPE":"Country","ISO_A2":"-99","ISO_A2_EH":"FR","ISO_A3":"-99","ISO_A3_EH":"FRA","NAME":"France","CONTINENT":"Europe"},
                   "geometry":{"type":"Polygon","coordinates":[[[2,48],[3,48],[3,49],[2,49],[2,48]]]}},
                  {"type":"Feature",
                   "properties":{"TYPE":"Sovereign country","ISO_A2":"-99","ISO_A2_EH":"NO","ISO_A3":"-99","ISO_A3_EH":"NOR","NAME":"Norway","CONTINENT":"Europe"},
                   "geometry":{"type":"Polygon","coordinates":[[[10,60],[11,60],[11,61],[10,61],[10,60]]]}},
                  {"type":"Feature",
                   "properties":{"TYPE":"Sovereign country","ISO_A2":"CN-TW","ISO_A2_EH":"TW","ISO_A3":"TWN","ISO_A3_EH":"TWN","NAME":"Taiwan","CONTINENT":"Asia"},
                   "geometry":{"type":"Polygon","coordinates":[[[120,23],[121,23],[121,24],[120,24],[120,23]]]}},
                  {"type":"Feature",
                   "properties":{"TYPE":"Dependency","ISO_A2":"-99","ISO_A2_EH":"FR","ISO_A3":"-99","ISO_A3_EH":"FRA","NAME":"Clipperton I.","CONTINENT":"Seven seas (open ocean)"},
                   "geometry":{"type":"Polygon","coordinates":[[[-110,10],[-109,10],[-109,11],[-110,11],[-110,10]]]}},
                  {"type":"Feature",
                   "properties":{"TYPE":"Indeterminate","ISO_A2":"-99","ISO_A2_EH":"BR","ISO_A3":"-99","ISO_A3_EH":"BRA","NAME":"Brazilian I.","CONTINENT":"South America"},
                   "geometry":{"type":"Polygon","coordinates":[[[-28,-20],[-27,-20],[-27,-19],[-28,-19],[-28,-20]]]}},
                  {"type":"Feature",
                   "properties":{"TYPE":"Indeterminate","ISO_A2":"-99","ISO_A2_EH":"-99","ISO_A3":"-99","ISO_A3_EH":"-99","NAME":"Bir Tawil","CONTINENT":"Africa"},
                   "geometry":{"type":"Polygon","coordinates":[[[33,21],[34,21],[34,22],[33,22],[33,21]]]}}
                ]}
                """, StandardCharsets.UTF_8);

        NaturalEarthLoader.LoadSummary summary = loader.loadFrom(file);

        assertThat(summary.loaded())
                .as("Brazil, France, Norway, Taiwan resolve; the three sub-national features do not")
                .isEqualTo(4);
        assertThat(summary.skipped())
                .as("Clipperton, Brazilian I. and code-less Bir Tawil are skipped")
                .isEqualTo(3);

        // The France/Norway recovery (the important half of the fix).
        assertThat(iso3Of("FR")).isEqualTo("FRA");
        assertThat(iso3Of("NO")).isEqualTo("NOR");
        assertThat(iso3Of("TW")).isEqualTo("TWN");

        // The dependency-clobber guard: FR is France, not the Pacific atoll that
        // borrows its code; BR is Brazil, not "Brazilian I.".
        assertThat(nameOf("FR")).isEqualTo("France");
        assertThat(nameOf("BR")).isEqualTo("Brazil");
        assertThat(count("SELECT count(*) FROM country WHERE name IN ('Clipperton I.','Brazilian I.')"))
                .as("no sub-national feature ever becomes a country row")
                .isZero();
        assertThat(count("SELECT count(*) FROM country WHERE iso2 IN ('-99','CN')"))
                .as("no sentinel or truncated code is ever stored")
                .isZero();
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private String iso3Of(String iso2) {
        return jdbc.queryForObject("SELECT iso3 FROM country WHERE iso2 = ?", String.class, iso2).trim();
    }

    private String nameOf(String iso2) {
        return jdbc.queryForObject("SELECT name FROM country WHERE iso2 = ?", String.class, iso2);
    }

    private static String geoNamesRow(String id, String name, double lat, double lon, String cc) {
        // GeoNames layout: id, name, asciiname, alternatenames, lat, lon,
        // feature_class, feature_code, country_code, ... (only 4,5 are used).
        return String.join("\t",
                id, name, name, "", String.valueOf(lat), String.valueOf(lon), "P", "PPL", cc);
    }

    private long countGeonames() {
        return count("SELECT count(*) FROM candidate_point WHERE source = 'geonames'");
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
