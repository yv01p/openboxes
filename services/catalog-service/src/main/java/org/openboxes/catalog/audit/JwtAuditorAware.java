package org.openboxes.catalog.audit;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

// FD#8 Option-A audit infrastructure (built once in T2; reused by T3–T5, T12 write paths).
// Supplies the current user id for @CreatedBy/@LastModifiedBy via Spring Data JPA auditing.
// JwtCookieAuthFilter (jwt-auth-common) sets the SecurityContext principal = the JWT `sub`
// (the user id) on every authenticated request, so getAuthentication().getName() IS the user id.
// Returns empty for unauthenticated/anonymous writes — created_by_id/updated_by_id are nullable
// and tolerate NULL for system/anonymous writes.
@Component
public class JwtAuditorAware implements AuditorAware<String> {
    @Override
    public Optional<String> getCurrentAuditor() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
            .filter(Authentication::isAuthenticated)
            .filter(auth -> !"anonymousUser".equals(auth.getPrincipal()))
            .map(Authentication::getName);
    }
}
