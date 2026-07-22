package dev.terraquest.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only read model over pool health.
 *
 * <p>Mapped under {@code /api/v1/admin}, which {@code SecurityConfig} guards with
 * HTTP Basic against a single admin credential. It exposes operational numbers
 * (pool size, probe queue, ingest state), never gameplay data, so it is safe to
 * read repeatedly while tuning the harvester and, later, the sampler.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class HarvestStatsController {

    private final HarvestStatsService stats;

    public HarvestStatsController(HarvestStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/harvest-stats")
    public HarvestStats harvestStats() {
        return stats.compute();
    }
}
