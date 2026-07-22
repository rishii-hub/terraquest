package dev.terraquest.location;

import java.util.List;

/**
 * Drain and stamp the probe queue.
 *
 * <p>A plain interface, not a Spring Data repository, so the harvester's unit
 * tests can substitute an in-memory fake without a database. The JPA-backed
 * implementation lives in {@link CandidatePointRepositoryImpl}.
 */
public interface CandidatePointRepository {

    /** Oldest-first batch of points not yet probed. */
    List<CandidatePoint> findUnprobed(int limit);

    void saveAll(List<CandidatePoint> points);
}
