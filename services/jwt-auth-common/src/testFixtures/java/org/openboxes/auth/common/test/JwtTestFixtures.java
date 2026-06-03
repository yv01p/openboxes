package org.openboxes.auth.common.test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.openboxes.auth.common.JwtService;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/** Shared JWT/cookie test helper for service integration tests (Phase 6.1 RC-61). */
public final class JwtTestFixtures {
    private JwtTestFixtures() {}

    public static final String TEST_SECRET = "test-secret-32-chars-minimum-for-hs256-key";

    /** A valid signed token: subject "test-user", role ROLE_BROWSER, 1h expiry, HMAC over TEST_SECRET. */
    public static String validToken() {
        var key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
            .subject("test-user")
            .claim("roles", List.of("ROLE_BROWSER"))
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 3600_000L))
            .signWith(key)
            .compact();
    }

    /** The valid token wrapped in the auth cookie (name from JwtService.COOKIE_NAME). */
    public static Cookie authCookie() {
        return new Cookie(JwtService.COOKIE_NAME, validToken());
    }
}
