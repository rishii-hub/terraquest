package dev.terraquest.game;

import dev.terraquest.imagery.Projection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Classic Mode HTTP contract.
 *
 * <p>The shape of this API is dictated by the anti-cheat model: responses are
 * deliberately thin. {@link RoundView} carries an image and nothing that could
 * be reverse-engineered into a location. The full truth arrives only in
 * {@link GuessResult}, after the answer is locked in.
 */
@RestController
@RequestMapping("/api/v1/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GameCreated startGame(@AuthenticationPrincipal AuthenticatedUser user) {
        Game game = gameService.startClassic(user.id());
        return new GameCreated(game.getId(), game.getRoundCount());
    }

    /**
     * Fetch the current round. Safe to call repeatedly; a refresh re-mints the
     * signed URL rather than consuming or rerolling the round.
     */
    @GetMapping("/{gameId}/rounds/{index}")
    public RoundView getRound(@AuthenticationPrincipal AuthenticatedUser user,
                              @PathVariable UUID gameId,
                              @PathVariable int index) {
        return gameService.issueRound(gameId, user.id(), index);
    }

    @PostMapping("/{gameId}/rounds/{index}/guess")
    public GuessResult guess(@AuthenticationPrincipal AuthenticatedUser user,
                             @PathVariable UUID gameId,
                             @PathVariable int index,
                             @Valid @RequestBody GuessRequest body) {
        return gameService.submitGuess(gameId, user.id(), index, body.lat(), body.lon());
    }

    // ---------------------------------------------------------------
    // DTOs
    // ---------------------------------------------------------------

    public record GameCreated(UUID gameId, int roundCount) {}

    /**
     * Note the omissions: no coordinates, no country, no Mapillary identifier,
     * no contributor name. {@code initialHeading} is the compass bearing the
     * viewer should face on load -- it reveals orientation, not position.
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
    ) {}

    /**
     * Client-supplied duration is intentionally absent. Elapsed time is computed
     * server-side from the round's issuance timestamp; accepting it from the
     * client would make every timed mode forgeable.
     */
    public record GuessRequest(
            @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @DecimalMin("-180.0") @DecimalMax("180.0") double lon
    ) {}

    public record GuessResult(
            int score,
            double distanceMetres,
            double actualLat,
            double actualLon,
            String countryCode,
            Attribution attribution,
            int runningTotal,
            boolean gameComplete
    ) {}

    /**
     * CC-BY-SA credit. Rendered on the result screen -- deferred rather than
     * omitted, because a contributor's upload history frequently gives away the
     * country before the player has guessed.
     */
    public record Attribution(
            String contributor,
            String profileUrl,
            String licence,
            String sourceUrl
    ) {}
}
