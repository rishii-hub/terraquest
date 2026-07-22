package dev.terraquest.location;

import dev.terraquest.imagery.ImageryProvider;
import dev.terraquest.imagery.ImageryProvider.SourceImage;
import dev.terraquest.shared.GeoPoint;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harvester decision logic, tested against fakes.
 *
 * <p>None of these tests touch the network, a database, or Spring. That is the
 * dividend of the SPI refactor and the reason this suite exists in the same PR:
 * the quality filter decides what players see, and before this change it had
 * zero coverage because exercising it required live Mapillary credentials.
 *
 * <p>The fixed clock matters. Playability depends on image age; tests pinned to
 * {@code Instant.now()} rot silently as the calendar advances past their
 * fixtures.
 */
class LocationHarvesterTest {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final GeoPoint BERLIN = new GeoPoint(52.52, 13.405);

    // ---------------------------------------------------------------
    // Playability filter
    // ---------------------------------------------------------------

    @Test
    void panorama_with_recent_capture_is_playable() {
        var harvester = harvesterWith(new StubProvider(List.of()));
        assertThat(harvester.isPlayable(pano(NOW.minus(30, ChronoUnit.DAYS)))).isTrue();
    }

    @Test
    void flat_image_without_compass_angle_is_rejected() {
        var harvester = harvesterWith(new StubProvider(List.of()));
        SourceImage noCompass = flat(NOW.minus(30, ChronoUnit.DAYS), null);
        assertThat(harvester.isPlayable(noCompass))
                .as("a flat image we cannot orient makes an unfair round")
                .isFalse();
    }

    @Test
    void image_without_capture_date_is_rejected() {
        var harvester = harvesterWith(new StubProvider(List.of()));
        assertThat(harvester.isPlayable(pano(null))).isFalse();
    }

    @Test
    void image_older_than_seven_years_is_rejected() {
        var harvester = harvesterWith(new StubProvider(List.of()));
        SourceImage ancient = pano(NOW.minus(2600, ChronoUnit.DAYS));
        assertThat(harvester.isPlayable(ancient)).isFalse();
    }

    // ---------------------------------------------------------------
    // Quality scoring
    // ---------------------------------------------------------------

    @Test
    void panoramas_always_outrank_flat_images() {
        var harvester = harvesterWith(new StubProvider(List.of()));

        // Worst pano (old) vs best flat (fresh): the pano must still win,
        // because look-around is worth more than recency ever is.
        float oldPano = harvester.qualityScore(pano(NOW.minus(2500, ChronoUnit.DAYS)));
        float freshFlat = harvester.qualityScore(flat(NOW, 90f));

        assertThat(oldPano).isGreaterThan(freshFlat);
    }

    @Test
    void quality_score_stays_within_unit_interval() {
        var harvester = harvesterWith(new StubProvider(List.of()));
        assertThat(harvester.qualityScore(pano(NOW))).isBetween(0f, 1f);
        assertThat(harvester.qualityScore(flat(NOW.minus(2500, ChronoUnit.DAYS), 0f)))
                .isBetween(0f, 1f);
    }

    // ---------------------------------------------------------------
    // Provider failure isolation
    // ---------------------------------------------------------------

    @Test
    void one_failing_provider_does_not_lose_results_from_the_others() {
        var healthy = new StubProvider(List.of(pano(NOW.minus(10, ChronoUnit.DAYS))));
        var broken = new StubProvider(List.of()) {
            @Override
            public List<SourceImage> findNear(GeoPoint c, double r, int l) {
                throw new RuntimeException("simulated rate limit");
            }
        };

        var harvester = harvesterWith(broken, healthy);
        int kept = harvester.harvestPoint(candidateAt(BERLIN));

        assertThat(kept)
                .as("healthy provider's results must survive a sibling's failure")
                .isEqualTo(1);
    }

    @Test
    void per_point_cap_limits_a_flood_of_good_imagery() {
        List<SourceImage> flood = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> pano("img-" + i, NOW.minus(i, ChronoUnit.DAYS)))
                .toList();

        var harvester = harvesterWith(new StubProvider(flood));
        int kept = harvester.harvestPoint(candidateAt(BERLIN));

        assertThat(kept)
                .as("one well-mapped intersection must not flood the pool")
                .isEqualTo(3);
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private LocationHarvester harvesterWith(ImageryProvider... providers) {
        return new LocationHarvester(
                List.of(providers),
                new InMemoryCandidateRepository(),
                new InMemoryLocationRepository(),
                new RecordingAssetService(),
                FIXED);
    }

    private static SourceImage pano(Instant captured) {
        return pano("p-1", captured);
    }

    private static SourceImage pano(String id, Instant captured) {
        return new SourceImage("stub", id, "seq-1", BERLIN, null, true, captured, "tester", "u1");
    }

    private static SourceImage flat(Instant captured, Float compass) {
        return new SourceImage("stub", "f-1", "seq-1", BERLIN, compass, false, captured, "tester", "u1");
    }

    private static CandidatePoint candidateAt(GeoPoint p) {
        return CandidatePoint.of(p, "DE", "test");
    }

    /** Minimal well-behaved provider; override to simulate misbehaviour. */
    private static class StubProvider implements ImageryProvider {
        private final List<SourceImage> images;

        StubProvider(List<SourceImage> images) {
            this.images = images;
        }

        @Override public String id() { return "stub"; }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, true, false, 1000);
        }

        @Override
        public List<SourceImage> findNear(GeoPoint centre, double radiusMetres, int limit) {
            return images.stream().limit(limit).toList();
        }

        @Override
        public Optional<String> resolveImageUrl(String externalId, ImageSize size) {
            return Optional.of("https://stub.example/" + externalId);
        }

        @Override
        public Attribution attributionFor(SourceImage image) {
            return new Attribution("tester", null, "CC0-1.0", null, null);
        }
    }
}
