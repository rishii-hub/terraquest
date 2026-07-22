package dev.terraquest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <p>{@code @EnableScheduling} is what actually runs {@code LocationHarvester};
 * the harvester's {@code @Scheduled} batch is a no-op without it.
 */
@SpringBootApplication
@EnableScheduling
public class TerraQuestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TerraQuestApplication.class, args);
    }
}
