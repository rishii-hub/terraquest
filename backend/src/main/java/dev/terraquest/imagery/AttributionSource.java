package dev.terraquest.imagery;

import dev.terraquest.shared.Attribution;

/**
 * The attribution seam.
 *
 * <p>{@code GameService} calls {@code round.getAsset().attributionFor(location)}.
 * The asset carries no attribution data of its own -- the contributor, the
 * source image reference and the licence all belong to the location's imagery
 * metadata. Rather than have {@link ImageAsset} (in {@code imagery}) reach into
 * {@code Location} (in {@code location}) -- which would invert the module
 * dependency and create a cycle -- the asset accepts anything that can describe
 * its own source attribution. {@code Location} implements this interface.
 *
 * <p>This also keeps provider-specific URL shapes out of the asset: how a
 * contributor id becomes a profile link is the source's concern, decided where
 * the (currently Mapillary-shaped) identifiers actually live.
 */
public interface AttributionSource {

    Attribution sourceAttribution();
}
