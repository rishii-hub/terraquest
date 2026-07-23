package dev.terraquest.imagery.mapillary;

import com.fasterxml.jackson.databind.JsonNode;
import dev.terraquest.imagery.ProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Thin client over the Mapillary Graph API.
 *
 * Hard constraint (formalized 2026-01-16): bbox queries against /images must be
 * strictly smaller than 0.01 degrees square. We therefore never search a region
 * directly — we probe individual candidate points with a small window and let the
 * harvester walk a seed list. See LocationHarvester.
 *
 * <p>Created only when {@code terraquest.harvest.enabled} is true, the same gate
 * as the {@link dev.terraquest.location.LocationHarvester} it exists to serve:
 * the round path never touches Mapillary (attribution is stored on the location),
 * so a deployment that only serves rounds needs neither this client nor a token.
 * When the gate is on, a blank {@code MAPILLARY_ACCESS_TOKEN} fails construction
 * immediately rather than surfacing as a 4xx on every probe.
 */
@Component
@ConditionalOnProperty(name = "terraquest.harvest.enabled", havingValue = "true", matchIfMissing = false)
public class MapillaryClient {

    /** Slightly under the API ceiling of 0.01 to avoid boundary rejections. */
    public static final double MAX_BBOX_DEGREES = 0.0098;

    private static final String FIELDS = String.join(",",
            "id", "geometry", "compass_angle", "captured_at",
            "is_pano", "sequence", "creator", "thumb_1024_url");

    private final WebClient client;
    private final String accessToken;

    public MapillaryClient(WebClient.Builder builder,
                           @org.springframework.beans.factory.annotation.Value("${mapillary.access-token}") String accessToken) {
        // Fail fast, matching R2Configured's rule that a present-but-empty value
        // does not count as configured. Discovering a blank token here beats
        // discovering it 50 rejected probes into a harvest.
        if (!StringUtils.hasText(accessToken)) {
            throw new IllegalStateException(
                    "MAPILLARY_ACCESS_TOKEN is missing or blank. The harvester cannot probe "
                            + "Mapillary without a token: set MAPILLARY_ACCESS_TOKEN, or turn the "
                            + "harvester off with HARVEST_ENABLED=false.");
        }
        this.client = builder.baseUrl("https://graph.mapillary.com").build();
        this.accessToken = accessToken;
    }

    /**
     * Search a bounding box for street-level imagery. The SPI adapter keeps the
     * box under {@code MAX_BBOX_DEGREES} square; the API rejects anything at or
     * above 0.01 degrees.
     *
     * @param limit max images to return; keep small, we only need a handful per point
     */
    public List<MapillaryImage> searchBbox(double minLat, double minLon,
                                           double maxLat, double maxLon, int limit) {
        // Mapillary expects bbox as west,south,east,north.
        String bbox = "%f,%f,%f,%f".formatted(minLon, minLat, maxLon, maxLat);

        JsonNode body = get(uri -> uri.path("/images")
                .queryParam("access_token", accessToken)
                .queryParam("fields", FIELDS)
                .queryParam("bbox", bbox)
                .queryParam("limit", limit)
                .build());

        if (body == null || !body.has("data")) {
            return List.of();
        }

        List<MapillaryImage> out = new ArrayList<>();
        for (JsonNode node : body.get("data")) {
            MapillaryImage img = parse(node);
            if (img != null) {
                out.add(img);
            }
        }
        return out;
    }

    /**
     * Resolve a single field for one image (e.g. a sized thumbnail URL). Returns
     * empty if the image or field is absent rather than throwing, so a missing
     * URL degrades to skipping that image rather than failing the batch.
     */
    public Optional<String> fetchField(String imageId, String field) {
        JsonNode body = get(uri -> uri.path("/" + imageId)
                .queryParam("access_token", accessToken)
                .queryParam("fields", field)
                .build());

        if (body == null || !body.has(field) || body.get(field).isNull()) {
            return Optional.empty();
        }
        return Optional.of(body.get(field).asText());
    }

    /**
     * Issue a GET and translate any failure into a {@link ProviderException}
     * carrying the retryable flag. This is where HTTP status codes stop: nothing
     * above this method sees them, so the harvester's retry policy is expressed
     * purely as "retryable or not", never as a status comparison.
     */
    private JsonNode get(Function<UriBuilder, URI> uriFunction) {
        try {
            return client.get()
                    .uri(uriFunction)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
        } catch (WebClientResponseException e) {
            // The server answered with a status. 4xx (other than 429) is a
            // permanent client error -- retrying repeats the same rejection.
            throw new ProviderException(
                    "Mapillary responded HTTP " + e.getStatusCode().value(),
                    isRetryable(e.getStatusCode().value()), e);
        } catch (RuntimeException e) {
            // No HTTP response at all: connection reset, DNS failure, or the
            // 15s read timeout tripping. All transient by nature -- retry.
            throw new ProviderException("Mapillary request failed: " + e.getMessage(), true, e);
        }
    }

    /**
     * Whether an HTTP failure from Mapillary is worth retrying. A 429 (rate
     * limit) and any 5xx (server fault) are transient; every other 4xx is a
     * permanent client error -- a malformed request, a bad token, a missing
     * image -- where a retry only repeats the rejection. Package-private so the
     * classification that gates the harvester's retry loop is unit-testable
     * without a live API.
     */
    static boolean isRetryable(int httpStatus) {
        if (httpStatus == 429) {
            return true;
        }
        return httpStatus >= 500 && httpStatus < 600;
    }

    private MapillaryImage parse(JsonNode n) {
        JsonNode coords = n.path("geometry").path("coordinates");
        if (!coords.isArray() || coords.size() < 2) {
            return null;
        }
        JsonNode creator = n.path("creator");

        return new MapillaryImage(
                n.path("id").asText(),
                n.path("sequence").asText(null),
                coords.get(1).asDouble(),   // lat
                coords.get(0).asDouble(),   // lon
                n.has("compass_angle") ? (float) n.get("compass_angle").asDouble() : null,
                n.path("is_pano").asBoolean(false),
                n.has("captured_at") ? Instant.ofEpochMilli(n.get("captured_at").asLong()) : null,
                creator.path("username").asText(null),
                creator.path("id").asText(null)
        );
    }
}
