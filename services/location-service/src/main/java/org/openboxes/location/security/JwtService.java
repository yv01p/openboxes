package org.openboxes.location.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class JwtService {
    public static final String COOKIE_NAME = "obx_token";
    private final SecretKey signingKey;

    public JwtService(@Value("${openboxes.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> validate(String token) {
        try {
            return Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
