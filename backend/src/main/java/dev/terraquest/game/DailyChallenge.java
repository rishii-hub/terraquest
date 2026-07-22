package dev.terraquest.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The same five locations served to everyone on a given date. Maps the
 * {@code daily_challenge} table (V1). Chosen ahead of time by a scheduled job
 * so the draw is deterministic and cacheable.
 */
@Entity
@Table(name = "daily_challenge")
public class DailyChallenge {

    @Id
    @Column(name = "challenge_date", nullable = false, updatable = false)
    private LocalDate challengeDate;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "location_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] locationIds;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected DailyChallenge() {
        // for JPA
    }

    public DailyChallenge(LocalDate challengeDate, UUID[] locationIds) {
        this.challengeDate = challengeDate;
        this.locationIds = locationIds != null ? locationIds.clone() : null;
    }

    public LocalDate getChallengeDate() {
        return challengeDate;
    }

    public UUID[] getLocationIds() {
        return locationIds != null ? locationIds.clone() : null;
    }
}
