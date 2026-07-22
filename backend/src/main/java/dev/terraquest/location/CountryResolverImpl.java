package dev.terraquest.location;

import dev.terraquest.shared.GeoPoint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * PostGIS-backed {@link CountryResolver}.
 *
 * <p>{@code ST_Covers} rather than {@code ST_Contains}: a point exactly on a
 * boundary must resolve to its country, and {@code ST_Contains} excludes boundary
 * points. The geography point is cast to geometry so the query rides the GiST
 * index on {@code country_boundary.geom}; both sides are SRID 4326.
 */
@Repository
class CountryResolverImpl implements CountryResolver {

    @PersistenceContext
    private EntityManager em;

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> resolve(GeoPoint point) {
        List<String> hits = em.createNativeQuery("""
                        SELECT iso2 FROM country_boundary
                        WHERE ST_Covers(
                            geom,
                            ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))
                        LIMIT 1
                        """)
                .setParameter("lon", point.lon())
                .setParameter("lat", point.lat())
                .getResultList();

        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }
}
