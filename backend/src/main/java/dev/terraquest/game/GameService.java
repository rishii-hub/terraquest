package dev.terraquest.game;

import dev.terraquest.imagery.ImageAsset;
import dev.terraquest.imagery.ImageAssetService;
import dev.terraquest.location.Location;
import dev.terraquest.location.LocationSampler;
import dev.terraquest.location.NoLocationsAvailableException;
import dev.terraquest.scoring.ScoringService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates a single-player game.
 *
 * <p>Three invariants hold the anti-cheat design together, and all three live
 * here rather than in the controller so no future endpoint can bypass them:
 *
 * <ol>
 *   <li><b>Lazy issuance.</b> Round <i>n+1</i> is materialised only once round
 *       <i>n</i> has been answered. Handing the client all five rounds up front
 *       would hand it the whole game.</li>
 *   <li><b>Server-authoritative timing.</b> Elapsed time is derived from
 *       {@code issued_at}, stamped here. Any duration in the request body is
 *       discarded.</li>
 *   <li><b>Single-shot guesses.</b> The guess write is a conditional update
 *       guarded on {@code guessed_at IS NULL}, so concurrent submissions cannot
 *       be replayed for a better score.</li>
 * </ol>
 */
@Service
public class GameService {

    private static final int DEFAULT_ROUNDS = 5;

    private final GameRepository games;
    private final RoundRepository rounds;
    private final LocationSampler sampler;
    private final ImageAssetService imagery;
    private final ScoringService scoring;

    public GameService(GameRepository games,
                       RoundRepository rounds,
                       LocationSampler sampler,
                       ImageAssetService imagery,
                       ScoringService scoring) {
        this.games = games;
        this.rounds = rounds;
        this.sampler = sampler;
        this.imagery = imagery;
        this.scoring = scoring;
    }

    @Transactional
    public Game startClassic(UUID userId) {
        Game game = Game.builder()
                .userId(userId)
                .mode(GameMode.CLASSIC)
                .roundCount(DEFAULT_ROUNDS)
                .build();
        return games.save(game);
    }

    /**
     * Issue the next unanswered round, creating it on demand.
     *
     * <p>Idempotent by design: calling this repeatedly for the same round returns
     * the same location with a freshly minted signed URL, so a page refresh does
     * not cost the player their round or let them reroll a hard location.
     */
    @Transactional
    public RoundView issueRound(UUID gameId, UUID userId, int roundIndex) {
        Game game = games.findOwnedOrThrow(gameId, userId);
        game.assertInProgress();

        if (roundIndex < 0 || roundIndex >= game.getRoundCount()) {
            throw new InvalidRoundException("Round %d out of range".formatted(roundIndex));
        }

        // Refuse to run ahead: the player must answer round n before seeing n+1.
        if (roundIndex > 0 && !rounds.isAnswered(gameId, roundIndex - 1)) {
            throw new RoundLockedException("Previous round not yet answered");
        }

        Round round = rounds.findByGameAndIndex(gameId, roundIndex)
                .orElseGet(() -> createRound(game, roundIndex));

        if (round.isAnswered()) {
            throw new RoundAlreadyAnsweredException(roundIndex);
        }

        // Re-stamp on every issue. A refresh should not be punished, and the
        // worst case -- a player reloading to reset their clock -- is caught by
        // per-round rate limiting rather than by making refreshes destructive.
        round.setIssuedAt(Instant.now());
        rounds.save(round);

        ImageAsset asset = round.getAsset();
        return new RoundView(
                round.getId(),
                roundIndex,
                imagery.signedUrl(asset),
                asset.getProjection(),
                asset.getWidth(),
                asset.getHeight(),
                round.getLocation().getCompassAngle(),
                game.getTimeLimitSeconds()
        );
        // Note what is absent: no coordinates, no country, no Mapillary ID and
        // no attribution. Contributor names leak location -- a mapper with
        // thousands of uploads in one country identifies it instantly -- so
        // attribution is returned with the result, below.
    }

    private Round createRound(Game game, int roundIndex) {
        // Rounds are created lazily in order, so every existing round for this
        // game precedes this one. Excluding their locations keeps a game from
        // ever repeating a place; excluding their countries spreads the game
        // across the map until the pool forces a repeat (see LocationSampler).
        List<Round> prior = rounds.findByGameId(game.getId());
        Set<UUID> excludeLocations = prior.stream()
                .map(r -> r.getLocation().getId())
                .collect(Collectors.toSet());
        Set<String> excludeCountries = prior.stream()
                .map(r -> r.getLocation().getCountryCode())
                .collect(Collectors.toSet());

        // Classic Mode serves only panoramas; every other mode keeps the full
        // pool. The mode -> filter decision lives here, not buried in a query.
        boolean panoramasOnly = game.getMode() == GameMode.CLASSIC;

        Location location;
        try {
            location = sampler.sampleForGame(
                    game.getId(), roundIndex, panoramasOnly, excludeLocations, excludeCountries);
        } catch (NoLocationsAvailableException e) {
            // Translate the pool-seam failure into a mapped HTTP status so an
            // exhausted pool reads as a clean 503, not a 500 + stack trace.
            throw new PoolExhaustedException(e);
        }
        ImageAsset asset = location.primaryAsset();

        Round round = Round.builder()
                .game(game)
                .roundIndex((short) roundIndex)
                .location(location)
                .asset(asset)
                .build();
        return rounds.save(round);
    }

    /**
     * Score a guess and reveal the answer.
     *
     * @param guessLat client-supplied latitude
     * @param guessLon client-supplied longitude
     */
    @Transactional
    public GuessResult submitGuess(UUID gameId, UUID userId, int roundIndex,
                                   double guessLat, double guessLon) {

        Game game = games.findOwnedOrThrow(gameId, userId);
        game.assertInProgress();

        Round round = rounds.findByGameAndIndex(gameId, roundIndex)
                .orElseThrow(() -> new InvalidRoundException("Round not issued"));

        if (round.getIssuedAt() == null) {
            throw new InvalidRoundException("Round not issued");
        }

        Location actual = round.getLocation();
        var result = scoring.evaluateWorld(
                actual.getLatitude(), actual.getLongitude(), guessLat, guessLon);

        long elapsedMs = Duration.between(round.getIssuedAt(), Instant.now()).toMillis();

        // Conditional write: returns 0 if another request already answered this
        // round, which is how we get single-shot semantics without a lock.
        int updated = rounds.recordGuessIfUnanswered(
                round.getId(), guessLat, guessLon,
                result.distanceMetres(), result.score(), elapsedMs);

        if (updated == 0) {
            throw new RoundAlreadyAnsweredException(roundIndex);
        }

        game.addScore(result.score());
        boolean finished = roundIndex == game.getRoundCount() - 1;
        if (finished) {
            game.complete();
        }
        games.save(game);

        return new GuessResult(
                result.score(),
                result.distanceMetres(),
                actual.getLatitude(),
                actual.getLongitude(),
                actual.getCountryCode(),
                // CC-BY-SA attribution, surfaced on the result screen.
                round.getAsset().attributionFor(actual),
                game.getTotalScore(),
                finished
        );
    }
}
