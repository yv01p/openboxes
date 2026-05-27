package org.openboxes.identity.repository;

import org.openboxes.identity.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

    Optional<PasswordResetToken> findByTokenAndUsedAtIsNull(String token);

    void deleteByExpiresAtBefore(Instant instant);
}
