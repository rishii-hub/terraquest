package dev.terraquest.imagery;

import dev.terraquest.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Owns the imagery proxy: ingesting Mapillary images into object storage (via
 * {@link StorageProvider}) and handing out short-lived signed URLs at play time.
 *
 * <p>Why proxy at all: a Mapillary image ID resolves to exact coordinates via a
 * single public API call. Shipping that ID to the client makes every leaderboard
 * meaningless. Assets here are keyed by a random UUID that encodes nothing.
 *
 * <p>What this does not defend against: an attacker who plays many rounds,
 * perceptually hashes the images and builds a lookup table. That attack works
 * against any fixed-pool geography game, GeoGuessr included, and the only real
 * mitigation is pool size. We are raising the cost of cheating, not eliminating it.
 */
@Service
public class ImageAssetService {

    private static final Logger log = LoggerFactory.getLogger(ImageAssetService.class);

    /**
     * Signed URLs outlive a round generously enough to survive a slow mobile
     * connection or a backgrounded tab, but not long enough to be worth sharing.
     */
    private static final Duration SIGNED_URL_TTL = Duration.ofMinutes(15);

    /**
     * Equirectangular panoramas are downscaled to this width. 2048x1024 is the
     * point where a phone GPU stops struggling and text on shopfronts is still
     * readable when zoomed -- which is the entire skill of the game.
     */
    private static final int MAX_PANO_WIDTH = 2048;

    private final StorageProvider storage;
    private final ImageAssetRepository assets;

    public ImageAssetService(StorageProvider storage, ImageAssetRepository assets) {
        this.storage = storage;
        this.assets = assets;
    }

    /**
     * Fetch, sanitise and store one Mapillary image. Called from the harvester,
     * never from a request thread.
     *
     * @return the persisted asset, or empty if the image could not be processed
     */
    public java.util.Optional<ImageAsset> ingest(UUID locationId, String sourceUrl, boolean panoramic) {
        try {
            byte[] original = download(sourceUrl);
            byte[] clean = stripMetadataAndResize(original, panoramic);

            String storageKey = "img/" + UUID.randomUUID();

            storage.put(storageKey, clean, "image/jpeg");

            BufferedImage probe = ImageIO.read(new ByteArrayInputStream(clean));

            ImageAsset asset = ImageAsset.builder()
                    .locationId(locationId)
                    .storageKey(storageKey)
                    .projection(panoramic ? Projection.EQUIRECTANGULAR : Projection.FLAT)
                    .width(probe.getWidth())
                    .height(probe.getHeight())
                    .bytes(clean.length)
                    .exifStripped(true)
                    .build();

            return java.util.Optional.of(assets.save(asset));

        } catch (Exception e) {
            log.warn("Asset ingest failed for location {}: {}", locationId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Re-encode through a raw pixel buffer.
     *
     * <p>This is the security-critical step, not a nicety. Mapillary JPEGs can
     * carry GPS EXIF tags. Copying bytes straight into R2 would ship the answer
     * inside the image the player is being asked to identify. Decoding to a
     * {@link BufferedImage} and re-encoding discards every metadata segment,
     * because the pixel buffer simply has nowhere to put them.
     */
    private byte[] stripMetadataAndResize(byte[] source, boolean panoramic) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            throw new IOException("Undecodable image");
        }

        if (panoramic && image.getWidth() > MAX_PANO_WIDTH) {
            image = resize(image, MAX_PANO_WIDTH, MAX_PANO_WIDTH / 2);
        }

        // Drop any alpha channel; JPEG cannot represent it and some encoders
        // silently produce corrupt output otherwise.
        BufferedImage rgb = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(image, 0, 0, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "jpg", out)) {
            throw new IOException("No JPEG writer available");
        }
        return out.toByteArray();
    }

    private BufferedImage resize(BufferedImage src, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        scaled.createGraphics().drawImage(
                src.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        return scaled;
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Mint a short-lived signed URL for an asset. Generated per round issuance,
     * so two players seeing the same location receive different URLs and no
     * durable identifier is ever exposed.
     */
    public String signedUrl(ImageAsset asset) {
        if (!asset.isExifStripped()) {
            // Belt and braces. The query layer already filters on this flag, but
            // a leaked unstripped asset is a total compromise of the round.
            throw new IllegalStateException("Refusing to serve asset with intact metadata: " + asset.getId());
        }

        return storage.signedUrl(asset.getStorageKey(), SIGNED_URL_TTL);
    }
}
