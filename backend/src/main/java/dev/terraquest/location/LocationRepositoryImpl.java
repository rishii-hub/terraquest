package dev.terraquest.location;

import dev.terraquest.imagery.ImageryProvider.SourceImage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JPA-backed {@link LocationRepository}. The reads that shape gameplay --
 * per-country counts and the in-country draw -- are native so they can lean on
 * PostGIS and on deterministic ordering that JPQL cannot express.
 */
@Repository
class LocationRepositoryImpl implements LocationRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Integer> countActiveByCountry(float minQuality, boolean panoramasOnly) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT country_code, count(*)::int AS n"
                                + " FROM location"
                                + " WHERE is_active AND asset_ready AND quality_score >= :minQuality"
                                + (panoramasOnly ? " AND is_panoramic" : "")
                                + " GROUP BY country_code")
                .setParameter("minQuality", minQuality)
                .getResultList();

        Map<String, Integer> byCountry = new LinkedHashMap<>();
        for (Object[] row : rows) {
            byCountry.put((String) row[0], ((Number) row[1]).intValue());
        }
        return byCountry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Location> sampleWithinCountry(String country, float minQuality, Set<UUID> exclude,
                                                  boolean panoramasOnly, long seed) {
        boolean hasExclusions = exclude != null && !exclude.isEmpty();

        // Deterministic ordering: hash the row id against the seed. Same seed ->
        // same hash -> same top row on any node. ORDER BY random() would not be
        // reproducible and would break daily challenges and multiplayer rooms.
        String sql = "SELECT * FROM location"
                + " WHERE country_code = :country"
                + "   AND is_active AND asset_ready"
                + "   AND quality_score >= :minQuality"
                + (panoramasOnly ? " AND is_panoramic" : "")
                + (hasExclusions ? " AND id NOT IN (:exclude)" : "")
                + " ORDER BY md5(id::text || CAST(:seed AS text))"
                + " LIMIT 1";

        Query query = em.createNativeQuery(sql, Location.class)
                .setParameter("country", country)
                .setParameter("minQuality", minQuality)
                .setParameter("seed", seed);
        if (hasExclusions) {
            query.setParameter("exclude", exclude);
        }

        List<Location> result = query.getResultList();
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    @Override
    @Transactional
    public Location upsertFromSource(SourceImage image, String countryCode, float quality) {
        // Idempotent on mapillary_image_id (its unique key). Casts on nullable
        // columns give Postgres a type for null-valued binds.
        UUID id = (UUID) em.createNativeQuery("""
                        INSERT INTO location (
                            mapillary_image_id, sequence_id, position, country_code,
                            compass_angle, is_panoramic, captured_at,
                            creator_username, creator_id, quality_score)
                        VALUES (
                            :mapillaryId,
                            CAST(:sequenceId AS text),
                            ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                            :countryCode,
                            CAST(:compassAngle AS real),
                            :panoramic,
                            CAST(:capturedAt AS timestamptz),
                            CAST(:creatorUsername AS text),
                            CAST(:creatorId AS text),
                            :quality)
                        ON CONFLICT (mapillary_image_id) DO UPDATE SET
                            sequence_id = EXCLUDED.sequence_id,
                            position = EXCLUDED.position,
                            country_code = EXCLUDED.country_code,
                            compass_angle = EXCLUDED.compass_angle,
                            is_panoramic = EXCLUDED.is_panoramic,
                            captured_at = EXCLUDED.captured_at,
                            creator_username = EXCLUDED.creator_username,
                            creator_id = EXCLUDED.creator_id,
                            quality_score = EXCLUDED.quality_score
                        RETURNING id
                        """)
                .setParameter("mapillaryId", image.externalId())
                .setParameter("sequenceId", image.sequenceId())
                .setParameter("lon", image.position().lon())
                .setParameter("lat", image.position().lat())
                .setParameter("countryCode", countryCode)
                .setParameter("compassAngle", image.compassAngle())
                .setParameter("panoramic", image.panoramic())
                .setParameter("capturedAt", image.capturedAt())
                .setParameter("creatorUsername", image.contributorName())
                .setParameter("creatorId", image.contributorId())
                .setParameter("quality", quality)
                .getSingleResult();

        // The row was written outside the persistence context; load it back so
        // callers get a managed entity (they read getId()).
        return em.find(Location.class, id);
    }

    @Override
    @Transactional
    public void markAssetReady(UUID locationId) {
        em.createNativeQuery("UPDATE location SET asset_ready = true WHERE id = :id")
                .setParameter("id", locationId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void recordIngestFailure(UUID locationId) {
        em.createNativeQuery(
                        "UPDATE location SET ingest_attempts = ingest_attempts + 1 WHERE id = :id")
                .setParameter("id", locationId)
                .executeUpdate();
    }
}
