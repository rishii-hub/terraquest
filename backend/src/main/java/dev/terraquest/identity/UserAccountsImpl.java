package dev.terraquest.identity;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

/**
 * Mints and looks up player accounts against the {@code app_user} table.
 *
 * <p>A guest gets a {@code guest-xxxxxx} username (six random hex chars, ~16M space),
 * a null email and no OAuth link -- the schema permits all of that, and a later OAuth
 * PR can claim the row by filling those columns in rather than migrating game history.
 */
@Service
class UserAccountsImpl implements UserAccounts {

    /** A pre-check plus a handful of retries makes a suffix collision a non-event. */
    private static final int MAX_ALLOCATION_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;

    UserAccountsImpl(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserAccount createGuest() {
        // Deliberately not @Transactional: each save() runs in its own transaction, so a
        // lost race on the unique username throws cleanly and the retry starts fresh
        // rather than reusing a rollback-marked transaction.
        for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
            String username = "guest-" + randomSuffix();
            if (users.existsByUsername(username)) {
                continue;
            }
            try {
                return toAccount(users.save(new AppUser(username, null)));
            } catch (DataIntegrityViolationException raced) {
                // Two sessions drew the same suffix at once; draw another.
            }
        }
        throw new IllegalStateException(
                "Could not allocate a unique guest username after "
                        + MAX_ALLOCATION_ATTEMPTS + " attempts");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UUID id) {
        return users.findById(id).map(UserAccountsImpl::toAccount);
    }

    /** Six lowercase hex chars, e.g. {@code a4f2c1} -> {@code guest-a4f2c1}. */
    private String randomSuffix() {
        return String.format("%06x", random.nextInt(0x1000000));
    }

    private static UserAccount toAccount(AppUser user) {
        return new UserAccount(user.getId(), user.getUsername(), user.getXp());
    }
}
