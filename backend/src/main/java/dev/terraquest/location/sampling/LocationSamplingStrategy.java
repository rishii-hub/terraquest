package dev.terraquest.location.sampling;

import dev.terraquest.location.Location;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * How a round chooses a location from the pool.
 *
 * <p>Separated from {@code CandidateSeedSource} deliberately. Seeding decides
 * <i>where we look</i> for imagery; sampling decides <i>what we serve</i>. They
 * change for unrelated reasons -- seeding changes when we add OSM road nodes,
 * sampling changes when we add difficulty tiers or regional modes -- so folding
 * them into one "location provider" interface would produce a type with two
 * independent reasons to change.
 *
 * <p>Implementations must be deterministic given the same seed, so daily
 * challenges and multiplayer rooms can reproduce an identical location set
 * across nodes without coordinating through the database.
 */
public interface LocationSamplingStrategy {

    String id();

    /**
     * @param request constraints for this draw
     * @return one location, or empty if the pool cannot satisfy the constraints
     */
    java.util.Optional<Location> sample(SampleRequest request);

    /**
     * @param seed             deterministic draw seed; same seed yields same result
     * @param excludeLocations already used in this game
     * @param excludeCountries already used in this game
     * @param restrictTo       optional country allow-list for regional modes
     * @param minQuality       floor on the harvester's quality heuristic
     */
    record SampleRequest(
            long seed,
            Set<UUID> excludeLocations,
            Set<String> excludeCountries,
            List<String> restrictTo,
            float minQuality
    ) {
        public static SampleRequest world(long seed) {
            return new SampleRequest(seed, Set.of(), Set.of(), List.of(), 0.4f);
        }
    }
}
