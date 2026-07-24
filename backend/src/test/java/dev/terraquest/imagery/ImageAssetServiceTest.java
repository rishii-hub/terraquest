package dev.terraquest.imagery;

import com.sun.net.httpserver.HttpServer;
import dev.terraquest.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the ingest encoder.
 *
 * <p>The failure mode these tests exist to prevent: imagery silently degraded on
 * the way into storage. Two independent bugs produced it together -- the harvester
 * fetched a 1024px thumbnail, and {@code ImageIO.write} was called with no
 * {@link ImageWriteParam} so it encoded at the platform default of roughly 0.7.
 * The result was a 60-110 KB panorama that looked soft in the viewer. Neither bug
 * was visible from any assertion in the suite, which is why the size floor below
 * matters more than the dimension checks.
 *
 * <p>A real HTTP server serves the fixtures so {@code download} is exercised for
 * real rather than stubbed around, and nothing here needs Spring, a database or a
 * mocking framework.
 */
class ImageAssetServiceTest {

    private static final long THIRTY_MB = 30L * 1024 * 1024;

    /**
     * The signature an EXIF APP1 segment opens with, after the 0xFFE1 marker:
     * "Exif" followed by two NUL bytes.
     */
    private static final byte[] EXIF_SIGNATURE = {'E', 'x', 'i', 'f', 0, 0};

    private HttpServer server;
    private InMemoryStorageProvider storage;
    private ImageAssetService service;
    private final AtomicInteger pathCounter = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        storage = new InMemoryStorageProvider();
        service = newService(THIRTY_MB);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // ---------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------

    @Test
    void a_panorama_wider_than_the_cap_is_stored_at_4096_by_2048() throws Exception {
        String url = serve(jpeg(detailed(4608, 2304), 0.9f));

        BufferedImage stored = storedImage(service.ingest(UUID.randomUUID(), url, true));

        assertThat(stored.getWidth())
                .as("panoramas are capped at 4096; 2048 is only ~5.7px per degree of wrap")
                .isEqualTo(4096);
        assertThat(stored.getHeight())
                .as("equirectangular is 2:1 by definition")
                .isEqualTo(2048);
    }

    @Test
    void a_flat_frame_is_capped_at_2048_and_keeps_its_own_aspect_ratio() throws Exception {
        String url = serve(jpeg(detailed(3000, 2000), 0.9f));

        BufferedImage stored = storedImage(service.ingest(UUID.randomUUID(), url, false));

        assertThat(stored.getWidth())
                .as("flat frames fill a viewport, not a sphere, so 2048 is enough")
                .isEqualTo(2048);
        assertThat(stored.getHeight())
                .as("a 3:2 frame must stay 3:2, not be squashed into the panorama's 2:1")
                .isEqualTo(1365);
    }

    @Test
    void a_source_smaller_than_the_cap_is_never_upscaled() throws Exception {
        String url = serve(jpeg(detailed(1600, 800), 0.9f));

        BufferedImage stored = storedImage(service.ingest(UUID.randomUUID(), url, true));

        assertThat(stored.getWidth())
                .as("upscaling invents no detail and only inflates storage")
                .isEqualTo(1600);
        assertThat(stored.getHeight()).isEqualTo(800);
    }

    // ---------------------------------------------------------------
    // Encoding quality -- the regression guard
    // ---------------------------------------------------------------

    /**
     * The test that would have caught the original bug. It fails if the encoder
     * loses its explicit quality param <em>or</em> if the harvester reverts to
     * fetching low-resolution sources, because either one drops the stored size
     * back under the floor.
     */
    @Test
    void a_stored_panorama_is_far_larger_than_the_default_encoder_would_produce() throws Exception {
        String url = serve(jpeg(detailed(4608, 2304), 0.9f));

        ImageAsset asset = service.ingest(UUID.randomUUID(), url, true).orElseThrow();
        byte[] stored = storage.get(asset.getStorageKey()).orElseThrow();

        assertThat(stored.length)
                .as("a 4096x2048 frame at quality 0.92 is megabytes; the old default-quality "
                        + "1024px encode produced 60-110 KB")
                .isGreaterThan(300 * 1024);
        assertThat(asset.getBytes())
                .as("the recorded size must match what was actually stored")
                .isEqualTo(stored.length);
    }

    // ---------------------------------------------------------------
    // Metadata stripping
    // ---------------------------------------------------------------

    @Test
    void gps_exif_present_in_the_source_is_absent_from_the_stored_image() throws Exception {
        byte[] source = withGpsExif(jpeg(detailed(2400, 1200), 0.9f));

        // Without this the test could pass against a source that never carried
        // EXIF at all, proving nothing about the strip.
        assertThat(indexOf(source, EXIF_SIGNATURE))
                .as("the fixture must actually contain EXIF for the strip to mean anything")
                .isNotNegative();

        String url = serve(source);
        ImageAsset asset = service.ingest(UUID.randomUUID(), url, true).orElseThrow();
        byte[] stored = storage.get(asset.getStorageKey()).orElseThrow();

        assertThat(indexOf(stored, EXIF_SIGNATURE))
                .as("re-encoding from the pixel buffer leaves GPS tags nowhere to live")
                .isEqualTo(-1);
        assertThat(indexOf(stored, new byte[]{(byte) 0xFF, (byte) 0xE1}))
                .as("no APP1 segment should survive; JPEG escapes 0xFF in scan data, "
                        + "so this sequence can only be a real marker")
                .isEqualTo(-1);
        assertThat(asset.isExifStripped()).isTrue();
    }

