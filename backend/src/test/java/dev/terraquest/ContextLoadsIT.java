package dev.terraquest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The check no other test performs: the whole application context boots.
 *
 * <p>CI compiles, runs the architecture rules and the JPA/web test slices, and
 * applies migrations -- but nothing ever started the full application. That gap
 * is exactly how two missing beans (the storage provider and a {@code Clock})
 * shipped undetected: every slice test supplied them itself. A context-load
 * assertion is the cheapest guard against the next such bean.
 *
 * <p>No R2 credentials are set, so this boots the {@code LocalFilesystemStorageProvider}
 * path -- the configuration most contributors actually run. PostGIS is real
 * because Flyway migrates and Hibernate {@code validate} runs at startup against
 * the true schema; a mock would prove nothing. Skips without Docker like the
 * other ITs, and runs for real in CI.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ContextLoadsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
    }

    @Test
    void context_loads() {
        // Intentionally empty. Success is the context having started: if any bean
        // cannot be constructed, @SpringBootTest fails before this method runs.
    }
}
