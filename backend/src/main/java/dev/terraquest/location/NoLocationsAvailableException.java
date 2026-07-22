package dev.terraquest.location;

/**
 * The pool could not satisfy a sampling request -- a regional mode with thin
 * coverage can legitimately exhaust it. Lives in {@code location} so the sampler
 * seam throws without the game module owning the failure type.
 */
public class NoLocationsAvailableException extends RuntimeException {

    public NoLocationsAvailableException(String message) {
        super(message);
    }
}
