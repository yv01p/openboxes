package org.openboxes.document.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Temporary {@code permitAll()} configuration for Task 5 live-probe. Without an explicit
 * SecurityFilterChain bean, Spring Boot 3 auto-configures HTTP Basic with a random password
 * printed at startup — unusable for probes and for Grails-side {@code DocumentClient} calls
 * (Task 8b) that route via nginx.
 *
 * <p>Task 7 replaces this with JWT cookie validation per spec §4.4. See the deferred follow-ups
 * table (T2-I2, T2-M5) in {@code 2026-05-26-phase-1-document-slice-implementation-plan.md}.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }
}
