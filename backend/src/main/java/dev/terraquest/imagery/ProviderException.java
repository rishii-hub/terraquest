package dev.terraquest.imagery;

/**
 * Thrown by an {@link ImageryProvider} when a probe cannot be completed.
 *
 * <p>Carries the one distinction the harvester needs but must not derive for
 * itself: whether retrying could plausibly succeed. A malformed or rejected
 * request -- an HTTP 4xx other than 429 -- is permanent; retrying burns rate
 * limit on the same failure and, before this type existed, cost a real
 * harvest 50 futile retries on an empty token. A rate limit (429), a timeout,
 * a connection error or a server fault (5xx) is transient and worth another
 * attempt.
 *
 * <p>Classification is the adapter's job. HTTP status codes are a Mapillary
 * detail that must never reach location code; the harvester sees only
 * {@link #isRetryable()}.
 */
public class ProviderException extends RuntimeException {

    private final boolean retryable;

    public ProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * True if another attempt at this probe may succeed (5xx, connection
     * timeout, 429); false if the request was rejected permanently and a retry
     * would only repeat the same rejection.
     */
    public boolean isRetryable() {
        return retryable;
    }
}
