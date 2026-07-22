package dev.terraquest.location;

import dev.terraquest.imagery.ImageryProvider.SourceImage;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The playable pool.
 *
 * <p>A plain interface, not a Spring Data repository: the harvester depends on
 * {@link #upsertFromSource} and {@link #markAssetReady}, and its unit tests
 * substitute an in-memory fake. The JPA-backed implementation is
 * {@link LocationRepositoryImpl}; the sampler ({@code CountryQuotaSampler})
 * reads through {@link #countActiveByCountry} and {@link #sampleWithinCountry}.
 */
public interface LocationRepository {

    /**
     * Count of playable locations per country code, filtered by quality. Only
     * active, asset-ready rows count -- those are the ones a round can serve.
     */
    Map<String, Integer> countActiveByCountry(float minQuality);

    /**
     * Draw one location from a country, deterministically.
     *
     * <p>Deterministic given the seed so daily challenges and multiplayer rooms
     * reproduce an identical draw on every node. Respects {@code is_active} and
     * {@code asset_ready}, and excludes locations already used in this game.
     */
    Optional<Location> sampleWithinCountry(String country, float minQuality, Set<UUID> exclude, long seed);

    /**
     * Insert or update a location from a probed source image. Idempotent on the
     * provider's external id (the {@code mapillary_image_id} unique key), so
     * re-probing a point never duplicates the pool.
     */
    Location upsertFromSource(SourceImage image, String countryCode, float quality);

    /** Flip a location playable once its imagery is cached and cleaned. */
    void markAssetReady(UUID locationId);

    /**
     * Record that an asset ingest attempt failed for this location. The location
     * row remains with {@code asset_ready = false}, invisible to the sampler; the
     * counter separates a genuine ingest failure from a location not yet attempted
     * for the harvest-stats endpoint.
     */
    void recordIngestFailure(UUID locationId);
}
