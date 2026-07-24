package dev.terraquest.game;

import dev.terraquest.config.SecurityConfig;
import dev.terraquest.identity.UserAccount;
import dev.terraquest.identity.UserAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The session-identity contract at the web + security layer, without a database.
 *
 * <p>Proves the three things the guest filter promises: one session is one player
 * across requests, a different session is a different player, and a guest session
 * cannot reach the admin chain. The {@code GameService} is a recording subclass and
 * {@code UserAccounts} an in-memory stub, so the test needs neither Postgres nor the
 * mocking framework (which does not support the local JDK).
 */
@WebMvcTest(GameController.class)
@Import({SecurityConfig.class, GameSessionAuthTest.Stubs.class})
@TestPropertySource(properties = {
        "terraquest.admin.username=admin",
        "terraquest.admin.password=s3cret"
})
class GameSessionAuthTest {

    @Autowired private MockMvc mvc;
    @Autowired private RecordingGameService games;

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        // The recording service is a context singleton shared across methods.
        games.userIds.clear();
    }

    @Test
    void the_same_session_yields_the_same_user_across_rounds() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/api/v1/games").session(session)).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/games").session(session)).andExpect(status().isCreated());

        assertThat(games.userIds).hasSize(2);
        assertThat(games.userIds.get(0)).isEqualTo(games.userIds.get(1));
    }

    @Test
    void a_different_session_yields_a_different_user() throws Exception {
        mvc.perform(post("/api/v1/games").session(new MockHttpSession())).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/games").session(new MockHttpSession())).andExpect(status().isCreated());

        assertThat(games.userIds).hasSize(2);
        assertThat(games.userIds.get(0)).isNotEqualTo(games.userIds.get(1));
    }

    @Test
    void an_anonymous_game_session_cannot_reach_admin() throws Exception {
        mvc.perform(get("/api/v1/admin/harvest-stats").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_session_that_already_played_still_cannot_reach_admin() throws Exception {
        // Reproduces the cross-chain scenario: the guest identity minted on the game
        // chain must not bleed into the admin chain via the shared session -- an admin
        // request without admin credentials must still be challenged (401), never
        // treated as an authenticated-but-forbidden caller (403).
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/games").session(session)).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/admin/harvest-stats").session(session))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        RecordingGameService gameService() {
            return new RecordingGameService();
        }

        @Bean
        UserAccounts userAccounts() {
            return new SequentialGuests();
        }
    }

    /** Captures the userId every {@code startClassic} is called with. */
    static class RecordingGameService extends GameService {
        final List<UUID> userIds = new CopyOnWriteArrayList<>();

        RecordingGameService() {
            super(null, null, null, null, null);
        }

        @Override
        public Game startClassic(UUID userId) {
            userIds.add(userId);
            return Game.builder().userId(userId).mode(GameMode.CLASSIC).roundCount(5).build();
        }
    }

    /** Hands out a fresh, distinct guest on each call; deterministic ids for assertions. */
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
