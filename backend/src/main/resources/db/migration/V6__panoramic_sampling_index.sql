-- Panorama-first Classic Mode.
--
-- Classic Mode now draws only panoramic locations (see CountryQuotaSampler /
-- SampleRequest.panoramasOnly). The existing idx_location_sampling covers
-- (quality_score) WHERE is_active AND asset_ready, but the panoramic draw adds
-- `is_panoramic` to the predicate and filters by country. This partial index
-- keeps sampleWithinCountry's per-country panoramic lookup and the
-- countActiveByCountry aggregate cheap without slowing the full-pool modes,
-- which continue to use idx_location_sampling.
CREATE INDEX idx_location_panoramic_sampling ON location (country_code)
    WHERE is_active AND asset_ready AND is_panoramic;
