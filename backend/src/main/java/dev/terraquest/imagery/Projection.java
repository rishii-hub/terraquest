package dev.terraquest.imagery;

/**
 * How a stored image is projected. Mirrors the Postgres {@code projection_type}
 * enum in V2; a flat frame is oriented by a compass angle, an equirectangular
 * panorama is looked around freely.
 */
public enum Projection {
    EQUIRECTANGULAR,
    FLAT
}
