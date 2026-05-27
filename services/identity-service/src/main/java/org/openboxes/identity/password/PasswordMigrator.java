package org.openboxes.identity.password;

import org.openboxes.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrates legacy SHA-1 password hashes to BCrypt in a separate transaction.
 * <p>
 * Uses REQUIRES_NEW propagation to ensure migration failures don't roll back
 * the calling transaction (e.g., login success should not depend on migration write success).
 * Extracted as a separate service to avoid @Transactional self-invocation issues.
 */
@Service
public class PasswordMigrator {
    private static final Logger log = LoggerFactory.getLogger(PasswordMigrator.class);
    private final UserRepository userRepository;

    public PasswordMigrator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrateToBcrypt(String userId, String newBcryptHash) {
        try {
            userRepository.findById(userId).ifPresent(u -> {
                u.setPassword(newBcryptHash);
                userRepository.save(u);
                log.info("legacy SHA-1 password migrated to BCrypt for userId={}", userId);
            });
        } catch (Exception e) {
            log.warn("password migration write failed for userId={}: {}", userId, e.getMessage());
        }
    }
}
