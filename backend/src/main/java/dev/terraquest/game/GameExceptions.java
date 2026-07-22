package dev.terraquest.game;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * The exceptions {@code GameService} throws, kept together because they share
 * one purpose: turning a broken game-flow invariant into a specific HTTP status
 * without leaking why. Note {@link GameNotFoundException} is a 404 even when the
 * game exists but belongs to someone else -- ownership failures must not confirm
 * the id is real.
 */
final class GameExceptions {
    private GameExceptions() {
    }
}

/** The game does not exist, or is not the caller's. */
@ResponseStatus(HttpStatus.NOT_FOUND)
class GameNotFoundException extends RuntimeException {
    GameNotFoundException(UUID gameId) {
        super("Game not found: " + gameId);
    }
}

/** A state-changing call arrived for a game that is not in progress. */
@ResponseStatus(HttpStatus.CONFLICT)
class GameNotInProgressException extends RuntimeException {
    GameNotInProgressException(UUID gameId, GameStatus status) {
        super("Game " + gameId + " is " + status + ", not IN_PROGRESS");
    }
}

/** A round index outside the game, or a round asked for before it was issued. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidRoundException extends RuntimeException {
    InvalidRoundException(String message) {
        super(message);
    }
}

/** The player tried to run ahead to a round before answering the previous one. */
@ResponseStatus(HttpStatus.CONFLICT)
class RoundLockedException extends RuntimeException {
    RoundLockedException(String message) {
        super(message);
    }
}

/** A guess arrived for a round already answered -- the single-shot guarantee. */
@ResponseStatus(HttpStatus.CONFLICT)
class RoundAlreadyAnsweredException extends RuntimeException {
    RoundAlreadyAnsweredException(int roundIndex) {
        super("Round " + roundIndex + " has already been answered");
    }
}
