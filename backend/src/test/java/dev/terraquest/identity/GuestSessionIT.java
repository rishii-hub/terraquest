package dev.terraquest.identity;

import dev.terraquest.game.GameCreated;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session-identity contract end to end against a real Postgres: a guest row is
 * minted, bound to the session, and reused; the FK-bound game rows prove the same
 * user_id survives across requests in a session and differs across sessions; {@code
 * /api/v1/me} reflects the session identity and survives a refresh; and the admin
 * chain stays closed to a game session.
 *
 * <p>Starting a game only inserts a {@code game} row (locations are sampled lazily on
 * the first round), so this needs no seeded pool. Skips without Docker like the other
 * ITs and runs for real in CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class GuestSessionIT {

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void the_same_session_owns_both_games_and_a_fresh_session_does_not() {
        // First game with no cookie: the server mints a guest and sets JSESSIONID.
        ResponseEntity<GameCreated> first = startGame(new HttpHeaders());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String cookie = sessionCookie(first);

        // Second game carrying that cookie: same session.
        ResponseEntity<GameCreated> second = startGame(withCookie(cookie));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID firstOwner = ownerOf(first.getBody().gameId());
        UUID secondOwner = ownerOf(second.getBody().gameId());
        assertThat(firstOwner).isEqualTo(secondOwner);

        // A fresh session (no cookie) is a different player.
        ResponseEntity<GameCreated> other = startGame(new HttpHeaders());
        assertThat(ownerOf(other.getBody().gameId())).isNotEqualTo(firstOwner);
    }

    @Test
    void me_reports_the_session_identity_and_survives_a_refresh() {
        ResponseEntity<GameCreated> game = startGame(new HttpHeaders());
        String cookie = sessionCookie(game);

        ResponseEntity<UserAccount> me = rest.exchange(
                url("/api/v1/me"), HttpMethod.GET, new HttpEntity<>(withCookie(cookie)), UserAccount.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().username()).startsWith("guest-");
        assertThat(me.getBody().xp()).isZero();
        // The /me id is the same user the game row is owned by, across a second call.
        assertThat(me.getBody().id()).isEqualTo(ownerOf(game.getBody().gameId()));

        ResponseEntity<UserAccount> refreshed = rest.exchange(
                url("/api/v1/me"), HttpMethod.GET, new HttpEntity<>(withCookie(cookie)), UserAccount.class);
        assertThat(refreshed.getBody().id()).isEqualTo(me.getBody().id());
    }

    @Test
    void a_game_session_cannot_reach_admin() {
        ResponseEntity<GameCreated> game = startGame(new HttpHeaders());
        String cookie = sessionCookie(game);

        ResponseEntity<String> admin = rest.exchange(
                url("/api/v1/admin/harvest-stats"), HttpMethod.GET,
                new HttpEntity<>(withCookie(cookie)), String.class);

        // The security property is that a guest session is denied admin access -- the
        // guest cookie confers nothing (an admin request with no cookie is denied
        // identically). The real servlet container denies with 403; the WebMvc slice
        // returns 401 for the same case. Assert the denial, not the exact code. A 4xx
        // denial carries no stats body (getBody() is in fact null here), so the status
        // is itself the proof nothing leaked.
        assertThat(admin.getStatusCode().is4xxClientError()).isTrue();
        assertThat(admin.getBody()).isNull();
    }

    private ResponseEntity<GameCreated> startGame(HttpHeaders headers) {
        return rest.exchange(url("/api/v1/games"), HttpMethod.POST,
                new HttpEntity<>(headers), GameCreated.class);
    }

    private UUID ownerOf(UUID gameId) {
        return jdbc.queryForObject("select user_id from game where id = ?", UUID.class, gameId);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String sessionCookie(ResponseEntity<?> response) {
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("Set-Cookie").contains("JSESSIONID");
        return setCookie.split(";", 2)[0];
    }

    private static HttpHeaders withCookie(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        return headers;
    }
}
