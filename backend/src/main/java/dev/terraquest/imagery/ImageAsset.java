package dev.terraquest.imagery;

import dev.terraquest.shared.Attribution;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A cached, EXIF-stripped copy of a source image in object storage. Maps the
 * {@code image_asset} table (V2). The client is served this, never the original
 * -- the storage key is opaque and carries no location information.
 */
@Entity
@Table(name = "image_asset")
public class ImageAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "location_id", nullable = false, updatable = false)
    private UUID locationId;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "projection", columnDefinition = "projection_type", nullable = false)
    private Projection projection;

    @Column(name = "width", nullable = false)
    private int width;

    @Column(name = "height", nullable = false)
    private int height;

    @Column(name = "bytes", nullable = false)
    private long bytes;

    @Column(name = "exif_stripped", nullable = false)
    private boolean exifStripped;

    @Column(name = "sequence_index")
    private Integer sequenceIndex;

    // DB-assigned (DEFAULT now()); read-only from the entity's perspective.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ImageAsset() {
        // for JPA
    }

    private ImageAsset(Builder b) {
        this.locationId = b.locationId;
        this.storageKey = b.storageKey;
        this.projection = b.projection;
        this.width = b.width;
        this.height = b.height;
        this.bytes = b.bytes;
        this.exifStripped = b.exifStripped;
        this.sequenceIndex = b.sequenceIndex;
    }

    /**
     * Attribution for the image this asset was cut from. Delegates to the source
     * so the asset stays free of provider-specific URL shapes -- see
     * {@link AttributionSource}.
     */
    public Attribution attributionFor(AttributionSource source) {
        return source.sourceAttribution();
    }

    public UUID getId() {
        return id;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Projection getProjection() {
        return projection;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public long getBytes() {
        return bytes;
    }

    public boolean isExifStripped() {
        return exifStripped;
    }

    public Integer getSequenceIndex() {
        return sequenceIndex;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID locationId;
        private String storageKey;
        private Projection projection;
        private int width;
        private int height;
        private long bytes;
        private boolean exifStripped;
        private Integer sequenceIndex;

        public Builder locationId(UUID locationId) {
            this.locationId = locationId;
            return this;
        }

        public Builder storageKey(String storageKey) {
            this.storageKey = storageKey;
            return this;
        }

        public Builder projection(Projection projection) {
            this.projection = projection;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder height(int height) {
            this.height = height;
            return this;
        }

        public Builder bytes(long bytes) {
            this.bytes = bytes;
            return this;
        }

        public Builder exifStripped(boolean exifStripped) {
            this.exifStripped = exifStripped;
            return this;
        }

        public Builder sequenceIndex(Integer sequenceIndex) {
            this.sequenceIndex = sequenceIndex;
            return this;
        }

        public ImageAsset build() {
            return new ImageAsset(this);
        }
    }
}
