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

    @Override
    public List<CandidatePoint> findUnprobed(int limit) {
        return saved.stream()
                .filter(c -> !c.isProbed())
                .limit(limit)
                .toList();
    }

    @Override
    public void saveAll(List<CandidatePoint> points) {
        saved.addAll(points);
    }
}
