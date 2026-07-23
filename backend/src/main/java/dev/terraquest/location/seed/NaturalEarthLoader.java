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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
 * <p>ISO codes are read defensively. Natural Earth stores {@code -99} -- and, for
 * a few disputed territories, a hyphenated value such as {@code CN-TW} -- in the
 * primary {@code ISO_A2}/{@code ISO_A3} fields, and the real code in the
 * {@code _EH} ("de facto") fields. This is not a rare edge: <b>France and Norway
 * both carry {@code ISO_A2 = ISO_A3 = "-99"}</b> in this dataset, so reading only
 * the primary field silently drops two major countries from the pool -- and
 * {@code CN-TW} is five characters, which overflows the {@code CHAR(2)} column and
 * aborts the entire load. A code is therefore taken from {@code ISO_A2}, falling
 * back to {@code ISO_A2_EH} when the primary field is a sentinel or placeholder.
 *
 * <p>The {@code _EH} fallback is applied only to self-standing entities. For a
 * sub-national feature -- {@code TYPE} of {@code Dependency}, {@code Indeterminate}
 * or {@code Lease} (Clipperton I., Brazilian I., Baikonur) -- {@code ISO_A2_EH}
 * holds the <i>parent sovereign's</i> code, not one of its own. Honouring it would
 * make Clipperton resolve to {@code FR} and, via {@code ON CONFLICT (iso2) DO
 * UPDATE}, overwrite France's own boundary with a Pacific atoll's. Such features
 * are treated as having no code and skipped.
 *
 * <p>A feature with no usable ISO A2 is skipped entirely: a point falling only in
 * such a polygon resolves to no country and is excluded from play, which is the
 * intended behaviour, not a gap to paper over.
 */
@Service
public class NaturalEarthLoader {

    private static final Logger log = LoggerFactory.getLogger(NaturalEarthLoader.class);

    /** ISO 3166-1 alpha-2 / alpha-3 shapes: exactly two or three ASCII letters.
     *  Rejects the {@code -99} sentinel and hyphenated placeholders by construction. */
    private static final Pattern ISO2 = Pattern.compile("[A-Za-z]{2}");
    private static final Pattern ISO3 = Pattern.compile("[A-Za-z]{3}");

    /** Natural Earth {@code TYPE}s that are sub-national and carry their parent
     *  sovereign's code in {@code ISO_A2_EH}; the de-facto fallback must not honour
     *  it for these, or a dependency would overwrite the country it belongs to. */
    private static final Set<String> DEPENDENT_TYPES = Set.of("Dependency", "Indeterminate", "Lease");

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
            return "loaded=%d, skipped(no own ISO A2)=%d".formatted(loaded, skipped);
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

        String iso2 = resolveIso2(props);
        if (iso2 == null) {
            // No code of its own -- either unidentified (both fields "-99") or a
            // dependency that only borrows its parent's code. Excluded from play.
            return false;
        }
        // iso3 is NOT NULL. Prefer a clean alpha-3 from either field, then fall
        // back to the raw primary value and finally iso2, so a country with a
        // usable iso2 but no ISO 3166 alpha-3 (Kosovo) still loads rather than
        // being dropped or crashing the NOT NULL constraint.
        String iso3 = firstNonBlank(
                firstIso(props, ISO3, "ISO_A3", "ISO_A3_EH"),
                text(props, "ISO_A3", "iso_a3"),
                iso2);
        String name = firstNonBlank(text(props, "NAME", "name"), text(props, "ADMIN", "admin"));
        String continent = firstNonBlank(text(props, "CONTINENT", "continent"), "Unknown");
        String geomJson = geometry.toString();

        jdbc.update(UPSERT_BOUNDARY, iso2, name, geomJson);
        jdbc.update(UPSERT_COUNTRY, iso2, iso3, name, continent, geomJson);
        return true;
    }

    /**
     * The feature's own ISO alpha-2, or null if it has none. Prefers a well-formed
     * {@code ISO_A2}; when that is a sentinel/placeholder, falls back to
     * {@code ISO_A2_EH} unless the feature is a {@link #DEPENDENT_TYPES sub-national
     * dependency}, whose {@code _EH} value is its parent's code rather than its own.
     */
    private static String resolveIso2(JsonNode props) {
        String raw = text(props, "ISO_A2", "iso_a2");
        if (raw != null && ISO2.matcher(raw).matches()) {
            return raw.toUpperCase(Locale.ROOT);
        }
        String type = text(props, "TYPE", "type");
        if (type != null && DEPENDENT_TYPES.contains(type)) {
            return null;
        }
        String eh = text(props, "ISO_A2_EH", "iso_a2_eh");
        return (eh != null && ISO2.matcher(eh).matches()) ? eh.toUpperCase(Locale.ROOT) : null;
    }

    /**
     * The first of the given property keys whose value is a well-formed ISO code
     * of the expected shape, upper-cased. Natural Earth's {@code -99} sentinel and
     * hyphenated placeholders (e.g. {@code CN-TW}) fail the shape check and are
     * passed over, so a later {@code _EH} field can supply the real code. Used for
     * alpha-3, where the de-facto field is always the entity's own.
     */
    private static String firstIso(JsonNode props, Pattern shape, String... keys) {
        for (String key : keys) {
            String value = text(props, key, key.toLowerCase(Locale.ROOT));
            if (value != null && shape.matcher(value).matches()) {
                return value.toUpperCase(Locale.ROOT);
            }
        }
        return null;
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
