package dev.terraquest.admin;

import dev.terraquest.location.CandidatePointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delegates pool maintenance to the candidate repository inside a write
 * transaction. Thin by design: the recovery policy (which points, to what state)
 * lives with the queue query in {@code CandidatePointRepository}, next to the
 * partial-index predicate it must stay consistent with.
 */
@Service
public class PoolMaintenanceServiceImpl implements PoolMaintenanceService {

    private final CandidatePointRepository candidates;

    public PoolMaintenanceServiceImpl(CandidatePointRepository candidates) {
        this.candidates = candidates;
    }

    @Override
    @Transactional
    public int resetExhaustedCandidates() {
        return candidates.resetExhaustedRetries();
    }
}
