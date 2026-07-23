package dev.terraquest.location;

import dev.terraquest.imagery.ImageryProvider;
import dev.terraquest.imagery.ImageryProvider.SourceImage;
import dev.terraquest.imagery.ProviderException;
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
        LocationHarvester.PointOutcome outcome = harvester.harvestPoint(candidateAt(BERLIN));

        assertThat(outcome.kept())
                .as("healthy provider's results must survive a sibling's failure")
                .isEqualTo(1);
        assertThat(outcome.probeSucceeded())
                .as("one provider responding is a definitive probe, not a failure")
                .isTrue();
    }

    @Test
    void per_point_cap_limits_a_flood_of_good_imagery() {
        List<SourceImage> flood = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> pano("img-" + i, NOW.minus(i, ChronoUnit.DAYS)))
                .toList();

        var harvester = harvesterWith(new StubProvider(flood));
        LocationHarvester.PointOutcome outcome = harvester.harvestPoint(candidateAt(BERLIN));

        assertThat(outcome.kept())
                .as("one well-mapped intersection must not flood the pool")
                .isEqualTo(3);
    }

    // ---------------------------------------------------------------
    // Capture-date validity floor
    // ---------------------------------------------------------------

    @Test
    void epoch_zero_capture_date_is_rejected_as_corrupt_metadata() {
        var harvester = harvesterWith(new StubProvider(List.of()));
        // 1970-01-01 is epoch zero: broken EXIF, not an old photo.
        assertThat(harvester.isPlayable(pano(Instant.parse("1970-01-01T00:00:00Z"))))
                .as("epoch-zero timestamps are corrupt, rejected deliberately")
                .isFalse();
    }

    @Test
    void capture_date_before_mapillary_existed_is_rejected() {
        var harvester = harvesterWith(new StubProvider(List.of()));
        // Mapillary did not exist before 2008; a 1989 date is broken metadata.
        assertThat(harvester.isPlayable(pano(Instant.parse("1989-06-01T00:00:00Z")))).isFalse();
    }

    // ---------------------------------------------------------------
    // Country resolution at the image point
    // ---------------------------------------------------------------

    @Test
    void a_point_resolving_to_no_country_is_not_persisted() {
        var harvester = new LocationHarvester(
                List.of(new StubProvider(List.of(pano(NOW.minus(10, ChronoUnit.DAYS))))),
                new InMemoryCandidateRepository(),
                new InMemoryLocationRepository(),
                new InMemoryCountryResolver(null), // offshore / disputed
                new RecordingAssetService(),
                FIXED);

        LocationHarvester.PointOutcome outcome = harvester.harvestPoint(candidateAt(BERLIN));

        assertThat(outcome.kept())
                .as("an unresolvable point is excluded from play, not guessed")
                .isZero();
        assertThat(outcome.probeSucceeded())
                .as("skipping on an unresolved country is not a probe failure")
                .isTrue();
    }

    // ---------------------------------------------------------------
    // Retry on transient failure (the data-loss bug this PR fixes)
    // ---------------------------------------------------------------

    @Test
    void a_transient_probe_failure_retries_instead_of_discarding_the_point() {
        var candidates = new InMemoryCandidateRepository();
        candidates.saveAll(List.of(candidateAt(BERLIN)));
        var harvester = batchHarvester(candidates, alwaysFailing());

        harvester.harvestBatch();

        CandidatePoint point = candidates.saved.get(0);
        assertThat(point.isProbed())
                .as("a server hiccup must not stamp the point probed")
                .isFalse();
        assertThat(point.getFailureCount()).isEqualTo(1);
        assertThat(candidates.findUnprobed(10))
                .as("still retriable after one failure")
                .hasSize(1);
    }

    @Test
    void a_point_drops_out_of_the_queue_after_exhausting_its_retries() {
        var candidates = new InMemoryCandidateRepository();
        candidates.saveAll(List.of(candidateAt(BERLIN)));
        var harvester = batchHarvester(candidates, alwaysFailing());

        for (int i = 0; i < 3; i++) {
            harvester.harvestBatch();
        }

        CandidatePoint point = candidates.saved.get(0);
        assertThat(point.getFailureCount()).isEqualTo(3);
        assertThat(point.isProbed()).isFalse();
        assertThat(candidates.findUnprobed(10))
                .as("three consecutive failures retire the point for good")
                .isEmpty();
    }

    @Test
    void a_retryable_provider_failure_keeps_the_point_in_the_queue() {
        var candidates = new InMemoryCandidateRepository();
        candidates.saveAll(List.of(candidateAt(BERLIN)));
        // 5xx / 429 / timeout: another attempt may succeed.
        var harvester = batchHarvester(candidates, failingWith(retryable()));

        harvester.harvestBatch();

        CandidatePoint point = candidates.saved.get(0);
        assertThat(point.isProbed())
                .as("a retryable failure must not stamp the point probed")
                .isFalse();
        assertThat(point.getFailureCount()).isEqualTo(1);
        assertThat(candidates.findUnprobed(10))
                .as("still retriable after a transient failure")
                .hasSize(1);
    }

    @Test
    void a_non_retryable_provider_failure_retires_the_point_without_retrying() {
        var candidates = new InMemoryCandidateRepository();
        candidates.saveAll(List.of(candidateAt(BERLIN)));
        // A 4xx (bad request/token): retrying only repeats the same rejection.
        var harvester = batchHarvester(candidates, failingWith(permanent()));

        harvester.harvestBatch();

        CandidatePoint point = candidates.saved.get(0);
        assertThat(point.isProbed())
                .as("a permanent 4xx is a verdict: mark it probed and move on")
                .isTrue();
        assertThat(point.getFailureCount())
                .as("a non-retryable failure must not burn a retry")
                .isZero();
        assertThat(candidates.findUnprobed(10))
                .as("a malformed request is not worth probing again")
                .isEmpty();
    }

    @Test
    void a_successful_probe_with_no_imagery_still_retires_the_point() {
        var candidates = new InMemoryCandidateRepository();
        candidates.saveAll(List.of(candidateAt(BERLIN)));
        var harvester = batchHarvester(candidates, new StubProvider(List.of()));

        harvester.harvestBatch();

        CandidatePoint point = candidates.saved.get(0);
        assertThat(point.isProbed())
                .as("'probed, nothing here' is a real result that retires the point")
                .isTrue();
        assertThat(point.getFailureCount()).isZero();
        assertThat(candidates.findUnprobed(10)).isEmpty();
    }

    // ---------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------

    private LocationHarvester batchHarvester(InMemoryCandidateRepository candidates,
                                             ImageryProvider provider) {
        return new LocationHarvester(
                List.of(provider),
                candidates,
                new InMemoryLocationRepository(),
                new InMemoryCountryResolver("DE"),
                new RecordingAssetService(),
                FIXED);
    }

    /** A provider whose every probe throws, simulating a transient outage. */
    private static ImageryProvider alwaysFailing() {
        return failingWith(new RuntimeException("simulated transient failure"));
    }

    /** A provider whose every probe throws the given exception. */
    private static ImageryProvider failingWith(RuntimeException failure) {
        return new StubProvider(List.of()) {
            @Override
            public List<SourceImage> findNear(GeoPoint centre, double radiusMetres, int limit) {
                throw failure;
            }
        };
    }

    /** What the adapter throws for a 5xx / 429 / timeout. */
    private static ProviderException retryable() {
        return new ProviderException("simulated 503", true, null);
    }

    /** What the adapter throws for a 4xx other than 429. */
    private static ProviderException permanent() {
        return new ProviderException("simulated 400", false, null);
    }

    private LocationHarvester harvesterWith(ImageryProvider... providers) {
        return new LocationHarvester(
                List.of(providers),
                new InMemoryCandidateRepository(),
                new InMemoryLocationRepository(),
                new InMemoryCountryResolver("DE"),
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
