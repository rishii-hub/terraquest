package dev.terraquest.storage;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link StorageProvider} for tests -- the testability argument ADR
 * 0003 makes for this SPI. Lets the asset pipeline be exercised end to end with
 * no S3 client, no filesystem and no credentials.
 */
public class InMemoryStorageProvider implements StorageProvider {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] content, String contentType) {
        objects.put(key, content.clone());
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        if (!objects.containsKey(key)) {
            throw new IllegalStateException("No object stored at " + key);
        }
        return "mem://" + key;
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    @Override
    public boolean exists(String key) {
        return objects.containsKey(key);
    }

    /** Test accessor: the bytes stored at {@code key}, if any. */
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(objects.get(key)).map(byte[]::clone);
    }
}
