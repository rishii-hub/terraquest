package dev.terraquest.admin;

/**
 * Admin-triggered maintenance on the candidate seed grid.
 *
 * <p>A plain interface, matching the codebase's interface-plus-Impl convention,
 * so the controller's web/security slice test can stub it without a database.
 * The JPA-backed implementation is {@link PoolMaintenanceServiceImpl}.
 */
public interface PoolMaintenanceService {

    /**
     * Return every retry-exhausted candidate to the probe queue.
     *
     * @return how many candidates were reset
     */
    int resetExhaustedCandidates();
}
