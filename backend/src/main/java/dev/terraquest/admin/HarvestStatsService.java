package dev.terraquest.admin;

/**
 * Computes a {@link HarvestStats} snapshot of pool health on demand.
 *
 * <p>A plain interface, matching the codebase's interface-plus-Impl convention,
 * so the controller's web/security slice test can stub it without a database.
 * The PostGIS-backed implementation is {@link HarvestStatsServiceImpl}.
 */
public interface HarvestStatsService {

    HarvestStats compute();
}
