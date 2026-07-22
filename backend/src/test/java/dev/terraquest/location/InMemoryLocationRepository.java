package dev.terraquest.location;

import dev.terraquest.imagery.ImageryProvider.SourceImage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link LocationRepository} for {@link LocationHarvesterTest}.
 *
 * <p>Only the harvester's write path ({@link #upsertFromSource},
 * {@link #markAssetReady}) needs real behaviour; the sampler reads return empty.
 * {@code upsertFromSource} is idempotent on the external id, mirroring the
 * production unique-key upsert.
 */
class InMemoryLocationRepository implements LocationRepository {

    final Map<String, Location> byExternalId = new HashMap<>();
    final Set<UUID> assetReady = new HashSet<>();

    @Override
    public Map<String, Integer> countActiveByCountry(float minQuality) {
        return Map.of();
    }

    @Override
    public Optional<Location> sampleWithinCountry(String country, float minQuality, Set<UUID> exclude, long seed) {
        return Optional.empty();
    }

    @Override
    public Location upsertFromSource(SourceImage image, String countryCode, float quality) {
        return byExternalId.computeIfAbsent(
                image.externalId(),
                key -> new Location(UUID.randomUUID(), countryCode, false));
    }

    @Override
    public void markAssetReady(UUID locationId) {
        assetReady.add(locationId);
    }
}
