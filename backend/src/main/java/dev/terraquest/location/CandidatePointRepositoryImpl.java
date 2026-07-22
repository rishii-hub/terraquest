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
        // Matches idx_candidate_unprobed
        // (partial index WHERE probed_at IS NULL AND failure_count < 3).
        return em.createQuery(
                        "select c from CandidatePoint c"
                                + " where c.probedAt is null and c.failureCount < :maxFailures"
                                + " order by c.id",
                        CandidatePoint.class)
                .setParameter("maxFailures", MAX_CONSECUTIVE_FAILURES)
                .setMaxResults(limit)
                .getResultList();
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
