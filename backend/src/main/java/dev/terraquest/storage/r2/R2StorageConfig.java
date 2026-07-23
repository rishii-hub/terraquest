package dev.terraquest.storage.r2;

import dev.terraquest.storage.R2Configured;
import dev.terraquest.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Wires the R2-backed {@link StorageProvider}, and with it the only S3 client in
 * the application. Guarded by {@link R2Configured}: with no R2 credentials set,
 * none of these beans exist and the local-filesystem provider takes over -- so
 * the app starts cleanly without a cloud account, which was the original wiring
 * gap.
 *
 * <p>R2 speaks S3 at an account-scoped endpoint with a fixed {@code auto} region.
 */
@Configuration
@Conditional(R2Configured.class)
class R2StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(R2StorageConfig.class);

    @Bean
    S3Client s3Client(@Value("${terraquest.r2.account-id}") String accountId,
                      @Value("${terraquest.r2.access-key-id}") String accessKeyId,
                      @Value("${terraquest.r2.secret-access-key}") String secretAccessKey) {
        return S3Client.builder()
                .endpointOverride(endpoint(accountId))
                .region(Region.of("auto"))
                .credentialsProvider(credentials(accessKeyId, secretAccessKey))
                .build();
    }

    @Bean
    S3Presigner s3Presigner(@Value("${terraquest.r2.account-id}") String accountId,
                            @Value("${terraquest.r2.access-key-id}") String accessKeyId,
                            @Value("${terraquest.r2.secret-access-key}") String secretAccessKey) {
        return S3Presigner.builder()
                .endpointOverride(endpoint(accountId))
                .region(Region.of("auto"))
                .credentialsProvider(credentials(accessKeyId, secretAccessKey))
                .build();
    }

    @Bean
    StorageProvider r2StorageProvider(S3Client s3Client,
                                      S3Presigner s3Presigner,
                                      @Value("${terraquest.r2.bucket}") String bucket) {
        log.info("Storage: Cloudflare R2 active (bucket '{}')", bucket);
        return new R2StorageProvider(s3Client, s3Presigner, bucket);
    }

    private static URI endpoint(String accountId) {
        return URI.create("https://" + accountId + ".r2.cloudflarestorage.com");
    }

    private static StaticCredentialsProvider credentials(String accessKeyId, String secretAccessKey) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    }
}
