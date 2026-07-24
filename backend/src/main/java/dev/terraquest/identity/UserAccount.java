package dev.terraquest.identity;

import java.util.UUID;

/**
 * A player account as the edge exposes it: identity and progression, nothing
 * about credentials.
 *
 * <p>Deliberately not the {@link AppUser} entity. Handing the JPA entity out of
 * the identity module would leak the {@code password_hash} / {@code oauth_*}
 * columns into the API surface and couple callers to Hibernate; this record is
 * the whole of what {@code GET /api/v1/me} needs.
 */
public record UserAccount(UUID id, String username, long xp) {
}
