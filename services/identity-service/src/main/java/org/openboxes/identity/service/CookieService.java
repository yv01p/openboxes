package org.openboxes.identity.service;

import org.springframework.stereotype.Service;

@Service
public class CookieService {
    public String build(String token, boolean clear) {
        long maxAge = clear ? 0 : JwtService.TOKEN_LIFETIME_SECONDS;
        String value = clear ? "" : token;
        return JwtService.COOKIE_NAME + "=" + value
            + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + maxAge;
    }
}
