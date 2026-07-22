package dev.terraquest.location;

import dev.terraquest.shared.GeoPoint;

import java.util.Optional;

/**
 * In-memory {@link CountryResolver} for {@link LocationHarvesterTest}. Resolves
 * every point to a fixed country by default; construct with {@code null} to
 * simulate a point that falls in no polygon (offshore, disputed).
 */
class InMemoryCountryResolver implements CountryResolver {

    private final String countryCode;

    InMemoryCountryResolver(String countryCode) {
        this.countryCode = countryCode;
    }

    @Override
    public Optional<String> resolve(GeoPoint point) {
        return Optional.ofNullable(countryCode);
    }
}
