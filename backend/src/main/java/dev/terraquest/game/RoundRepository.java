package dev.terraquest.game;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Round}.
 */
public interface RoundRepository extends JpaRepository<Round, UUID> {

    @Query("select r from Round r where r.game.id = :gameId and r.roundIndex = :index")
    Optional<Round> findByGameAndIndex(@Param("gameId") UUID gameId, @Param("index") int index);

    @Query("""
            select case when count(r) > 0 then true else false end
            from Round r
            where r.game.id = :gameId and r.roundIndex = :index and r.guessedAt is not null
            """)
    boolean isAnswered(@Param("gameId") UUID gameId, @Param("index") int index);

    /**
     * Record a guess exactly once.
     *
     * <p>The {@code WHERE guessed_at IS NULL} guard is the single-shot guarantee:
     * a second concurrent submission matches zero rows and returns 0, so it can
     * neither overwrite the first score nor be replayed for a better one. This is
     * a conditional write, never a read-then-write -- the latter is a race.
     *
     * <p>Native because the guess is a geography value; JPQL has no
     * {@code ST_MakePoint}. Sets distance, score and {@code guessed_at} together
     * to satisfy the {@code chk_guess_complete} constraint.
     *
     * @return rows affected: 1 on the first call, 0 on any subsequent call
     */
    @Modifying
    @Query(value = """
            UPDATE round
            SET guess = ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                distance_m = :distanceM,
                score = :score,
                elapsed_ms = :elapsedMs,
                guessed_at = now()
            WHERE id = :roundId AND guessed_at IS NULL
            """, nativeQuery = true)
    int recordGuessIfUnanswered(@Param("roundId") UUID roundId,
                                @Param("lat") double lat,
                                @Param("lon") double lon,
                                @Param("distanceM") double distanceM,
                                @Param("score") int score,
                                @Param("elapsedMs") long elapsedMs);
}
