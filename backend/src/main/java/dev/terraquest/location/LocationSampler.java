package dev.terraquest.location;

import dev.terraquest.location.sampling.LocationSamplingStrategy;
import dev.terraquest.location.sampling.LocationSamplingStrategy.SampleRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Draw the location for one round of a game.
     *
     * <p>The seed is derived from the game id and round index (not a random) so
     * re-issuing a round -- a page refresh -- yields the same location instead of
     * rerolling a hard one.
     *
     * <p><b>Exclusion and the relaxation ladder.</b> {@code excludeLocations} and
     * {@code excludeCountries} carry the locations and countries already used in
     * this game, so a five-round game is five distinct places. When the pool
     * cannot satisfy both at once, we relax <i>only the country exclusion</i>: a
     * repeated country is a far smaller problem than a repeated location or --
     * under {@code panoramasOnly} -- a flat round. The location exclusion and the
     * panorama filter are never relaxed; if distinct locations genuinely run out,
     * we throw rather than degrade the draw.
     *
     * @param panoramasOnly restrict to panoramic imagery (set for Classic Mode)
     */
    public Location sampleForGame(UUID gameId, int roundIndex, boolean panoramasOnly,
                                  Set<UUID> excludeLocations, Set<String> excludeCountries) {
        long seed = gameId.getMostSignificantBits() * 31
                + gameId.getLeastSignificantBits()
                + roundIndex;

        // Rung 1: a fresh location in a country not yet seen this game.
        Optional<Location> pick = strategy.sample(new SampleRequest(
                seed, excludeLocations, excludeCountries, List.of(), 0.4f, panoramasOnly));

        // Rung 2: the panoramic pool can be thin enough that no unused country
        // clears the floor. Allow a repeated country, still a fresh location.
        if (pick.isEmpty() && !excludeCountries.isEmpty()) {
            pick = strategy.sample(new SampleRequest(
                    seed, excludeLocations, Set.of(), List.of(), 0.4f, panoramasOnly));
        }

        // Rung 3: no distinct location left at all -- fail honestly rather than
        // repeat a location or fall back to flat imagery.
        return pick.orElseThrow(() -> new NoLocationsAvailableException(
                "No playable location for game %s round %d".formatted(gameId, roundIndex)));
    }
}
