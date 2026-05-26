package org.openboxes.document.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtCookieAuthFilter extends OncePerRequestFilter {
    private final SecretKey signingKey;

    public JwtCookieAuthFilter(@Value("${openboxes.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("obx_token".equals(c.getName())) {
                    try {
                        Claims claims = Jwts.parser()
                                .verifyWith(signingKey)
                                .build()
                                .parseSignedClaims(c.getValue())
                                .getPayload();
                        String userId = claims.getSubject();
                        @SuppressWarnings("unchecked")
                        List<String> roles = (List<String>) claims.get("roles", List.class);
                        var authorities = roles == null ? List.<SimpleGrantedAuthority>of()
                                : roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
                        var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    } catch (JwtException e) {
                        // invalid token — leave SecurityContext empty; downstream rejects.
                        // Log only the exception class name; jjwt's messages can include
                        // token fragments which we never want in logs (T7-M1). `logger` is
                        // inherited from OncePerRequestFilter; no extra import required.
                        logger.debug("JWT cookie rejected: " + e.getClass().getSimpleName());
                    }
                    break;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
