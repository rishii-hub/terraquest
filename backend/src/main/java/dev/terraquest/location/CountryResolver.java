package dev.terraquest.location;

import dev.terraquest.shared.GeoPoint;

import java.util.Optional;

/**
 * Resolves a point to the ISO 3166-1 alpha-2 country that contains it.
 *
 * <p>We do this ourselves against Natural Earth polygons rather than trusting a
 * seed's declared country code, which is unreliable near borders. The country a
 * point resolves to drives Country Streak scoring and Passport stamps, so it has
 * to be the country the point actually falls in.
 *
 * <p>A plain interface, not a Spring Data repository, so the harvester's unit
 * tests can substitute an in-memory fake without a database. The PostGIS-backed
 * implementation is {@link CountryResolverImpl}.
 */
public interface CountryResolver {

    /**
     * The country whose boundary polygon covers this point, or empty if the
     * point falls in no polygon (offshore, disputed). An empty result is a real
     * answer: such a location is excluded from play rather than guessed at.
     */
    Optional<String> resolve(GeoPoint point);
}
