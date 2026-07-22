package dev.terraquest.location;

import dev.terraquest.location.sampling.LocationSamplingStrategy;
import dev.terraquest.location.sampling.LocationSamplingStrategy.SampleRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Bridges the game engine to the pluggable {@link LocationSamplingStrategy}.
 *
 * <p>{@code GameService} asks for "the location for game G, round N"; this turns
 * that into a deterministic seed and delegates the coverage-bias correction to
 * the configured strategy. Deriving the seed from the game id and round index
 * (rather than a random) means re-issuing a round -- a page refresh -- yields
 * the same location instead of rerolling a hard one.
 *
 * <p>Lives in {@code location} (not {@code game}) so the game module depends only
 * on this seam, never on a concrete sampling strategy.
 */
@Component
public class LocationSampler {

    private final LocationSamplingStrategy strategy;

    public LocationSampler(LocationSamplingStrategy strategy) {
        this.strategy = strategy;
    }

    public Location sampleForGame(UUID gameId, int roundIndex) {
        long seed = gameId.getMostSignificantBits() * 31
                + gameId.getLeastSignificantBits()
                + roundIndex;
        return strategy.sample(SampleRequest.world(seed))
                .orElseThrow(() -> new NoLocationsAvailableException(
                        "No playable location for game %s round %d".formatted(gameId, roundIndex)));
    }
}
