package dev.terraquest.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Game}.
 */
public interface GameRepository extends JpaRepository<Game, UUID> {

    Optional<Game> findByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Load a game the caller owns, or throw.
     *
     * <p>Scoped by {@code userId} in the query, not checked after loading: a
     * game belonging to another player must be indistinguishable from one that
     * does not exist, so ownership is a WHERE clause and the failure is a
     * not-found, never a forbidden that confirms the id is real.
     */
    default Game findOwnedOrThrow(UUID gameId, UUID userId) {
        return findByIdAndUserId(gameId, userId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
    }
}
