package dev.terraquest.location;

import dev.terraquest.imagery.AttributionSource;
import dev.terraquest.imagery.ImageAsset;
import dev.terraquest.shared.Attribution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A playable location. Maps the {@code location} table (V1 + V2). A game round
 * is a single indexed SELECT against this table; no imagery backend is contacted
 * while a player waits.
 *
 * <p>Implements {@link AttributionSource} so an {@link ImageAsset} can attribute
 * itself without {@code imagery} depending back on {@code location}. The JTS
 * point stays private; the service surface sees only {@code getLatitude()} /
 * {@code getLongitude()}.
 */
@Entity
@Table(name = "location")
public class Location implements AttributionSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "mapillary_image_id", nullable = false, unique = true)
    private String mapillaryImageId;

    @Column(name = "sequence_id")
    private String sequenceId;

    @Column(name = "position", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point position;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", length = 2, nullable = false)
    private String countryCode;

    @Column(name = "compass_angle")
    private Float compassAngle;

    @Column(name = "is_panoramic", nullable = false)
    private boolean panoramic;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "creator_username")
    private String creatorUsername;

    @Column(name = "creator_id")
    private String creatorId;

    @Column(name = "quality_score", nullable = false)
    private float qualityScore = 0.5f;

    @Column(name = "difficulty", nullable = false)
    private short difficulty = 3;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "report_count", nullable = false)
    private int reportCount = 0;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "asset_ready", nullable = false)
    private boolean assetReady = false;

    @Column(name = "ingest_attempts", nullable = false)
    private int ingestAttempts = 0;

    // Read-only view for primaryAsset(); the FK is owned by ImageAsset.locationId.
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", insertable = false, updatable = false)
    private List<ImageAsset> assets;

    protected Location() {
        // for JPA
    }

    // Package-private: lets the in-memory test fake hand back a Location with an
    // id without a database round-trip. Not part of the production surface.
    Location(UUID id, String countryCode, boolean assetReady) {
        this.id = id;
        this.countryCode = countryCode;
        this.assetReady = assetReady;
    }

    private Location(Builder b) {
        this.id = b.id;
        this.mapillaryImageId = b.mapillaryImageId;
        this.sequenceId = b.sequenceId;
        this.position = b.position;
        this.countryCode = b.countryCode;
        this.compassAngle = b.compassAngle;
        this.panoramic = b.panoramic;
        this.capturedAt = b.capturedAt;
        this.creatorUsername = b.creatorUsername;
        this.creatorId = b.creatorId;
        this.qualityScore = b.qualityScore;
        this.difficulty = b.difficulty;
        this.active = b.active;
        this.reportCount = b.reportCount;
        this.assetReady = b.assetReady;
    }

    /**
     * The asset the client is served for this location. Only fully-processed
     * (EXIF-stripped) assets are eligible; a location that reaches a game round
     * has {@code asset_ready = true} and therefore at least one.
     */
    public ImageAsset primaryAsset() {
        if (assets != null) {
            for (ImageAsset asset : assets) {
                if (asset.isExifStripped()) {
                    return asset;
                }
            }
        }
        throw new IllegalStateException("Location has no servable asset: " + id);
    }

    @Override
    public Attribution sourceAttribution() {
        // Mapillary-shaped because the schema is: the column is literally
        // `mapillary_image_id` and there is no `provider` column to switch on.
        // Isolated here (not in ImageAsset) so that when the schema generalises,
        // only this method changes. See the PR notes on the attribution seam.
        String contributor = creatorUsername != null ? creatorUsername : "Mapillary contributor";
        String profileUrl = creatorId != null
                ? "https://www.mapillary.com/app/user/" + creatorId
                : null;
        String sourceUrl = "https://www.mapillary.com/app?pKey=" + mapillaryImageId;
        return new Attribution(contributor, profileUrl, "CC-BY-SA-4.0", sourceUrl);
    }

    public UUID getId() {
        return id;
    }

    public double getLatitude() {
        return position.getY();
    }

    public double getLongitude() {
        return position.getX();
    }

    public String getCountryCode() {
        return countryCode;
    }

    public Float getCompassAngle() {
        return compassAngle;
    }

    public String getMapillaryImageId() {
        return mapillaryImageId;
    }

    public boolean isPanoramic() {
        return panoramic;
    }

    public float getQualityScore() {
        return qualityScore;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isAssetReady() {
        return assetReady;
    }

    public int getIngestAttempts() {
        return ingestAttempts;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private String mapillaryImageId;
        private String sequenceId;
        private Point position;
        private String countryCode;
        private Float compassAngle;
        private boolean panoramic = false;
        private Instant capturedAt;
        private String creatorUsername;
        private String creatorId;
        private float qualityScore = 0.5f;
        private short difficulty = 3;
        private boolean active = true;
        private int reportCount = 0;
        private boolean assetReady = false;

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder mapillaryImageId(String mapillaryImageId) {
            this.mapillaryImageId = mapillaryImageId;
            return this;
        }

        public Builder sequenceId(String sequenceId) {
            this.sequenceId = sequenceId;
            return this;
        }

        public Builder position(Point position) {
            this.position = position;
            return this;
        }

        public Builder countryCode(String countryCode) {
            this.countryCode = countryCode;
            return this;
        }

        public Builder compassAngle(Float compassAngle) {
            this.compassAngle = compassAngle;
            return this;
        }

        public Builder panoramic(boolean panoramic) {
            this.panoramic = panoramic;
            return this;
        }

        public Builder capturedAt(Instant capturedAt) {
            this.capturedAt = capturedAt;
            return this;
        }

        public Builder creatorUsername(String creatorUsername) {
            this.creatorUsername = creatorUsername;
            return this;
        }

        public Builder creatorId(String creatorId) {
            this.creatorId = creatorId;
            return this;
        }

        public Builder qualityScore(float qualityScore) {
            this.qualityScore = qualityScore;
            return this;
        }

        public Builder difficulty(short difficulty) {
            this.difficulty = difficulty;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public Builder reportCount(int reportCount) {
            this.reportCount = reportCount;
            return this;
        }

        public Builder assetReady(boolean assetReady) {
            this.assetReady = assetReady;
            return this;
        }

        public Location build() {
            return new Location(this);
        }
    }
}
