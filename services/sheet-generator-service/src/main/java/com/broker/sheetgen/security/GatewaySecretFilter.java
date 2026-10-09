package com.broker.sheetgen.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Nothing reaches this service except through the BFF. Every request (health probes aside) must carry a valid {@link GatewaySeal} signature,
 * or a verified SYSTEM token: a super-credential holder (a service account whose secret is stored hashed in identity-service) who signs in
 * for a token. A request with neither is refused before authentication, parsing or any database work, so curl, Postman and port scans get an empty 403.
 *
 * Fail closed: without GATEWAY_SECRET the service refuses to start, unless gateway.allow-unguarded=true (local development and tests only).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewaySecretFilter extends OncePerRequestFilter {
    private final GatewaySeal seal;
    private final JwtDecoder decoder;

    public GatewaySecretFilter(@Value("${gateway.secret:}") String secret, @Value("${gateway.allow-unguarded:false}") boolean allowUnguarded, JwtDecoder decoder) {
        this.decoder = decoder;
        boolean blank = secret.isBlank();
        if (!blank && secret.length() < 32 && !allowUnguarded) throw new IllegalStateException("GATEWAY_SECRET is shorter than 32 characters. Use a long random value (openssl rand -hex 32).");
        if (blank && !allowUnguarded) throw new IllegalStateException("GATEWAY_SECRET is not set, so this service would answer anyone who can reach it. Set it (the same value as the BFF's BFF_GATEWAY_SECRET); "
                + "gateway.allow-unguarded=true is for local development only.");
        this.seal = blank ? null : new GatewaySeal(secret);
        if (blank) logger.warn("GATEWAY_SECRET is not set and gateway.allow-unguarded=true: requests that bypass the BFF are NOT refused. Local development only.");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return seal == null || request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String auth = req.getHeader("Authorization");
        if (seal.verify(req.getHeader("X-Gateway-Timestamp"), req.getHeader("X-Gateway-Nonce"), req.getHeader("X-Gateway-Signature"), req.getMethod(), req.getRequestURI(),
                req.getQueryString(), auth, System.currentTimeMillis() / 1000) || isSystemToken(auth)) {
            chain.doFilter(req, res);
            return;
        }
        logger.info("refused a request that did not come through the BFF: " + req.getMethod() + " " + req.getRequestURI() + " from " + req.getRemoteAddr());
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    /** A super-credential call: a token identity-service issued to a service account (role SYSTEM), verified like any other. */
    private boolean isSystemToken(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        try {
            Jwt jwt = decoder.decode(auth.substring(7));
            return "SYSTEM".equals(jwt.getClaimAsString("role"));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
