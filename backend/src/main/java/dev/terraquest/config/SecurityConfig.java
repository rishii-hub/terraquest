package dev.terraquest.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security: protect the admin endpoints, and change nothing else.
 *
 * <p>The only requirement in this PR is to keep {@code /api/v1/admin/**} behind a
 * credential. HTTP Basic against one config-supplied admin user does that with no
 * external identity provider. Real player authentication (OAuth/JWT) is a later
 * phase and out of scope here.
 *
 * <p><b>Strictly additive.</b> The admin chain matches {@code /api/v1/admin/**}
 * only. A second, lower-priority chain leaves every other route
 * {@code authenticated()} exactly as the framework default already had it, so the
 * game endpoints' access is unchanged and the admin credential cannot open them.
 *
 * <p>Security types stay in this edge package; {@code ArchitectureTest}'s
 * {@code security_types_stay_at_the_edge} rule (which covers game, location,
 * progression and scoring) is unaffected.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/admin/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurity(HttpSecurity http) throws Exception {
        // Everything outside /api/v1/admin keeps its prior posture: authenticated,
        // with no auth mechanism wired here, so the admin Basic credential is
        // scoped to the admin chain and does not unlock game routes.
        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * The single admin principal, from config. The raw password is encoded at
     * startup so no plaintext is stored in memory; if it is left blank the admin
     * user simply cannot authenticate (no weak default credential ships).
     */
    @Bean
    InMemoryUserDetailsManager adminUserDetails(
            @Value("${terraquest.admin.username:admin}") String username,
            @Value("${terraquest.admin.password:}") String password,
            PasswordEncoder encoder) {
        UserDetails admin = User.withUsername(username)
                .password(encoder.encode(password))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
