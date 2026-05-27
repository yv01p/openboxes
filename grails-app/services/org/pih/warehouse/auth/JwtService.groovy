package org.pih.warehouse.auth

import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.security.Keys

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
}
