package com.broker.integration.security;

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
    private final byte[] metricsToken;

    public GatewaySecretFilter(@Value("${gateway.secret:}") String secret, @Value("${gateway.allow-unguarded:false}") boolean allowUnguarded, JwtDecoder decoder,
                              @Value("${gateway.metrics-token:}") String metricsToken) {
        this.decoder = decoder;
        if (!metricsToken.isBlank() && metricsToken.length() < 32) throw new IllegalStateException("METRICS_TOKEN is shorter than 32 characters.");
        this.metricsToken = metricsToken.isBlank() ? null : metricsToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
        if (isMetricsScrape(req, auth)) {
            // the metrics token is not a JWT: hide it from the authentication filters behind us, which would reject it as one
            chain.doFilter(new jakarta.servlet.http.HttpServletRequestWrapper(req) {
                @Override public String getHeader(String name) { return "Authorization".equalsIgnoreCase(name) ? null : super.getHeader(name); }
                @Override public java.util.Enumeration<String> getHeaders(String name) { return "Authorization".equalsIgnoreCase(name) ? java.util.Collections.emptyEnumeration() : super.getHeaders(name); }
            }, res);
            return;
        }
        if (seal.verify(req.getHeader("X-Gateway-Timestamp"), req.getHeader("X-Gateway-Nonce"), req.getHeader("X-Gateway-Signature"), req.getMethod(), req.getRequestURI(),
                req.getQueryString(), auth, System.currentTimeMillis() / 1000) || isSystemToken(auth)) {
            chain.doFilter(req, res);
            return;
        }
        logger.info("refused a request that did not come through the BFF: " + req.getMethod() + " " + req.getRequestURI() + " from " + req.getRemoteAddr());
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    /**
     * The one read-only door for a metrics scraper (Prometheus): GET /actuator/prometheus with the static METRICS_TOKEN as its bearer token. It opens nothing else,
     * and with no METRICS_TOKEN set it does not exist. The token is not a user token and not a SYSTEM token.
     */
    private boolean isMetricsScrape(HttpServletRequest req, String auth) {
        return metricsToken != null && "GET".equals(req.getMethod()) && "/actuator/prometheus".equals(req.getRequestURI()) && auth != null && auth.startsWith("Bearer ")
                && java.security.MessageDigest.isEqual(metricsToken, auth.substring(7).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * A super-credential call: a token identity-service issued to a service account (role SYSTEM), verified like any other.
     * Also a break-glass token: a short-lived user token that identity issued only to a SYSTEM caller who also knew the break-glass password.
     */
    private boolean isSystemToken(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        try {
            Jwt jwt = decoder.decode(auth.substring(7));
            return "SYSTEM".equals(jwt.getClaimAsString("role")) || Boolean.TRUE.equals(jwt.getClaim("break_glass"));   // break_glass: see identity-service /auth/break-glass/
        } catch (RuntimeException e) {
            return false;
        }
    }
}
