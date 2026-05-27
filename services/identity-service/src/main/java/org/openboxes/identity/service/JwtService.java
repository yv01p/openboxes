package org.openboxes.identity.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.openboxes.identity.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class JwtService {
    public static final String COOKIE_NAME = "obx_token";
    public static final long TOKEN_LIFETIME_SECONDS = 8L * 3600L;
    private final SecretKey signingKey;

    public JwtService(@Value("${openboxes.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(User user, String locationId, List<String> roleIds) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(TOKEN_LIFETIME_SECONDS);
        return Jwts.builder()
            .subject(user.getId())
            .claim("loc", locationId)
            .claim("roles", roleIds == null ? List.of() : roleIds)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(signingKey)
            .compact();
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
