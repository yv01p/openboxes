package org.openboxes.catalog.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class JwtCookieAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    public JwtCookieAuthFilter(JwtService jwt) { this.jwtService = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtService.COOKIE_NAME.equals(c.getName())) {
                    Map<String, Object> claims = jwtService.validate(c.getValue());
                    if (claims != null) {
                        String userId = (String) claims.get("sub");
                        String locationId = (String) claims.get("loc");
                        @SuppressWarnings("unchecked")
                        List<String> roleIds = (List<String>) claims.getOrDefault("roles", List.of());
                        req.setAttribute("userId", userId);
                        req.setAttribute("locationId", locationId);
                        req.setAttribute("roleIds", roleIds);
                        var authorities = roleIds.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
                        SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, authorities));
                    }
                    break;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
