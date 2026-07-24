package dev.terraquest.config;

import dev.terraquest.game.AuthenticatedUser;
import dev.terraquest.identity.UserAccounts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every game/identity request an anonymous, session-bound player.
 *
 * <p>On the first request of a session it mints a guest {@code app_user} (via
 * {@link UserAccounts#createGuest()}) and stashes the id on the HTTP session; every
 * later request in the same session re-reads that id. Either way it installs an
 * {@link AuthenticatedUser} principal so {@code @AuthenticationPrincipal} resolves in
 * the controllers -- the whole reason the game endpoints were previously uncallable.
 *
 * <p>The {@code Authentication} is request-scoped, not persisted to a
 * {@code SecurityContextRepository}: the durable identity is the session attribute,
 * and the principal is re-derived from it on each request. That keeps session state to
 * a single UUID and sidesteps serializing a security context.
 *
 * <p>Not a Spring bean by design. Registering a {@code Filter} bean would make Boot
 * add it to the global servlet chain (every route, including admin); instead
 * {@code SecurityConfig} instantiates it inline in the game filter chain, so it runs
 * only for {@code /api/v1/games/**} and {@code /api/v1/me}.
 */
public class GuestAuthenticationFilter extends OncePerRequestFilter {

    static final String SESSION_USER_ID = "terraquest.userId";

    private final UserAccounts accounts;

    public GuestAuthenticationFilter(UserAccounts accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        HttpSession session = request.getSession(true);
        UUID userId = (UUID) session.getAttribute(SESSION_USER_ID);
        if (userId == null) {
            userId = accounts.createGuest().id();
            session.setAttribute(SESSION_USER_ID, userId);
        }

        var authentication = new GuestAuthenticationToken(new AuthenticatedUser(userId));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    /**
     * A minimal authenticated token whose principal is an {@link AuthenticatedUser}.
     * No authorities: a guest may reach the game endpoints (which {@code permitAll})
     * but nothing role-gated, so the admin chain stays closed to it.
     */
    private static final class GuestAuthenticationToken extends AbstractAuthenticationToken {

        private final AuthenticatedUser principal;

        GuestAuthenticationToken(AuthenticatedUser principal) {
            super(AuthorityUtils.NO_AUTHORITIES);
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
