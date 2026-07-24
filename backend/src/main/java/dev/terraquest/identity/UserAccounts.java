package dev.terraquest.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * The edge's view of player accounts: mint an anonymous guest, or look one up.
 *
 * <p>An interface, like {@code HarvestStatsService} and {@code PoolMaintenanceService},
 * so the security filter and {@code /me} controller depend on a port rather than the
 * JPA implementation -- which also lets the web-slice test supply a real in-memory
 * stub instead of a mock.
 */
public interface UserAccounts {

    /**
     * Create a fresh anonymous player: a generated {@code guest-xxxxxx} username, no
     * email and no OAuth link. The row is committed before this returns, so a caller
     * can immediately reference the new id as a foreign key.
     */
    UserAccount createGuest();

    Optional<UserAccount> findById(UUID id);
}
