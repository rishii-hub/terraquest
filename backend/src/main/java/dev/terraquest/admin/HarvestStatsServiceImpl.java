package dev.terraquest.admin;

import dev.terraquest.admin.HarvestStats.AssetStats;
import dev.terraquest.admin.HarvestStats.CandidateStats;
import dev.terraquest.admin.HarvestStats.CountryPool;
import dev.terraquest.location.sampling.CountryQuotaSampler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes {@link HarvestStats} from the live database with native aggregates.
 *
 * <p>Every read here is a plain count over an indexed predicate, cheap enough to
 * compute on demand rather than materialising a summary table. All queries are
 * covered by a Testcontainers test, since a mocked database would prove nothing
 * about the SQL.
 */
@Service
public class HarvestStatsServiceImpl implements HarvestStatsService {

    private static final int PANO_FLOOR = CountryQuotaSampler.MIN_LOCATIONS_PER_COUNTRY;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public HarvestStats compute() {
        List<CountryPool> pools = poolByCountry();
        long aboveFloor = pools.stream().filter(p -> p.panoramic() >= PANO_FLOOR).count();
        return new HarvestStats(pools, PANO_FLOOR, aboveFloor, candidateStats(), assetStats());
    }

    @SuppressWarnings("unchecked")
    private List<CountryPool> poolByCountry() {
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT country_code,
                               count(*) FILTER (WHERE is_panoramic)     AS panoramic,
                               count(*) FILTER (WHERE NOT is_panoramic)  AS flat
                        FROM location
                        WHERE is_active AND asset_ready
                        GROUP BY country_code
                        ORDER BY country_code
                        """)
                .getResultList();

        List<CountryPool> pools = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            pools.add(new CountryPool(
                    (String) row[0], asLong(row[1]), asLong(row[2])));
        }
        return pools;
    }

    private CandidateStats candidateStats() {
        Object[] row = (Object[]) em.createNativeQuery("""
                        SELECT count(*),
                               count(*) FILTER (WHERE probed_at IS NOT NULL),
                               count(*) FILTER (WHERE probed_at IS NULL AND failure_count < 3),
                               count(*) FILTER (WHERE probed_at IS NULL AND failure_count >= 3)
                        FROM candidate_point
                        """)
                .getSingleResult();
        return new CandidateStats(asLong(row[0]), asLong(row[1]), asLong(row[2]), asLong(row[3]));
    }

    private AssetStats assetStats() {
        long ingested = asLong(em.createNativeQuery(
                        "SELECT count(*) FROM image_asset WHERE exif_stripped")
                .getSingleResult());

        Object[] pending = (Object[]) em.createNativeQuery("""
                        SELECT count(*) FILTER (WHERE ingest_attempts = 0),
                               count(*) FILTER (WHERE ingest_attempts > 0)
                        FROM location
                        WHERE NOT asset_ready
                        """)
                .getSingleResult();
        return new AssetStats(ingested, asLong(pending[0]), asLong(pending[1]));
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }
}
