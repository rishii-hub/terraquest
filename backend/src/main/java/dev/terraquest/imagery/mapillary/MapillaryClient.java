package dev.terraquest.imagery.mapillary;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin client over the Mapillary Graph API.
 *
 * Hard constraint (formalized 2026-01-16): bbox queries against /images must be
 * strictly smaller than 0.01 degrees square. We therefore never search a region
 * directly — we probe individual candidate points with a small window and let the
 * harvester walk a seed list. See LocationHarvester.
 */
@Component
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

        JsonNode body = client.get()
                .uri(uri -> uri.path("/images")
                        .queryParam("access_token", accessToken)
                        .queryParam("fields", FIELDS)
                        .queryParam("bbox", bbox)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .block();

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
        JsonNode body = client.get()
                .uri(uri -> uri.path("/" + imageId)
                        .queryParam("access_token", accessToken)
                        .queryParam("fields", field)
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .block();

        if (body == null || !body.has(field) || body.get(field).isNull()) {
            return Optional.empty();
        }
        return Optional.of(body.get(field).asText());
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
