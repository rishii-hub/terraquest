package dev.terraquest.storage;

import java.time.Duration;

/**
 * Service provider interface for object storage.
 *
 * <p>ADR 0003 justifies this seam by <b>testability</b>, not migration: R2 is
 * S3-compatible and the AWS SDK is already a portability layer, so the value is
 * an in-memory implementation that lets the asset pipeline run without
 * credentials, and a local-filesystem implementation that lets development and
 * harvesting work with no cloud account at all.
 *
 * <p>Keys are opaque, caller-chosen strings (the asset pipeline uses
 * {@code img/<random-uuid>}). They may contain {@code /} as a path separator;
 * implementations treat that as hierarchy, never as an instruction to escape
 * their own namespace.
 *
 * <p>Nothing above this interface names a storage vendor. The AWS SDK lives only
 * behind {@code dev.terraquest.storage.r2}, enforced by ArchUnit.
 */
public interface StorageProvider {

    /**
     * Store bytes under a key, overwriting any existing object at that key.
     *
     * @param key         opaque storage key
     * @param content     object bytes
     * @param contentType MIME type, e.g. {@code image/jpeg}
     */
    void put(String key, byte[] content, String contentType);

    /**
     * Produce a URL that grants read access to an object for a bounded time.
     *
     * <p>For cloud backends this is a signed, expiring URL; the TTL is a real
     * security boundary. A backend whose URLs cannot expire (the local
     * filesystem) returns a stable URL and ignores the TTL -- acceptable
     * precisely because it is never a production surface.
     *
     * @param key opaque storage key
     * @param ttl how long the URL should remain valid
     * @return a resolvable URL for the object
     */
    String signedUrl(String key, Duration ttl);

    /** Delete the object at {@code key}; a no-op if nothing is stored there. */
    void delete(String key);

    /** Whether an object exists at {@code key}. */
    boolean exists(String key);
}
