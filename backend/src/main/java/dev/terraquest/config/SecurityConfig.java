package dev.terraquest.config;

import dev.terraquest.identity.UserAccounts;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Three ordered filter chains: admin credentials, anonymous game sessions, and an
 * authenticated catch-all -- each matching a disjoint slice of the API.
 *
 * <p><b>Admin ({@code @Order(1)}).</b> {@code /api/v1/admin/**} behind HTTP Basic and
 * {@code ROLE_ADMIN}, one config-supplied credential, no external identity provider.
 * Unchanged by this PR.
 *
 * <p><b>Game ({@code @Order(2)}).</b> {@code /api/v1/games/**} and {@code /api/v1/me}
 * get an anonymous, session-bound player from {@link GuestAuthenticationFilter}; the
 * chain {@code permitAll}s because that filter, not a credential, supplies identity.
 * Real player authentication (OAuth/JWT) is a later phase and out of scope here.
 *
 * <p><b>Default ({@code @Order(3)}).</b> Every other route stays {@code authenticated()}
 * exactly as the framework default already had it, so nothing else opened up and the
 * admin credential cannot reach the game or default chains.
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
    SecurityFilterChain gameSecurity(HttpSecurity http,
                                     UserAccounts accounts,
                                     CorsConfigurationSource corsConfigurationSource) throws Exception {
        http.securityMatcher("/api/v1/games/**", "/api/v1/me")
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // The guest filter installs an AuthenticatedUser for every request, so
                // there is no credential to check here -- authorization is "you have a
                // session", which this filter guarantees.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // TODO(frontend PR): re-enable CSRF with a cookie-based token repository
                // (CookieCsrfTokenRepository -> XSRF-TOKEN cookie / X-XSRF-TOKEN header)
                // and document how the SPA sends it. A guest session IS the player's
                // account -- it holds match history, stats and XP -- so a forged
                // cross-site start-game/guess is a real state change and CSRF is genuinely
                // needed. It is off now only because no frontend exists yet to define and
                // exercise the token exchange; this must be enabled before any deployment.
                .csrf(csrf -> csrf.disable())
                .addFilterBefore(new GuestAuthenticationFilter(accounts),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultSecurity(HttpSecurity http) throws Exception {
        // Everything outside the admin and game chains keeps its prior posture:
        // authenticated, with no auth mechanism wired here, so the admin Basic
        // credential is scoped to the admin chain and does not unlock other routes.
        http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * CORS for the browser SPA, which runs on a different origin in dev (the Vite
     * server, typically {@code http://localhost:5173}). Origins come from
     * {@code terraquest.cors.allowed-origins} -- never a hardcoded literal -- and
     * credentials are allowed so the session cookie rides along with game requests.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${terraquest.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", config);
        return source;
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
