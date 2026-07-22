package dev.terraquest.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single-player game. Maps the {@code game} table (V1).
 *
 * <p>Carries the small amount of behaviour the anti-cheat design assumes lives
 * on the aggregate rather than in the service: scoring, completion, and the
 * in-progress guard.
 */
@Entity
@Table(name = "game")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "mode", columnDefinition = "game_mode", nullable = false, updatable = false)
    private GameMode mode;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "game_status", nullable = false)
    private GameStatus status = GameStatus.IN_PROGRESS;

    @Column(name = "total_score", nullable = false)
    private int totalScore = 0;

    @Column(name = "round_count", nullable = false)
    private short roundCount = 5;

    @Column(name = "time_limit_s")
    private Integer timeLimitSeconds;

    @Column(name = "daily_key")
    private LocalDate dailyKey;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Game() {
        // for JPA
    }

    private Game(Builder b) {
        this.userId = b.userId;
        this.mode = b.mode;
        this.roundCount = b.roundCount;
        this.timeLimitSeconds = b.timeLimitSeconds;
        this.dailyKey = b.dailyKey;
    }

    /** Guard every state transition through the aggregate, not the endpoint. */
    public void assertInProgress() {
        if (status != GameStatus.IN_PROGRESS) {
            throw new GameNotInProgressException(id, status);
        }
    }

    public void addScore(int roundScore) {
        this.totalScore += roundScore;
    }

    public void complete() {
        this.status = GameStatus.COMPLETED;
        this.finishedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public GameMode getMode() {
        return mode;
    }

    public GameStatus getStatus() {
        return status;
    }

    public int getTotalScore() {
        return totalScore;
    }

    public int getRoundCount() {
        return roundCount;
    }

    public Integer getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID userId;
        private GameMode mode;
        private short roundCount = 5;
        private Integer timeLimitSeconds;
        private LocalDate dailyKey;

        public Builder userId(UUID userId) {
            this.userId = userId;
            return this;
        }

        public Builder mode(GameMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder roundCount(int roundCount) {
            this.roundCount = (short) roundCount;
            return this;
        }

        public Builder timeLimitSeconds(Integer timeLimitSeconds) {
            this.timeLimitSeconds = timeLimitSeconds;
            return this;
        }

        public Builder dailyKey(LocalDate dailyKey) {
            this.dailyKey = dailyKey;
            return this;
        }

        public Game build() {
            return new Game(this);
        }
    }
}
