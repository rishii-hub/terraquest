package dev.terraquest.game;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * A guess submission.
 *
 * <p>Client-supplied duration is intentionally absent. Elapsed time is computed
 * server-side from the round's issuance timestamp; accepting it from the client
 * would make every timed mode forgeable.
 */
public record GuessRequest(
        @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") double lon
) {
}
