package dev.terraquest.game;

import dev.terraquest.imagery.Projection;

import java.util.UUID;

/**
 * What the client receives for a round.
 *
 * <p>Note the omissions: no coordinates, no country, no Mapillary identifier, no
 * contributor name. {@code initialHeading} is the compass bearing the viewer
 * should face on load -- it reveals orientation, not position. The full truth
 * arrives only in {@link GuessResult}, after the answer is locked in.
 *
 * <p>A top-level type rather than nested in the controller: {@code GameService}
 * constructs it and the controller returns it, so it is shared package API, not
 * one class's private shape.
 */
public record RoundView(
        UUID roundId,
        int index,
        String imageUrl,
        Projection projection,
        int width,
        int height,
        Float initialHeading,
        Integer timeLimitSeconds
) {
}
