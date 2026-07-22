package dev.terraquest.progression;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stamp in a player's passport: the first time they correctly placed a
 * round in a given country, plus a running correct count. Maps the
 * {@code passport_stamp} table (V1), keyed by (user, country).
 *
 * <p>References its user and country by id rather than by association, keeping
 * {@code progression} a leaf that depends on no other feature module.
 */
@Entity
@Table(name = "passport_stamp")
@IdClass(PassportStamp.Key.class)
public class PassportStamp {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "country_code", length = 2, nullable = false, updatable = false)
    private String countryCode;

    @Column(name = "first_at", nullable = false, insertable = false, updatable = false)
    private Instant firstAt;

    @Column(name = "correct_count", nullable = false)
    private int correctCount = 1;

    protected PassportStamp() {
        // for JPA
    }

    public UUID getUserId() {
        return userId;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public int getCorrectCount() {
        return correctCount;
    }

    /** Composite key. JPA requires a public no-arg constructor and equals/hashCode. */
    public static class Key implements Serializable {
        private UUID userId;
        private String countryCode;

        public Key() {
        }

        public Key(UUID userId, String countryCode) {
            this.userId = userId;
            this.countryCode = countryCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(userId, key.userId) && Objects.equals(countryCode, key.countryCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, countryCode);
        }
    }
}
