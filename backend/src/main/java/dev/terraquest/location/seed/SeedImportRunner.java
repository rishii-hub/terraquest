package dev.terraquest.location.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs the one-off seed imports from the command line, never on a plain boot.
 *
 * <p>Importing on every startup would be wrong -- it is slow, and it would fight
 * the harvester for the connection pool. So the loaders run only when their
 * option is passed:
 *
 * <pre>
 *   java -jar app.jar --terraquest.import.boundaries=/path/to/ne_admin0.geojson
 *   java -jar app.jar --terraquest.import.geonames=/path/to/cities15000.txt
 * </pre>
 *
 * Load boundaries first: the GeoNames candidates carry no country (it is resolved
 * later against these polygons), but the {@code country} reference table the
 * harvester's FK needs is seeded by the boundary load. Both loaders are
 * idempotent, so a re-run is safe.
 */
@Component
public class SeedImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedImportRunner.class);

    private static final String BOUNDARIES_ARG = "terraquest.import.boundaries";
    private static final String GEONAMES_ARG = "terraquest.import.geonames";

    private final NaturalEarthLoader boundaries;
    private final GeoNamesImporter geonames;

    public SeedImportRunner(NaturalEarthLoader boundaries, GeoNamesImporter geonames) {
        this.boundaries = boundaries;
        this.geonames = geonames;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path boundaryFile = optionPath(args, BOUNDARIES_ARG);
        if (boundaryFile != null) {
            log.info("Loading Natural Earth boundaries from {}", boundaryFile);
            boundaries.loadFrom(boundaryFile);
        }

        Path geonamesFile = optionPath(args, GEONAMES_ARG);
        if (geonamesFile != null) {
            log.info("Importing GeoNames seeds from {}", geonamesFile);
            geonames.importFrom(geonamesFile);
        }
    }

    private static Path optionPath(ApplicationArguments args, String name) {
        if (!args.containsOption(name)) {
            return null;
        }
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            throw new IllegalArgumentException("--" + name + " requires a file path");
        }
        return Path.of(values.get(0));
    }
}
