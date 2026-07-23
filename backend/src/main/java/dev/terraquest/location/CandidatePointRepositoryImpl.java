package dev.terraquest.location;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JPA-backed {@link CandidatePointRepository}.
 */
@Repository
class CandidatePointRepositoryImpl implements CandidatePointRepository {

    @PersistenceContext
    private EntityManager em;

    // Mirrors the literal in idx_candidate_unprobed's partial predicate and
    // LocationHarvester.MAX_CONSECUTIVE_FAILURES; a point that has used up its
    // retries is not "unprobed" and must not be handed back.
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    @Override
    public List<CandidatePoint> findUnprobed(int limit) {
        // Predicate matches idx_candidate_unprobed (partial index WHERE probed_at
        // IS NULL AND failure_count < 3). Ordered at random rather than by id:
        // the grid is seeded country-by-country, so id order truncates a partial
        // harvest alphabetically. random() makes any prefix a representative
        // sample. The partial index still serves the predicate; only the sort of
        // the (few thousand) matching rows is unindexed, which is negligible at
        // once-a-minute batch cadence.
        return em.createQuery(
                        "select c from CandidatePoint c"
                                + " where c.probedAt is null and c.failureCount < :maxFailures"
                                + " order by function('random')",
                        CandidatePoint.class)
                .setParameter("maxFailures", MAX_CONSECUTIVE_FAILURES)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public int resetExhaustedRetries() {
        // Bulk update, no entity load: this can touch many rows and the caller
        // is an admin action, not the harvest loop. Scoped to unprobed points so
        // a genuinely probed candidate is never silently re-queued.
        return em.createQuery(
                        "update CandidatePoint c set c.failureCount = 0"
                                + " where c.probedAt is null and c.failureCount >= :maxFailures")
                .setParameter("maxFailures", MAX_CONSECUTIVE_FAILURES)
                .executeUpdate();
    }

    @Override
    public void saveAll(List<CandidatePoint> points) {
        for (CandidatePoint point : points) {
            if (point.getId() == null) {
                em.persist(point);
            } else {
                em.merge(point);
            }
        }
    }
}
