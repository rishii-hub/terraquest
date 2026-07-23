package dev.terraquest.imagery.mapillary;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two bugs a live harvest exposed, pinned as unit tests: a blank token that
 * should have stopped startup, and 4xx failures that were retried as if transient.
 *
 * <p>No network and no Spring -- the classification and the token guard are both
 * plain code, and keeping them testable in isolation is the whole reason the
 * retryable decision lives in the adapter rather than leaking status codes into
 * the harvester.
 */
class MapillaryClientTest {

    // ---------------------------------------------------------------
    // Fail fast on a blank token (matches R2Configured's hasText rule)
    // ---------------------------------------------------------------

    @Test
    void a_blank_token_is_refused_at_construction() {
        assertThatThrownBy(() -> new MapillaryClient(WebClient.builder(), "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAPILLARY_ACCESS_TOKEN");
    }

    @Test
    void an_empty_token_is_refused_at_construction() {
        assertThatThrownBy(() -> new MapillaryClient(WebClient.builder(), ""))
                .as("present-but-empty must not count as configured")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_present_token_constructs_cleanly() {
        assertThatCode(() -> new MapillaryClient(WebClient.builder(), "MLY|token|abc"))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------
    // 4xx is permanent; 5xx / 429 are transient
    // ---------------------------------------------------------------

    @Test
    void client_errors_other_than_rate_limit_are_not_retryable() {
        assertThat(MapillaryClient.isRetryable(400)).as("400 Bad Request").isFalse();
        assertThat(MapillaryClient.isRetryable(401)).as("401 Unauthorized").isFalse();
        assertThat(MapillaryClient.isRetryable(403)).as("403 Forbidden").isFalse();
        assertThat(MapillaryClient.isRetryable(404)).as("404 Not Found").isFalse();
    }

    @Test
    void rate_limiting_is_retryable() {
        assertThat(MapillaryClient.isRetryable(429)).as("429 Too Many Requests").isTrue();
    }

    @Test
    void server_errors_are_retryable() {
        assertThat(MapillaryClient.isRetryable(500)).isTrue();
        assertThat(MapillaryClient.isRetryable(502)).isTrue();
        assertThat(MapillaryClient.isRetryable(503)).isTrue();
    }
}
