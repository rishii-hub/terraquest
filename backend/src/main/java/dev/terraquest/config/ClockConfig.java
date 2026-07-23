package dev.terraquest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application {@link Clock}. Injected wherever time is read (the harvester
 * compares image capture dates against "now") so those components can be tested
 * against a fixed clock instead of {@code Instant.now()} -- which is exactly what
 * {@code LocationHarvesterTest} relies on.
 *
 * <p>UTC to match {@code hibernate.jdbc.time_zone=UTC}; the app reasons about
 * time in one zone everywhere.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
