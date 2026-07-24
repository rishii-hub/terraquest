package dev.terraquest.location.sampling;

import dev.terraquest.location.Location;
import dev.terraquest.location.LocationRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Default world sampler, correcting for coverage bias.
 *
 * <p><b>The problem this exists to solve.</b> Mapillary coverage is wildly uneven:
 * dense across Sweden, Germany, Japan and parts of the US, sparse to absent
 * elsewhere. Drawing uniformly at random from the location pool would produce a
 * game that is majority Northern Europe -- technically "global", experientially a
 * tour of Scandinavian B-roads. Players notice this within an hour and it is the
 * single most common complaint levelled at open-imagery geography games.
 *
 * <p><b>Approach.</b> Two-stage sampling. Pick a country first using weights that
 * are heavily flattened relative to raw pool share, then pick a location within
 * it. A country with 40,000 images and one with 400 end up far closer in
 * selection probability than their pool sizes suggest, while countries with too
 * few locations to be fair are excluded entirely rather than served repeatedly.
 *
 * <p>The flattening exponent is the tuning knob: 1.0 reproduces raw bias, 0.0
 * gives every country equal odds regardless of how thin its coverage is. We use
 * 0.35, which noticeably diversifies the rotation without surfacing the same
 * four Nairobi intersections every third game.
 */
@Component
public class CountryQuotaSampler implements LocationSamplingStrategy {

    private static final double FLATTENING_EXPONENT = 0.35;

    /**
     * Below this, a country's locations repeat often enough to be memorised.
     *
     * <p>Public so the harvest-stats endpoint can report how many countries clear
     * the floor without duplicating the number. This exposes the constant; it
     * does not change the sampler's behaviour.
     */
    public static final int MIN_LOCATIONS_PER_COUNTRY = 15;

    private final LocationRepository locations;

    public CountryQuotaSampler(LocationRepository locations) {
        this.locations = locations;
    }

    @Override
    public String id() {
        return "country-quota";
    }

    @Override
    public Optional<Location> sample(SampleRequest request) {
        // Seeded RNG: identical seeds must yield identical draws so daily
        // challenges and multiplayer rooms reproduce the same set on any node.
        Random rng = new Random(request.seed());

        Map<String, Integer> poolByCountry =
                locations.countActiveByCountry(request.minQuality(), request.panoramasOnly());

        List<String> eligible = poolByCountry.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_LOCATIONS_PER_COUNTRY)
                .map(Map.Entry::getKey)
                .filter(c -> !request.excludeCountries().contains(c))
                .filter(c -> request.restrictTo().isEmpty() || request.restrictTo().contains(c))
                .toList();

        if (eligible.isEmpty()) {
            // Regional modes with thin coverage can legitimately exhaust the
            // pool. Callers decide whether to relax constraints or end the game.
            return Optional.empty();
        }

        String country = weightedPick(eligible, poolByCountry, rng);
        return locations.sampleWithinCountry(
                country, request.minQuality(), request.excludeLocations(),
                request.panoramasOnly(), rng.nextLong());
    }

    /**
     * Pick a country with probability proportional to {@code poolSize ^ 0.35}.
     *
     * <p>Sub-linear weighting is the whole trick. Linear weighting hands the game
     * to whichever countries happen to have enthusiastic mappers; uniform
     * weighting oversamples countries with barely any coverage until players
     * recognise individual streets. The fractional exponent sits between the two.
     */
    private String weightedPick(List<String> countries, Map<String, Integer> pool, Random rng) {
        double[] weights = new double[countries.size()];
        double total = 0;

        for (int i = 0; i < countries.size(); i++) {
            weights[i] = Math.pow(pool.get(countries.get(i)), FLATTENING_EXPONENT);
            total += weights[i];
        }

        double target = rng.nextDouble() * total;
        double cumulative = 0;

        for (int i = 0; i < countries.size(); i++) {
            cumulative += weights[i];
            if (target <= cumulative) {
                return countries.get(i);
            }
        }
        // Unreachable barring floating-point drift at the final boundary.
        return countries.get(countries.size() - 1);
    }
}
