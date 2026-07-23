package dev.terraquest.admin;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only maintenance actions on the candidate seed grid.
 *
 * <p>Mapped under {@code /api/v1/admin}, which {@code SecurityConfig} guards with
 * HTTP Basic (and, for that chain, disabled CSRF, so a Basic-authenticated POST
 * needs no token). Kept separate from the read-only {@code HarvestStatsController}
 * so a mutating action and a stats read do not share one class.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class PoolMaintenanceController {

    private final PoolMaintenanceService maintenance;

    public PoolMaintenanceController(PoolMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    /**
     * Requeue every retry-exhausted candidate. Idempotent: a second call once the
     * points are back in the queue resets nothing.
     */
    @PostMapping("/candidates/reset-exhausted")
    public ResetResult resetExhausted() {
        return new ResetResult(maintenance.resetExhaustedCandidates());
    }

    /** @param reset how many candidates were returned to the probe queue */
    public record ResetResult(int reset) {}
}
