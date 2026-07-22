package dev.terraquest.location.seed;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads Natural Earth Admin 0 country polygons (public domain) into
 * {@code country_boundary}, and seeds the {@code country} reference table from
 * the same features.
 *
 * <p>This is the source of truth for reverse geocoding: {@code CountryResolver}
 * runs {@code ST_Covers} against {@code country_boundary}, and {@code location}'s
 * {@code country_code} FK points at {@code country}. Seeding both from one source
 * keeps their ISO codes aligned, so a resolved country always satisfies the FK.
 *
 * <p>Features are read one at a time from the GeoJSON {@code features} array
 * rather than parsed into one giant tree, so a full-resolution export does not
 * have to fit in memory at once.
 *
 * <p>Features whose ISO A2 code is absent or {@code -99} (Natural Earth's marker
 * for disputed or unrecognised territories) are skipped: a point falling only in
 * such a polygon resolves to no country and is excluded from play, which is the
 * intended behaviour, not a gap to paper over.
 */
@Service
public class NaturalEarthLoader {

    private static final Logger log = LoggerFactory.getLogger(NaturalEarthLoader.class);

    private static final String NO_ISO = "-99";

    private static final String UPSERT_BOUNDARY = """
            INSERT INTO country_boundary (iso2, name, geom)
            VALUES (?, ?, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)))
            ON CONFLICT (iso2) DO UPDATE SET name = EXCLUDED.name, geom = EXCLUDED.geom
            """;

    // Centroid via ST_PointOnSurface, not ST_Centroid: it is guaranteed to lie
    // inside the polygon, whereas a true centroid can fall in the sea for a
    // crescent-shaped country.
    private static final String UPSERT_COUNTRY = """
            INSERT INTO country (iso2, iso3, name, continent, centroid)
            VALUES (?, ?, ?, ?,
                    ST_PointOnSurface(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))::geography)
            ON CONFLICT (iso2) DO UPDATE SET
                iso3 = EXCLUDED.iso3, name = EXCLUDED.name,
                continent = EXCLUDED.continent, centroid = EXCLUDED.centroid
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NaturalEarthLoader(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record LoadSummary(int loaded, int skipped) {
        @Override
        public String toString() {
            return "loaded=%d, skipped(no ISO A2)=%d".formatted(loaded, skipped);
        }
    }

    @Transactional
    public LoadSummary loadFrom(Path geoJson) throws IOException {
        int loaded = 0;
        int skipped = 0;

        try (BufferedReader reader = Files.newBufferedReader(geoJson, StandardCharsets.UTF_8);
             JsonParser parser = mapper.getFactory().createParser(reader)) {

            advanceToFeatures(parser);
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode feature = mapper.readTree(parser);
                if (loadFeature(feature)) {
                    loaded++;
                } else {
                    skipped++;
                }
            }
        }

        LoadSummary summary = new LoadSummary(loaded, skipped);
        log.info("Natural Earth load from {}: {}", geoJson, summary);
        return summary;
    }

    /** Position the parser at the first element of the top-level features array. */
    private static void advanceToFeatures(JsonParser parser) throws IOException {
        while (parser.nextToken() != null) {
            if (parser.currentToken() == JsonToken.FIELD_NAME
                    && "features".equals(parser.currentName())) {
                parser.nextToken(); // consume START_ARRAY
                return;
            }
        }
        throw new IOException("GeoJSON has no 'features' array");
    }

    private boolean loadFeature(JsonNode feature) {
        JsonNode props = feature.path("properties");
        JsonNode geometry = feature.path("geometry");
        if (geometry.isMissingNode() || geometry.isNull()) {
            return false;
        }

        String iso2 = text(props, "ISO_A2", "iso_a2");
        if (iso2 == null || iso2.isBlank() || NO_ISO.equals(iso2)) {
            return false;
        }
        String iso3 = firstNonBlank(text(props, "ISO_A3", "iso_a3"), iso2);
        String name = firstNonBlank(text(props, "NAME", "name"), text(props, "ADMIN", "admin"));
        String continent = firstNonBlank(text(props, "CONTINENT", "continent"), "Unknown");
        String geomJson = geometry.toString();

        jdbc.update(UPSERT_BOUNDARY, iso2, name, geomJson);
        jdbc.update(UPSERT_COUNTRY, iso2, iso3, name, continent, geomJson);
        return true;
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
