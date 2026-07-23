package dev.terraquest.storage.r2;

import dev.terraquest.storage.StorageProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * {@link StorageProvider} backed by Cloudflare R2 through the S3-compatible AWS
 * SDK. Everything vendor-specific about object storage is contained here;
 * nothing outside {@code dev.terraquest.storage.r2} may name the AWS SDK, which
 * an ArchUnit rule enforces.
 */
class R2StorageProvider implements StorageProvider {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    R2StorageProvider(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .cacheControl("private, max-age=900")
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        return presigner.presignGetObject(
                        GetObjectPresignRequest.builder()
                                .signatureDuration(ttl)
                                .getObjectRequest(b -> b.bucket(bucket).key(key))
                                .build())
                .url()
                .toString();
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
