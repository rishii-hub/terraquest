package dev.terraquest.admin;

import java.util.List;

/**
 * A snapshot of pool health, returned by {@code GET /api/v1/admin/harvest-stats}.
 *
 * <p>This replaces the throwaway coverage spike permanently. It is the input for
 * tuning {@code FLATTENING_EXPONENT} and {@code MIN_LOCATIONS_PER_COUNTRY}, which
 * are currently guesses -- but those constants are not touched here; this PR
 * gathers the data first.
 *
 * @param pools                   per-country playable pool, split panoramic vs flat
 * @param panoFloor               the sampler's MIN_LOCATIONS_PER_COUNTRY
 * @param countriesAbovePanoFloor countries with at least {@code panoFloor} panoramas
 * @param candidates              probe-queue accounting
 * @param assets                  ingestion accounting
 */
public record HarvestStats(
        List<CountryPool> pools,
        int panoFloor,
        long countriesAbovePanoFloor,
        CandidateStats candidates,
        AssetStats assets) {

    /** Playable (active, asset-ready) locations for one country. */
    public record CountryPool(String countryCode, long panoramic, long flat) {
        public long total() {
            return panoramic + flat;
        }
    }

    /**
     * @param total          all candidate points
     * @param probed         successfully probed (a real verdict, imagery or not)
     * @param unprobed       still queued and retriable
     * @param retryExhausted given up on after repeated transient failures
     */
    public record CandidateStats(long total, long probed, long unprobed, long retryExhausted) {}

    /**
     * @param ingested      assets cached and EXIF-stripped
     * @param awaitingIngest locations with no ready asset and no attempt yet
     * @param failed        locations whose ingest was attempted and failed
     */
    public record AssetStats(long ingested, long awaitingIngest, long failed) {}
}
