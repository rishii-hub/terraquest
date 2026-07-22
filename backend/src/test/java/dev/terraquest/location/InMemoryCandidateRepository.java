package dev.terraquest.location;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link CandidatePointRepository} for {@link LocationHarvesterTest}.
 * Same package as the production type, so it can be a straightforward stand-in
 * without a database.
 */
class InMemoryCandidateRepository implements CandidatePointRepository {

    final List<CandidatePoint> saved = new ArrayList<>();

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    @Override
    public List<CandidatePoint> findUnprobed(int limit) {
        return saved.stream()
                .filter(c -> !c.isProbed())
                .filter(c -> !c.hasExhaustedRetries(MAX_CONSECUTIVE_FAILURES))
                .limit(limit)
                .toList();
    }

    @Override
    public void saveAll(List<CandidatePoint> points) {
        // Idempotent by identity, mirroring persist-or-merge: re-saving a point
        // already tracked (the harvester saves the batch it drained) updates it
        // in place rather than duplicating the row.
        for (CandidatePoint point : points) {
            if (!saved.contains(point)) {
                saved.add(point);
            }
        }
    }
}
