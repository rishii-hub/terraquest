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

    /**
     * A globally-representative sample of points not yet probed, up to {@code
     * limit}. Sampled at random, not by insertion order: the seed grid is loaded
     * country-by-country, so an id-ordered queue makes any harvest short of the
     * full sweep an alphabetically-truncated pool (a partial run once yielded
     * only AM and AO). Random ordering makes every prefix an unbiased sample of
     * the whole grid.
     */
    List<CandidatePoint> findUnprobed(int limit);

    /**
     * Return every retry-exhausted but still-unprobed candidate to the queue by
     * zeroing its failure count, and report how many were reset. Recovers points
     * killed by a systematic outage so a transient failure cannot permanently
     * shrink the seed grid. Points already probed are left untouched.
     */
    int resetExhaustedRetries();

    void saveAll(List<CandidatePoint> points);
}
