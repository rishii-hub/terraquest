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
 * table (V1 + V3).
 *
 * <p>Probing is not one-shot: transient provider errors (measured at 5.4% in the
 * coverage spike) must not permanently discard a point. A successful probe --
 * even one that finds no imagery -- stamps {@link #probedAt} and the point leaves
 * the queue. A failed probe increments {@link #failureCount} instead, and the
 * point is retried until it succeeds or exhausts {@code MAX_CONSECUTIVE_FAILURES}
 * attempts, after which it drops out of the unprobed queue for good.
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

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    protected CandidatePoint() {
        // for JPA
    }

    private CandidatePoint(Point position, String countryCode, String source) {
        this.position = position;
        this.countryCode = countryCode;
        this.source = source;
        this.hitCount = 0;
        this.failureCount = 0;
    }

    /**
     * Create an unprobed candidate. The seed importer and tests use this; the
     * harvester never creates candidates, only drains them.
     */
    public static CandidatePoint of(GeoPoint position, String countryCode, String source) {
        return new CandidatePoint(Geo.toPoint(position), countryCode, source);
    }

    /**
     * Record a successful probe: stamp it probed and remember how many locations
     * it yielded. The point leaves the unprobed queue (which filters on a null
     * {@code probed_at}). A hit count of zero is a real result -- "probed, no
     * imagery here" -- distinct from a point that never probed successfully.
     */
    public void recordSuccess(Instant when, int hitCount) {
        this.probedAt = when;
        this.hitCount = hitCount;
    }

    /**
     * Record a transient probe failure. Leaves {@code probed_at} null so the
     * point is retried, but increments the failure counter; once it reaches
     * {@code MAX_CONSECUTIVE_FAILURES} the point drops out of the queue via the
     * partial index rather than being retried against a dead region forever.
     */
    public void recordFailure() {
        this.failureCount++;
    }

    /** True once repeated transient failures have used up this point's retries. */
    public boolean hasExhaustedRetries(int maxFailures) {
        return failureCount >= maxFailures;
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

    public int getFailureCount() {
        return failureCount;
    }
}
