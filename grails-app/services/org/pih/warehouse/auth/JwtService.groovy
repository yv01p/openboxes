package org.pih.warehouse.auth

import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.security.Keys
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User

import javax.crypto.SecretKey
import java.nio.charset.StandardCharsets

@CompileStatic
@Transactional(readOnly = true)
class JwtService {

    static final String COOKIE_NAME = 'obx_token'
    static final int TOKEN_LIFETIME_SECONDS = 8 * 3600

    private SecretKey getSigningKey() {
        String secret = System.getenv('OPENBOXES_JWT_SECRET')
        if (!secret) {
            throw new IllegalStateException(
                'OPENBOXES_JWT_SECRET env var is required for JWT issuance/validation')
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))
    }

    String issue(User user, Location location) {
        Date now = new Date()
        Date exp = new Date(now.time + TOKEN_LIFETIME_SECONDS * 1000L)
        return Jwts.builder()
                .setSubject(user.id)
                .claim('loc', location?.id)
                .claim('roles', user.roles?.collect { it.id } ?: [])
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact()
    }

    /** Returns parsed claims map, or null if token invalid/expired. */
    Map<String, Object> validate(String token) {
        try {
            return (Map<String, Object>) Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
        } catch (JwtException ignored) {
            return null
        }
    }

    /** Build the Set-Cookie header value manually — Servlet 3.1 lacks native SameSite support. */
    static String buildSetCookieHeader(String token, boolean clear = false) {
        long maxAge = clear ? 0 : TOKEN_LIFETIME_SECONDS
        String value = clear ? '' : token
        return "${COOKIE_NAME}=${value}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAge}"
    }
}
