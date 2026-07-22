package dev.terraquest.scoring;

import org.springframework.stereotype.Service;

/**
 * Distance-to-score conversion.
 *
 * Uses exponential decay rather than linear falloff. Linear scoring feels wrong:
 * the difference between 10 km and 60 km should matter far more than the
 * difference between 3000 km and 3050 km, and only decay gives you that.
 *
 *     score = MAX * exp(-DECAY * distance / mapDiagonal)
 *
 * DECAY = 10 is tuned so a guess within ~1 km of a world-map target scores
 * roughly 4970/5000, and a guess 2000 km out scores roughly 260.
 */
@Service
public class ScoringService {

    public static final int MAX_ROUND_SCORE = 5000;

    private static final double EARTH_RADIUS_M = 6_371_000.0;
    private static final double DECAY = 10.0;

    /** Diagonal of a world map, in metres. Sub-maps would pass their own. */
    public static final double WORLD_DIAGONAL_M = 14_916_862.0;

    /** Anything at or inside this radius counts as a perfect guess. */
    private static final double PERFECT_RADIUS_M = 25.0;

    public RoundResult evaluate(double actualLat, double actualLon,
                                double guessLat, double guessLon,
                                double mapDiagonalM) {
        double distance = haversineMetres(actualLat, actualLon, guessLat, guessLon);

        int score;
        if (distance <= PERFECT_RADIUS_M) {
            score = MAX_ROUND_SCORE;
        } else {
            double raw = MAX_ROUND_SCORE * Math.exp(-DECAY * distance / mapDiagonalM);
            score = (int) Math.round(raw);
        }
        return new RoundResult(distance, score, score == MAX_ROUND_SCORE);
    }

    public RoundResult evaluateWorld(double actualLat, double actualLon,
                                     double guessLat, double guessLon) {
        return evaluate(actualLat, actualLon, guessLat, guessLon, WORLD_DIAGONAL_M);
    }

    /**
     * Great-circle distance in metres.
     *
     * Note: PostGIS ST_Distance on geography would be more accurate (spheroidal),
     * but this runs per-guess in memory and the error is under 0.5%, which is far
     * below the granularity anyone perceives in a score.
     */
    public static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public record RoundResult(double distanceMetres, int score, boolean perfect) {}
}
