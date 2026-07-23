package dev.terraquest.admin;

import dev.terraquest.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reset endpoint mutates the pool, so it must sit behind the same admin
 * credential as the stats read. This proves the security contract (no credential
 * rejected, admin credential accepted) and that the reset count is returned --
 * and, since the admin chain disables CSRF, that a Basic-authed POST needs no
 * token.
 *
 * <p>The service is stubbed with a real lambda rather than a mock so the test
 * exercises only the web + security layers and stays independent of the mocking
 * framework's JDK support.
 */
@WebMvcTest(PoolMaintenanceController.class)
@Import({SecurityConfig.class, PoolMaintenanceControllerTest.StubConfig.class})
@TestPropertySource(properties = {
        "terraquest.admin.username=admin",
        "terraquest.admin.password=s3cret"
})
class PoolMaintenanceControllerTest {

    @Autowired private MockMvc mvc;

    @Test
    void unauthenticated_reset_is_rejected() throws Exception {
        mvc.perform(post("/api/v1/admin/candidates/reset-exhausted"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_wrong_password_is_rejected() throws Exception {
        mvc.perform(post("/api/v1/admin/candidates/reset-exhausted").with(httpBasic("admin", "nope")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void the_admin_credential_resets_and_gets_the_count_back() throws Exception {
        mvc.perform(post("/api/v1/admin/candidates/reset-exhausted").with(httpBasic("admin", "s3cret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value(42));
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        PoolMaintenanceService poolMaintenanceService() {
            return () -> 42;
        }
    }
}
