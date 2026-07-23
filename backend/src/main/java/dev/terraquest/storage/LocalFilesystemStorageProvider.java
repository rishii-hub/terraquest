package dev.terraquest.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * {@link StorageProvider} backed by the local filesystem, active whenever R2
 * credentials are absent. This is what makes local development and imagery
 * harvesting work with no cloud account at all.
 *
 * <p>Objects are written under a configured base directory; keys map to relative
 * paths beneath it. {@link #signedUrl} returns a stable {@code file:} URL and
 * ignores the TTL -- local files do not expire, which is fine because this
 * backend is never a production surface.
 */
@Component
@Conditional(R2Configured.NotConfigured.class)
public class LocalFilesystemStorageProvider implements StorageProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystemStorageProvider.class);

    private final Path baseDir;

    public LocalFilesystemStorageProvider(
            @Value("${terraquest.storage.local.directory:${java.io.tmpdir}/terraquest-imagery}") String directory) {
        this.baseDir = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create local storage directory " + baseDir, e);
        }
        log.info("Storage: local filesystem active (no R2 credentials configured) -- writing under {}", baseDir);
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + key, e);
        }
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        // Local files carry no expiry; the TTL is meaningless here and ignored.
        return resolve(key).toUri().toString();
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    /**
     * Map a key to a path under {@link #baseDir}, refusing any key that would
     * escape it (e.g. one containing {@code ../}). Keys come from our own
     * pipeline today, but a storage layer that can be talked out of its own
     * directory is a liability regardless of the current caller.
     */
    private Path resolve(String key) {
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Storage key escapes base directory: " + key);
        }
        return resolved;
    }
}
