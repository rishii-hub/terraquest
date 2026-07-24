package dev.terraquest.admin;

import dev.terraquest.admin.HarvestStats.AssetStats;
import dev.terraquest.admin.HarvestStats.CandidateStats;
import dev.terraquest.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin endpoint is credential-gated. This proves the security contract the
 * stats endpoint depends on: no credential is rejected, the configured admin
 * credential is accepted, and a wrong password is rejected.
 *
 * <p>The service is stubbed with a real subclass rather than a mock so the test
 * exercises only the web + security layers and stays independent of the mocking
 * framework's JDK support.
 */
@WebMvcTest(HarvestStatsController.class)
@Import({SecurityConfig.class, HarvestStatsControllerTest.StubConfig.class})
@TestPropertySource(properties = {
        "terraquest.admin.username=admin",
        "terraquest.admin.password=s3cret"
})
class HarvestStatsControllerTest {

    @Autowired private MockMvc mvc;

    @Test
    void unauthenticated_requests_are_rejected() throws Exception {
        mvc.perform(get("/api/v1/admin/harvest-stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void a_wrong_password_is_rejected() throws Exception {
        mvc.perform(get("/api/v1/admin/harvest-stats").with(httpBasic("admin", "nope")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void the_admin_credential_is_accepted() throws Exception {
        mvc.perform(get("/api/v1/admin/harvest-stats").with(httpBasic("admin", "s3cret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.panoFloor").value(15));
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        HarvestStatsService harvestStatsService() {
            return () -> new HarvestStats(
                    List.of(), 15, 0,
                    new CandidateStats(0, 0, 0, 0),
                    new AssetStats(0, 0, 0));
        }

        // SecurityConfig's game chain needs a UserAccounts; the admin chain under test
        // never touches it, so a never-called stub satisfies the context.
        @Bean
        dev.terraquest.identity.UserAccounts userAccounts() {
            return new NoopUserAccounts();
        }
    }

    static final class NoopUserAccounts implements dev.terraquest.identity.UserAccounts {
        @Override
        public dev.terraquest.identity.UserAccount createGuest() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<dev.terraquest.identity.UserAccount> findById(java.util.UUID id) {
            return java.util.Optional.empty();
        }
    }
}
