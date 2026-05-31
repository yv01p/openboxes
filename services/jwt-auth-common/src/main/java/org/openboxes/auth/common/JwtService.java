package org.openboxes.auth.common;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

// No @Service — bean is declared in JwtAuthAutoConfiguration so consumers
// can override or @ConditionalOn behavior without scanning conflicts.
public class JwtService {
    public static final String COOKIE_NAME = "obx_token";
    private final SecretKey signingKey;

    public JwtService(String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> validate(String token) {
        if (token == null || token.isEmpty()) return null;
        return Jwts.parser().verifyWith(signingKey).build()
            .parseSignedClaims(token).getPayload();
    }
}
