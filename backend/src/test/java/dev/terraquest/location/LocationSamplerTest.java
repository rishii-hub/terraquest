package dev.terraquest.location;

import dev.terraquest.location.sampling.LocationSamplingStrategy;
import dev.terraquest.location.sampling.LocationSamplingStrategy.SampleRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-game exclusion + relaxation ladder in {@link LocationSampler}.
 *
 * <p>Drives the sampler with a recording strategy so each rung is observable:
 * the requests it emits reveal exactly which constraint was relaxed. The
 * invariant under test is that <i>only the country exclusion</i> is ever
 * dropped -- never the location exclusion, never the panorama filter.
 */
class LocationSamplerTest {

    private static final UUID GAME = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID USED_LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    @Test
    void rung_one_draws_a_distinct_country_when_the_pool_allows() {
        var strategy = new RecordingStrategy(req -> Optional.of(anyLocation()));
        var sampler = new LocationSampler(strategy);

        Location drawn = sampler.sampleForGame(
                GAME, 2, true, Set.of(USED_LOCATION), Set.of("DE"));

        assertThat(drawn).isNotNull();
        assertThat(strategy.requests).hasSize(1);
        SampleRequest first = strategy.requests.get(0);
        assertThat(first.excludeCountries()).as("prior country excluded").containsExactly("DE");
        assertThat(first.excludeLocations()).as("prior location excluded").containsExactly(USED_LOCATION);
        assertThat(first.panoramasOnly()).isTrue();
    }

    @Test
    void rung_two_relaxes_only_the_country_exclusion() {
        // No unused country can satisfy the draw; a repeated country can.
        var strategy = new RecordingStrategy(
                req -> req.excludeCountries().isEmpty() ? Optional.of(anyLocation()) : Optional.empty());
        var sampler = new LocationSampler(strategy);

        Location drawn = sampler.sampleForGame(
                GAME, 3, true, Set.of(USED_LOCATION), Set.of("DE"));

        assertThat(drawn).as("a repeated country still yields a fresh location").isNotNull();
        assertThat(strategy.requests).hasSize(2);

        SampleRequest fallback = strategy.requests.get(1);
        assertThat(fallback.excludeCountries()).as("country exclusion relaxed").isEmpty();
        assertThat(fallback.excludeLocations())
                .as("location exclusion is NOT relaxed on the fallback")
                .containsExactly(USED_LOCATION);
        assertThat(fallback.panoramasOnly())
                .as("the panorama filter is NOT relaxed on the fallback")
                .isTrue();
    }

    @Test
    void rung_three_throws_without_ever_relaxing_locations_or_the_panorama_filter() {
        var strategy = new RecordingStrategy(req -> Optional.empty());
        var sampler = new LocationSampler(strategy);

        assertThatThrownBy(() -> sampler.sampleForGame(
                GAME, 4, true, Set.of(USED_LOCATION), Set.of("DE")))
                .isInstanceOf(NoLocationsAvailableException.class);

        assertThat(strategy.requests)
                .as("every rung kept the panorama filter and the location exclusion")
                .allSatisfy(req -> {
                    assertThat(req.panoramasOnly()).isTrue();
                    assertThat(req.excludeLocations()).containsExactly(USED_LOCATION);
                });
    }

    @Test
    void location_exclusion_is_forwarded_even_when_panoramas_are_off() {
        // The same-location-twice fix is independent of the panorama filter:
        // non-Classic modes still exclude prior locations.
        var strategy = new RecordingStrategy(req -> Optional.of(anyLocation()));
        var sampler = new LocationSampler(strategy);

        sampler.sampleForGame(GAME, 1, false, Set.of(USED_LOCATION), Set.of());

        SampleRequest req = strategy.requests.get(0);
        assertThat(req.panoramasOnly()).isFalse();
        assertThat(req.excludeLocations())
                .as("prior locations are excluded regardless of mode")
                .containsExactly(USED_LOCATION);
    }

    private static Location anyLocation() {
        return Location.builder()
                .id(UUID.randomUUID())
                .countryCode("SE")
                .panoramic(true)
                .build();
    }

    /** Records every request and answers via the supplied responder. */
    private static final class RecordingStrategy implements LocationSamplingStrategy {
        final List<SampleRequest> requests = new ArrayList<>();
        private final Function<SampleRequest, Optional<Location>> responder;

        RecordingStrategy(Function<SampleRequest, Optional<Location>> responder) {
            this.responder = responder;
        }

        @Override
        public String id() {
            return "recording";
        }

        @Override
        public Optional<Location> sample(SampleRequest request) {
            requests.add(request);
            return responder.apply(request);
        }
    }
}
