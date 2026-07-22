package dev.terraquest.game;

import java.util.UUID;

/**
 * The authenticated caller, as the controller sees it: a bare {@code UserId} and
 * nothing else.
 *
 * <p>Deliberately not a Spring Security type. ADR 0003 chose to keep security
 * types at the edge and hand domain code only a {@code UserId}; the resolver
 * that turns a JWT or session into one of these lives in the (out-of-scope)
 * security config. ArchitectureTest forbids {@code org.springframework.security}
 * from leaking past the controller.
 */
public record AuthenticatedUser(UUID id) {
}
