package dev.terraquest.game;

import dev.terraquest.shared.Attribution;

/**
 * The result screen payload: the score, the true location, and the CC-BY-SA
 * attribution -- surfaced here rather than at round issue time because a
 * contributor's upload history frequently gives away the country before the
 * player has guessed.
 */
public record GuessResult(
        int score,
        double distanceMetres,
        double actualLat,
        double actualLon,
        String countryCode,
        Attribution attribution,
        int runningTotal,
        boolean gameComplete
) {
}
