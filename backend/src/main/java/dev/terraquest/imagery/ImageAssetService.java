package dev.terraquest.imagery;

import dev.terraquest.storage.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Iterator;
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
     * Equirectangular panoramas are downscaled to this width. A 360 degree wrap
     * across 2048px is only ~5.7px per degree, which is visibly soft the moment a
     * player zooms; 4096 doubles that and is the difference between readable and
     * unreadable shopfront text -- which is the entire skill of the game.
     */
    private static final int MAX_PANO_WIDTH = 4096;

    /**
     * Flat frames fill a normal viewport rather than a 360 sphere, so they gain
     * nothing from the panorama budget and the storage saving is worth keeping.
     */
    private static final int MAX_FLAT_WIDTH = 2048;

    /**
     * Explicit, and deliberately not the platform default. {@code ImageIO.write}
     * with no {@link ImageWriteParam} encodes at roughly 0.7, which stored the
     * whole pool at destructive compression and is the bug this constant exists
     * to prevent recurring. Raise with care: quality is superlinear in file size.
     */
    private static final float JPEG_QUALITY = 0.92f;

    private final StorageProvider storage;
    private final ImageAssetRepository assets;

    /**
     * Ceiling on a single source download. Panoramas are fetched at full capture
     * resolution now, and an unbounded {@code ofByteArray} read of a pathological
     * original is an OOM that takes the whole harvester with it.
     */
    private final long maxDownloadBytes;

    public ImageAssetService(
            StorageProvider storage,
            ImageAssetRepository assets,
            @org.springframework.beans.factory.annotation.Value(
                    "${terraquest.imagery.max-download-bytes:31457280}") long maxDownloadBytes) {
        // Fail at startup, not on every image. A nonsensical ceiling would
        // otherwise surface as a silent 100% ingest failure rate, which reads
        // like a provider outage rather than a typo in configuration.
        if (maxDownloadBytes <= 0 || maxDownloadBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "terraquest.imagery.max-download-bytes must be between 1 and "
                            + Integer.MAX_VALUE + ", got " + maxDownloadBytes);
        }

        this.storage = storage;
        this.assets = assets;
        this.maxDownloadBytes = maxDownloadBytes;
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

        // Downscale only. A source smaller than the cap is left alone: upscaling
        // invents no detail and only inflates what we pay to store.
        int cap = panoramic ? MAX_PANO_WIDTH : MAX_FLAT_WIDTH;
        if (image.getWidth() > cap) {
            // Equirectangular is 2:1 by definition. Flat frames carry whatever
            // aspect the camera had, so scale their height proportionally rather
            // than squashing them into the panorama's shape.
            int height = panoramic
                    ? cap / 2
                    : Math.max(1, Math.round(image.getHeight() * (cap / (float) image.getWidth())));
            image = resize(image, cap, height);
        }

        // Drop any alpha channel; JPEG cannot represent it and some encoders
        // silently produce corrupt output otherwise.
        BufferedImage rgb = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D flatten = rgb.createGraphics();
        try {
            flatten.drawImage(image, 0, 0, null);
        } finally {
            flatten.dispose();
        }

        return encodeJpeg(rgb);
    }

    /**
     * Encode at an explicit quality.
     *
     * <p>Writing {@code null} stream metadata and a {@link IIOImage} with null
     * image metadata is what keeps the EXIF guarantee above intact: we emit pixel
     * data and nothing else, so there is no segment for a GPS tag to survive in.
     */
    private byte[] encodeJpeg(BufferedImage rgb) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();

        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private BufferedImage resize(BufferedImage src, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            // Bicubic rather than the legacy Image.SCALE_SMOOTH path: originals
            // are now multi-megapixel and area-averaging them is slow enough to
            // show up across a harvest batch.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    /**
     * Fetch the source bytes, refusing anything over {@link #maxDownloadBytes}.
     *
     * <p>An oversized image is not a crash: the caller turns any exception here
     * into a skipped ingest, which the harvester already counts as a failure. One
     * pathological original costs us that image, not the batch.
     */
    private byte[] download(String url) throws IOException, InterruptedException {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed: HTTP " + response.statusCode());
        }

        // Cheap early exit for servers that tell the truth; readNBytes below is
        // what actually bounds memory, since Content-Length may be absent or lie.
        var declared = response.headers().firstValueAsLong("content-length");
        if (declared.isPresent() && declared.getAsLong() > maxDownloadBytes) {
            response.body().close();
            throw new IOException("Image exceeds " + maxDownloadBytes
                    + " byte limit (declared " + declared.getAsLong() + ")");
        }

        try (InputStream in = response.body()) {
            // One byte past the cap: if we get it, the source is over the limit.
            byte[] body = in.readNBytes(Math.toIntExact(maxDownloadBytes) + 1);
            if (body.length > maxDownloadBytes) {
                throw new IOException("Image exceeds " + maxDownloadBytes + " byte limit");
            }
            return body;
        }
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
