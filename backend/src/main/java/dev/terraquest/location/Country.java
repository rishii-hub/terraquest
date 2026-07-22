package dev.terraquest.location;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

/**
 * Reference data seeded from Natural Earth. Maps the {@code country} table (V1).
 * Drives the Passport system and country-accuracy stats; not written at play
 * time.
 */
@Entity
@Table(name = "country")
public class Country {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "iso2", length = 2, nullable = false, updatable = false)
    private String iso2;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "iso3", length = 3, nullable = false)
    private String iso3;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "continent", nullable = false)
    private String continent;

    @Column(name = "centroid", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point centroid;

    protected Country() {
        // for JPA
    }

    public String getIso2() {
        return iso2;
    }

    public String getIso3() {
        return iso3;
    }

    public String getName() {
        return name;
    }

    public String getContinent() {
        return continent;
    }

    public Point getCentroid() {
        return centroid;
    }
}
