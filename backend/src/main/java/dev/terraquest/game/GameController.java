package dev.terraquest.game;

import jakarta.validation.Valid;
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

    // DTOs (GameCreated, RoundView, GuessRequest, GuessResult) and the shared
    // Attribution value type are top-level in this package / the shared kernel,
    // so both this controller and GameService reference the same types.
}
