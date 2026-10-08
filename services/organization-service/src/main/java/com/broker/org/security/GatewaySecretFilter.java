package com.broker.org.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Only the BFF may call the browser-facing API. It sends the shared secret (GATEWAY_SECRET) and a session key, HMAC-SHA256(secret, session id); anything
 * else is refused before authentication even looks at the user's token. Service-to-service calls use other paths and are not affected.
 * Blank secret = check off (local development and tests); the service says so at startup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewaySecretFilter extends OncePerRequestFilter {

    private final byte[] secret;

    public GatewaySecretFilter(@Value("${gateway.secret:}") String secret) {
        this.secret = secret.isBlank() ? null : secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret == null) {
            logger.warn("GATEWAY_SECRET is not set: calls that bypass the BFF are NOT refused. Set it outside local development.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return secret == null || !(p.startsWith("/api/v1/broker/") || p.startsWith("/api/v1/employer/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String given = req.getHeader("X-Gateway-Secret");
        String sid = req.getHeader("X-Session-Id");
        String key = req.getHeader("X-Session-Key");
        if (given == null || sid == null || key == null || !same(given.getBytes(StandardCharsets.UTF_8), secret) || !same(hmac(sid).getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(req, res);
    }

    private static boolean same(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private String hmac(String sid) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte x : m.doFinal(sid.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
