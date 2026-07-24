package dev.terraquest.location.sampling;

import dev.terraquest.location.Location;
import dev.terraquest.location.LocationRepository;
import dev.terraquest.location.sampling.LocationSamplingStrategy.SampleRequest;
import dev.terraquest.imagery.ImageryProvider.SourceImage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sampler behaviour against an in-memory pool -- no database, no Spring.
 *
 * <p>These prove the panorama-first contract independently of SQL: a
 * panoramas-only draw never surfaces a flat location, the flag off still returns
 * the full pool, the country-quota weighting still applies within the panoramic
 * subset, and the draw is reproducible for a seed. The DB proof that the SQL
 * predicate matches lives in {@code LocationRepositoryIT}.
 */
class CountryQuotaSamplerTest {

    @Test
    void panoramas_only_never_returns_a_flat_location() {
        var repo = new FakePool()
                .add("DE", true, 20)
                .add("DE", false, 20)
                .add("FR", false, 30); // flat-only country
        var sampler = new CountryQuotaSampler(repo);

        for (long seed = 0; seed < 200; seed++) {
            Location drawn = sampler.sample(panoramasOnly(seed)).orElseThrow();
            assertThat(drawn.isPanoramic())
                    .as("panoramas-only draw returned a flat location for seed %d", seed)
                    .isTrue();
            assertThat(drawn.getCountryCode())
                    .as("a flat-only country must never be drawn under panoramas-only")
                    .isNotEqualTo("FR");
        }
    }

    @Test
    void flag_off_returns_the_full_pool_including_flat_locations() {
        var repo = new FakePool()
                .add("DE", true, 20)
                .add("DE", false, 20);
        var sampler = new CountryQuotaSampler(repo);

        boolean sawFlat = false;
        for (long seed = 0; seed < 200 && !sawFlat; seed++) {
            sawFlat = !sampler.sample(SampleRequest.world(seed)).orElseThrow().isPanoramic();
        }
        assertThat(sawFlat)
                .as("with the filter off, flat locations must remain drawable (no regression)")
                .isTrue();
    }

    @Test
    void country_quota_weighting_still_applies_within_the_panoramic_subset() {
        // Big and small both clear MIN_LOCATIONS_PER_COUNTRY on panoramas alone;
        // tiny does not. Flat padding on the small country must not lift it.
        var repo = new FakePool()
                .add("BG", true, 400)  // big panoramic pool
                .add("SM", true, 16)   // small panoramic pool, just clears the floor
                .add("SM", false, 400) // flat padding -- irrelevant under panoramas-only
                .add("TY", true, 5);   // below the floor
        var sampler = new CountryQuotaSampler(repo);

        Map<String, Integer> hits = new LinkedHashMap<>();
        for (long seed = 0; seed < 3000; seed++) {
            String c = sampler.sample(panoramasOnly(seed)).orElseThrow().getCountryCode();
            hits.merge(c, 1, Integer::sum);
        }

        assertThat(hits.getOrDefault("TY", 0))
                .as("a country below the panoramic floor is never eligible")
                .isZero();
        assertThat(hits.getOrDefault("BG", 0))
                .as("the larger panoramic pool is weighted more heavily")
                .isGreaterThan(hits.getOrDefault("SM", 0));
    }

    @Test
    void same_seed_same_request_yields_the_same_draw_with_the_filter_on() {
        var repo = new FakePool()
                .add("DE", true, 20)
                .add("SE", true, 20)
                .add("JP", true, 20);
        var sampler = new CountryQuotaSampler(repo);

        for (long seed = 0; seed < 50; seed++) {
            Location first = sampler.sample(panoramasOnly(seed)).orElseThrow();
            Location second = sampler.sample(panoramasOnly(seed)).orElseThrow();
            assertThat(second.getId())
                    .as("determinism must hold for seed %d with panoramas-only on", seed)
                    .isEqualTo(first.getId());
        }
    }

    private static SampleRequest panoramasOnly(long seed) {
        return new SampleRequest(seed, Set.of(), Set.of(), List.of(), 0.4f, true);
    }

    /**
     * In-memory pool honouring the two sampler reads, including the
     * {@code panoramasOnly} and quality filters. A pure function of its inputs so
     * a given seed reproduces a given draw, mirroring the production
     * {@code ORDER BY md5(id || seed)} without needing SQL.
     */
    private static final class FakePool implements LocationRepository {

        private record Entry(Location location, boolean panoramic, float quality) {}

        private final List<Entry> entries = new ArrayList<>();

        FakePool add(String country, boolean panoramic, int count) {
            for (int i = 0; i < count; i++) {
                Location loc = Location.builder()
                        .id(UUID.randomUUID())
                        .countryCode(country)
                        .panoramic(panoramic)
                        .build();
                entries.add(new Entry(loc, panoramic, 0.9f));
            }
            return this;
        }

        @Override
        public Map<String, Integer> countActiveByCountry(float minQuality, boolean panoramasOnly) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Entry e : entries) {
                if (e.quality() < minQuality) {
                    continue;
                }
                if (panoramasOnly && !e.panoramic()) {
                    continue;
                }
                counts.merge(e.location().getCountryCode(), 1, Integer::sum);
            }
            return counts;
        }

        @Override
        public Optional<Location> sampleWithinCountry(String country, float minQuality,
                                                      Set<UUID> exclude, boolean panoramasOnly, long seed) {
            return entries.stream()
                    .filter(e -> e.quality() >= minQuality)
                    .filter(e -> !panoramasOnly || e.panoramic())
                    .map(Entry::location)
                    .filter(l -> l.getCountryCode().equals(country))
                    .filter(l -> !exclude.contains(l.getId()))
                    .min(Comparator.comparing(l -> order(l.getId(), seed)))
                    .stream()
                    .findFirst();
        }

        // Deterministic surrogate for the SQL md5(id || seed) ordering: any pure
        // function of (id, seed) makes the in-country draw reproducible.
        private static String order(UUID id, long seed) {
            return Integer.toHexString((id.toString() + ':' + seed).hashCode());
        }

        @Override
        public Location upsertFromSource(SourceImage image, String countryCode, float quality) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markAssetReady(UUID locationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordIngestFailure(UUID locationId) {
            throw new UnsupportedOperationException();
        }
    }
}
