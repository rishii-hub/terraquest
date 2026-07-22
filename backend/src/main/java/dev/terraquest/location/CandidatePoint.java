package dev.terraquest.location;

import dev.terraquest.shared.Geo;
import dev.terraquest.shared.GeoPoint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;

/**
 * A seed point the harvester probes for imagery. Maps the {@code candidate_point}
 * table (V1). Probed exactly once, then stamped so the queue converges instead
 * of retrying dead regions forever.
 */
@Entity
@Table(name = "candidate_point")
public class CandidatePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "position", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point position;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "probed_at")
    private Instant probedAt;

    @Column(name = "hit_count", nullable = false)
    private int hitCount;

    protected CandidatePoint() {
        // for JPA
    }

    private CandidatePoint(Point position, String countryCode, String source) {
        this.position = position;
        this.countryCode = countryCode;
        this.source = source;
        this.hitCount = 0;
    }

    /**
     * Create an unprobed candidate. The seed importer and tests use this; the
     * harvester never creates candidates, only drains them.
     */
    public static CandidatePoint of(GeoPoint position, String countryCode, String source) {
        return new CandidatePoint(Geo.toPoint(position), countryCode, source);
    }

    /** Stamp this point probed. Idempotent enough: the queue filters on null. */
    public void markProbed(Instant when) {
        this.probedAt = when;
    }

    public Long getId() {
        return id;
    }

    public GeoPoint position() {
        return Geo.toGeoPoint(position);
    }

    public String countryCode() {
        return countryCode;
    }

    public String source() {
        return source;
    }

    public boolean isProbed() {
        return probedAt != null;
    }

    public Instant getProbedAt() {
        return probedAt;
    }

    public int getHitCount() {
        return hitCount;
    }
}
