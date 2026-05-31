package org.openboxes.auth.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

// @AutoConfiguration fires before user-defined @Configuration classes by default,
// which is sufficient for our consumers (all 5 services have a user-defined
// SecurityConfig that injects the filter bean via @Autowired). No explicit
// ordering attribute is needed.
@AutoConfiguration
@ConditionalOnProperty(name = "openboxes.jwt.secret")
public class JwtAuthAutoConfiguration {

    @Bean
    public JwtService jwtService(@Value("${openboxes.jwt.secret}") String secret) {
        return new JwtService(secret);
    }

    @Bean
    public JwtCookieAuthFilter jwtCookieAuthFilter(JwtService jwtService) {
        return new JwtCookieAuthFilter(jwtService);
    }
}
