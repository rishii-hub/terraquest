package dev.terraquest.game;

import dev.terraquest.config.SecurityConfig;
import dev.terraquest.identity.UserAccount;
import dev.terraquest.identity.UserAccounts;
import dev.terraquest.location.NoLocationsAvailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pool exhaustion must reach the client as a clean 503, not a 500 + stack trace.
 *
 * <p>A player who exhausts the panoramic pool -- the sampler cannot find five
 * distinct locations -- should see a sensible "try again later", so
 * {@code GameService} translates the sampler-seam
 * {@link NoLocationsAvailableException} into a {@code @ResponseStatus(503)}
 * {@code PoolExhaustedException}. This proves the mapping at the web layer.
 */
@WebMvcTest(GameController.class)
@Import({SecurityConfig.class, GameRoundErrorMappingTest.Stubs.class})
class GameRoundErrorMappingTest {

    @Autowired private MockMvc mvc;

    @Test
    void an_exhausted_pool_surfaces_as_503_not_500() throws Exception {
        UUID gameId = UUID.randomUUID();
        mvc.perform(get("/api/v1/games/{gameId}/rounds/{index}", gameId, 0)
                        .session(new MockHttpSession()))
                .andExpect(status().isServiceUnavailable());
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        GameService gameService() {
            return new ExhaustedGameService();
        }

        @Bean
        UserAccounts userAccounts() {
            return new SequentialGuests();
        }
    }

    /** Every round issuance hits an exhausted pool. */
    static class ExhaustedGameService extends GameService {
        ExhaustedGameService() {
            super(null, null, null, null, null);
        }

        @Override
        public RoundView issueRound(UUID gameId, UUID userId, int roundIndex) {
            throw new PoolExhaustedException(
                    new NoLocationsAvailableException("no distinct panoramic locations left"));
        }
    }

    /** Mints a guest so the game security chain authenticates the request. */
    static class SequentialGuests implements UserAccounts {
        private final AtomicInteger seq = new AtomicInteger();
        private final Map<UUID, UserAccount> byId = new ConcurrentHashMap<>();

        @Override
        public UserAccount createGuest() {
            int n = seq.incrementAndGet();
            UUID id = new UUID(0L, n);
            UserAccount account = new UserAccount(id, "guest-%06x".formatted(n), 0L);
            byId.put(id, account);
            return account;
        }

        @Override
        public Optional<UserAccount> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }
    }
}
