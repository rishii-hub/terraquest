package dev.terraquest.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persistence for {@link AppUser}. Package-private: nothing outside {@code identity}
 * touches the user table directly -- callers go through {@link UserAccounts}, which
 * keeps the entity and its credential columns off the API surface.
 */
interface UserRepository extends JpaRepository<AppUser, UUID> {

    boolean existsByUsername(String username);
}
