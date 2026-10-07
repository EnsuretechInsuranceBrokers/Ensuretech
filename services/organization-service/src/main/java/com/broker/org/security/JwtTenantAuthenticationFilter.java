package com.broker.org.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after bearer-token authentication. Copies tenant claims from the already-verified JWT into a
 * ThreadLocal TenantContext once per request and always clears it afterwards.
 * Deliberately NOT a Spring bean, so it is only registered inside the security filter chain.
 */
public class JwtTenantAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof JwtAuthenticationToken token) {
                Jwt jwt = token.getToken();
                TenantContextHolder.set(new TenantContext(
                        jwt.getSubject() != null ? jwt.getSubject() : String.valueOf((Object) jwt.getClaim("user_id")),
                        jwt.getClaimAsString("role"),
                        asLong(jwt.getClaim("broker_id")),
                        asLong(jwt.getClaim("employer_id")),
                        asLong(jwt.getClaim("tpa_id"))));
            }
            chain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private static Long asLong(Object claim) {
        return claim instanceof Number n ? n.longValue() : null;
    }
}
