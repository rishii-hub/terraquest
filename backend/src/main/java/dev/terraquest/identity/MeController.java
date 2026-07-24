package dev.terraquest.identity;

import dev.terraquest.game.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Who am I" for the SPA: the frontend calls this to show who it is playing as and
 * to confirm the session survived a refresh.
 *
 * <p>Lives in the edge module, so using {@code @AuthenticationPrincipal} here is
 * fine -- the ArchitectureTest rule that keeps Spring Security out of the domain
 * covers {@code game/location/progression/scoring}, not {@code identity}. The
 * {@link AuthenticatedUser} principal is supplied by the guest-session filter.
 */
@RestController
public class MeController {

    private final UserAccounts accounts;

    public MeController(UserAccounts accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/api/v1/me")
    public UserAccount me(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            // The filter binds an identity to every /api/v1/me request, so this only
            // happens if the endpoint is reached without it -- treat as unauthenticated.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return accounts.findById(user.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
