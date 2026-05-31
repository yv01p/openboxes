package org.openboxes.identity.service;

import org.openboxes.auth.common.JwtService;  // for COOKIE_NAME (Phase 5.1: extracted to starter)
import org.springframework.stereotype.Service;

@Service
public class CookieService {
    public String build(String token, boolean clear) {
        long maxAge = clear ? 0 : JwtIssuerService.TOKEN_LIFETIME_SECONDS;
        String value = clear ? "" : token;
        return JwtService.COOKIE_NAME + "=" + value
            + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + maxAge;
    }
}
