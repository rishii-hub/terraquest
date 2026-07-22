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

    @Override
    public List<CandidatePoint> findUnprobed(int limit) {
        // Matches idx_candidate_unprobed (partial index WHERE probed_at IS NULL).
        return em.createQuery(
                        "select c from CandidatePoint c where c.probedAt is null order by c.id",
                        CandidatePoint.class)
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
