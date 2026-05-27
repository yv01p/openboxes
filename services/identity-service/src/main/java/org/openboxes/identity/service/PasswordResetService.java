package org.openboxes.identity.service;

import org.openboxes.identity.entity.*;
import org.openboxes.identity.password.OpenboxesPasswordEncoder;
import org.openboxes.identity.password.PasswordComplexityValidator;
import org.openboxes.identity.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class PasswordResetService {
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final OpenboxesPasswordEncoder passwordEncoder;
    private final PasswordComplexityValidator validator;
    private final EmailService emailService;
    private final String grailsBaseUrl;
    private final SecureRandom rng = new SecureRandom();

    public PasswordResetService(UserRepository u, PasswordResetTokenRepository t,
                                OpenboxesPasswordEncoder e, PasswordComplexityValidator v,
                                EmailService email, @Value("${openboxes.grails-base-url:http://localhost/openboxes}") String url) {
        this.userRepository = u; this.tokenRepository = t; this.passwordEncoder = e;
        this.validator = v; this.emailService = email; this.grailsBaseUrl = url;
    }

    @Transactional
    public void requestReset(String email) {
        // Always silently succeed (don't leak whether email exists)
        userRepository.findByEmail(email).ifPresent(user -> {
            if (Boolean.TRUE.equals(user.getActive())) {
                byte[] bytes = new byte[32];
                rng.nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                PasswordResetToken prt = new PasswordResetToken();
                prt.setToken(token);
                prt.setUser(user);
                prt.setCreatedAt(Instant.now());
                prt.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
                tokenRepository.save(prt);
                String link = grailsBaseUrl + "/auth/resetPassword?token=" + token;
                try { emailService.sendPasswordResetEmail(user.getEmail(), link); }
                catch (Exception ignored) { /* silent fail per always-200 design */ }
            }
        });
    }

    @Transactional
    public void confirmReset(String token, String newPassword) {
        validator.validate(newPassword);
        PasswordResetToken prt = tokenRepository.findByTokenAndUsedAtIsNull(token)
            .orElseThrow(() -> new InvalidTokenException("invalid or already-used token"));
        if (prt.getExpiresAt().isBefore(Instant.now())) throw new InvalidTokenException("token expired");
        User user = prt.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        prt.setUsedAt(Instant.now());
        tokenRepository.save(prt);
    }
}
