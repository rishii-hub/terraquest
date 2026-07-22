package dev.terraquest.game;

import dev.terraquest.imagery.ImageAsset;
import dev.terraquest.location.Location;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

/**
 * One round of a game. Maps the {@code round} table (V1 + V2).
 *
 * <p>The guess write itself is deliberately not a method here: it is a
 * conditional {@code UPDATE ... WHERE guessed_at IS NULL} in
 * {@code RoundRepository}, which is what makes a guess single-shot under
 * concurrency. The entity only reports whether it has been answered.
 */
@Entity
@Table(name = "round")
public class Round {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false, updatable = false)
    private Game game;

    @Column(name = "round_index", nullable = false, updatable = false)
    private short roundIndex;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_id", nullable = false, updatable = false)
    private Location location;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private ImageAsset asset;

    @Column(name = "guess", columnDefinition = "geography(Point,4326)")
    private Point guess;

    @Column(name = "guess_country", length = 2)
    private String guessCountry;

    @Column(name = "distance_m")
    private Double distanceM;

    @Column(name = "score")
    private Integer score;

    @Column(name = "elapsed_ms")
    private Integer elapsedMs;

    @Column(name = "guessed_at")
    private Instant guessedAt;

    @Column(name = "issued_at")
    private Instant issuedAt;

    protected Round() {
        // for JPA
    }

    private Round(Builder b) {
        this.game = b.game;
        this.roundIndex = b.roundIndex;
        this.location = b.location;
        this.asset = b.asset;
    }

    public boolean isAnswered() {
        return guessedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public Game getGame() {
        return game;
    }

    public short getRoundIndex() {
        return roundIndex;
    }

    public Location getLocation() {
        return location;
    }

    public ImageAsset getAsset() {
        return asset;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Game game;
        private short roundIndex;
        private Location location;
        private ImageAsset asset;

        public Builder game(Game game) {
            this.game = game;
            return this;
        }

        public Builder roundIndex(short roundIndex) {
            this.roundIndex = roundIndex;
            return this;
        }

        public Builder location(Location location) {
            this.location = location;
            return this;
        }

        public Builder asset(ImageAsset asset) {
            this.asset = asset;
            return this;
        }

        public Round build() {
            return new Round(this);
        }
    }
}