    // ---------------------------------------------------------------
    // Download ceiling
    // ---------------------------------------------------------------

    @Test
    void a_source_over_the_download_ceiling_is_skipped_rather_than_buffered() throws Exception {
        ImageAssetService bounded = newService(64 * 1024);
        String url = serve(jpeg(detailed(2400, 1200), 0.9f));

        Optional<ImageAsset> result = bounded.ingest(UUID.randomUUID(), url, true);

        assertThat(result)
                .as("an oversized original is one skipped image, not an OOM that "
                        + "takes the harvest batch with it")
                .isEmpty();
    }

    @Test
    void a_nonsensical_download_ceiling_is_rejected_at_construction() {
        assertThatThrownBy(() -> newService(0))
                .as("a bad ceiling must fail at startup, not as a silent 100% ingest "
                        + "failure rate that reads like a provider outage")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-download-bytes");
    }

    @Test
    void a_source_under_the_download_ceiling_still_ingests() throws Exception {
        ImageAssetService bounded = newService(THIRTY_MB);
        String url = serve(jpeg(detailed(1600, 800), 0.9f));

        assertThat(bounded.ingest(UUID.randomUUID(), url, true))
                .as("the ceiling must not reject ordinary imagery")
                .isPresent();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * {@code ingest} calls exactly one repository method, so the collaborator is a
     * proxy that answers {@code save} and refuses everything else. A hand-written
     * fake would be forty stubs for one call, and a mocking framework would tie
     * these tests to its JDK support -- the tradeoff {@code PoolMaintenanceControllerTest}
     * already calls out.
     */
    private ImageAssetService newService(long maxDownloadBytes) {
        ImageAssetRepository repository = (ImageAssetRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ImageAssetRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> args[0];
                    case "toString" -> "SaveOnlyImageAssetRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new ImageAssetService(storage, repository, maxDownloadBytes);
    }

    private BufferedImage storedImage(Optional<ImageAsset> ingested) throws IOException {
        ImageAsset asset = ingested.orElseThrow(() -> new AssertionError("ingest returned empty"));
        byte[] stored = storage.get(asset.getStorageKey()).orElseThrow();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored));

        assertThat(decoded).as("stored bytes must decode as a JPEG").isNotNull();
        assertThat(asset.getWidth()).isEqualTo(decoded.getWidth());
        assertThat(asset.getHeight()).isEqualTo(decoded.getHeight());
        return decoded;
    }

    /** Publish {@code body} on a fresh path and return its absolute URL. */
    private String serve(byte[] body) {
        String path = "/fixture-" + pathCounter.incrementAndGet();
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /**
     * Deterministic high-frequency content. Flat colour or a smooth gradient would
     * compress to almost nothing regardless of quality setting, which would make
     * the size-floor assertion meaningless; blocks of seeded noise give the
     * encoder real detail to preserve while staying well under the download cap.
     */
    private static BufferedImage detailed(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(20260725L);
        int block = 4;

        for (int y = 0; y < height; y += block) {
            for (int x = 0; x < width; x += block) {
                int colour = random.nextInt(0xFFFFFF);
                for (int dy = 0; dy < block && y + dy < height; dy++) {
                    for (int dx = 0; dx < block && x + dx < width; dx++) {
                        image.setRGB(x + dx, y + dy, colour);
                    }
                }
            }
        }
        return image;
    }

    private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * Splice a minimal EXIF APP1 segment carrying a GPS IFD in directly after the
     * SOI marker. Hand-built rather than pulled from a metadata library: the test
     * only needs bytes that are structurally skippable by a decoder and
     * unmistakably present to a byte search.
     */
    private static byte[] withGpsExif(byte[] jpeg) throws IOException {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8});           // big-endian, IFD0 at 8

        tiff.write(new byte[]{0, 1});                                   // IFD0: one entry
        tiff.write(new byte[]{(byte) 0x88, 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, 26}); // GPSInfo -> 26
        tiff.write(new byte[]{0, 0, 0, 0});                             // no next IFD

        tiff.write(new byte[]{0, 1});                                   // GPS IFD: one entry
        tiff.write(new byte[]{0, 1, 0, 2, 0, 0, 0, 2, 'N', 0, 0, 0});   // GPSLatitudeRef = "N"
        tiff.write(new byte[]{0, 0, 0, 0});                             // no next IFD

        byte[] payload = tiff.toByteArray();
        int segmentLength = 2 + EXIF_SIGNATURE.length + payload.length;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                                          // SOI
        out.write(new byte[]{(byte) 0xFF, (byte) 0xE1});                // APP1
        out.write(new byte[]{(byte) (segmentLength >> 8), (byte) segmentLength});
        out.write(EXIF_SIGNATURE);
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** First index of {@code needle} in {@code haystack}, or -1. */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
