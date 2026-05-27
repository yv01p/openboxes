package org.openboxes.identity.password;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Custom PasswordEncoder that supports both BCrypt (current) and legacy SHA-1 hashes.
 * <p>
 * Automatically migrates SHA-1 hashes to BCrypt on successful authentication.
 * Uses ThreadLocal to track the current user being verified, since the PasswordEncoder
 * interface's matches() signature does not include a userId parameter.
 * <p>
 * Note: MessageDigest.getInstance("SHA") is intentional — "SHA" is the Java alias for SHA-1,
 * matching the legacy Grails PasswordCodec.groovy implementation for bit-exact compatibility.
 */
@Component
public class OpenboxesPasswordEncoder implements PasswordEncoder {
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
    private final PasswordMigrator migrator;
    // Identity-service tracks the current user being verified via ThreadLocal so the
    // PasswordEncoder.matches(...) signature (no userId arg) can still trigger migration.
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    public OpenboxesPasswordEncoder(PasswordMigrator migrator) {
        this.migrator = migrator;
    }

    public static void setCurrentUserId(String userId) { CURRENT_USER_ID.set(userId); }
    public static void clearCurrentUserId() { CURRENT_USER_ID.remove(); }

    @Override
    public String encode(CharSequence rawPassword) {
        return bcrypt.encode(rawPassword);   // new + changed passwords always BCrypt
    }

    @Override
    public boolean matches(CharSequence rawPassword, String storedHash) {
        if (storedHash == null) return false;
        if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
            return bcrypt.matches(rawPassword, storedHash);
        }
        if (sha1Base64(rawPassword.toString()).equals(storedHash)) {
            String userId = CURRENT_USER_ID.get();
            if (userId != null) {
                migrator.migrateToBcrypt(userId, bcrypt.encode(rawPassword));
            }
            return true;
        }
        // No cleartext fallback (spec §10.1, §14).
        return false;
    }

    static String sha1Base64(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA")  // "SHA" alias → SHA-1, matches PasswordCodec.groovy:18
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA algorithm unavailable", e);
        }
    }
}
