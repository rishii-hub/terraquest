package dev.terraquest.location.seed;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Streams a GeoNames {@code cities15000.txt} export into {@code candidate_point}.
 *
 * <p>Data is GeoNames, CC BY 4.0 (attributed in the README). The file is
 * tab-separated; column 4 is latitude, column 5 is longitude. Column 8 (the
 * country code) is deliberately ignored: GeoNames codes are unreliable near
 * borders, so a location's country is resolved from Natural Earth polygons at
 * harvest time instead.
 *
 * <p><b>Streaming, not slurping.</b> The file is read a line at a time and
 * inserted in batches; it is never loaded into memory whole. A ~25k-row export
 * imports in one pass with bounded heap.
 *
 * <p><b>Idempotent.</b> Each row's {@code import_key} is its coordinate rounded
 * to three decimals (~110 m). Inserts use {@code ON CONFLICT (import_key) DO
 * NOTHING}, so re-running the import against the same file adds nothing. Dedupe
 * is on rounded coordinates, never on name -- GeoNames has many same-named
 * places.
 */
@Service
public class GeoNamesImporter {

    private static final Logger log = LoggerFactory.getLogger(GeoNamesImporter.class);

    private static final int LAT_COLUMN = 4;
    private static final int LON_COLUMN = 5;
    private static final int BATCH_SIZE = 1000;

    private static final String INSERT_SQL = """
            INSERT INTO candidate_point (position, country_code, source, import_key)
            VALUES (ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, NULL, 'geonames', ?)
            ON CONFLICT (import_key) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public GeoNamesImporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Parsed coordinates plus the coordinate-rounded idempotency key. */
    private record Seed(double lat, double lon, String importKey) {}

    public record ImportSummary(long parsed, long malformed, int inserted) {
        @Override
        public String toString() {
            return "parsed=%d, inserted=%d, skipped(existing/duplicate)=%d, malformed=%d"
                    .formatted(parsed, inserted, parsed - inserted, malformed);
        }
    }

    @Transactional
    public ImportSummary importFrom(Path file) throws IOException {
        long parsed = 0;
        long malformed = 0;
        int inserted = 0;
        List<Seed> batch = new ArrayList<>(BATCH_SIZE);

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Seed seed = parse(line);
                if (seed == null) {
                    malformed++;
                    continue;
                }
                parsed++;
                batch.add(seed);
                if (batch.size() >= BATCH_SIZE) {
                    inserted += flush(batch);
                    batch.clear();
                }
            }
            inserted += flush(batch);
        }

        ImportSummary summary = new ImportSummary(parsed, malformed, inserted);
        log.info("GeoNames import from {}: {}", file, summary);
        return summary;
    }

    private static Seed parse(String line) {
        String[] cols = line.split("\t");
        if (cols.length <= LON_COLUMN) {
            return null;
        }
        try {
            double lat = Double.parseDouble(cols[LAT_COLUMN].trim());
            double lon = Double.parseDouble(cols[LON_COLUMN].trim());
            // Rounded-coordinate dedupe key. Locale.ROOT so the decimal point is
            // '.', not a locale-specific comma that would break the key format.
            String key = String.format(Locale.ROOT, "%.3f:%.3f", lat, lon);
            return new Seed(lat, lon, key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int flush(List<Seed> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int[][] results = jdbc.batchUpdate(INSERT_SQL, batch, batch.size(), (ps, seed) -> {
            ps.setDouble(1, seed.lon()); // ST_MakePoint is (x=lon, y=lat)
            ps.setDouble(2, seed.lat());
            ps.setString(3, seed.importKey());
        });
        int inserted = 0;
        for (int[] chunk : results) {
            for (int rows : chunk) {
                // ON CONFLICT DO NOTHING reports 0 for a skipped row.
                if (rows > 0) {
                    inserted++;
                }
            }
        }
        return inserted;
    }
}
