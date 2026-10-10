package com.broker.policy.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every request has a correlation id for its whole life. The BFF mints one (or accepts a plain id from the caller) and sends it as X-Correlation-Id; this service puts it in the
 * logging context, so every log line the request causes carries it (logging.pattern.level), returns it in the response, passes it on to the services it calls, and writes one
 * access line per request. Grep the id across the logs of every service to follow one request. It runs before the gateway lock, so refused requests are logged with an id too.
 * An id that is not plainly an id (wrong characters, too long, newlines) is replaced, never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    private static final Pattern IN_EVENT = Pattern.compile("\"correlation_id\"\\s*:\\s*\"([A-Za-z0-9._-]{8,64})\"");
    private static final Logger access = LoggerFactory.getLogger("access");

    public static boolean valid(String id) { return id != null && VALID.matcher(id).matches(); }

    /** The id of the request this thread is serving, or null (a background job). */
    public static String current() { return MDC.get(MDC_KEY); }

    /** The id an event carries (the engine's job correlation id), or null. */
    public static String fromEvent(String json) {
        if (json == null) return null;
        Matcher m = IN_EVENT.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** Runs work under an id (a Kafka event, a background job) and restores whatever was there before. A null id is replaced by a fresh one. */
    public static void runWith(String id, Runnable work) {
        supply(id, () -> { work.run(); return null; });
    }

    public static <T> T supply(String id, Supplier<T> work) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, valid(id) ? id : UUID.randomUUID().toString());
        try { return work.get(); }
        finally { if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous); }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String given = req.getHeader(HEADER);
        String id = valid(given) ? given : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);
        long t0 = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            String uri = req.getRequestURI();
            boolean quiet = uri.startsWith("/actuator/health") || uri.startsWith("/actuator/prometheus");        // probes and scrapes every few seconds: debug level only
            String line = req.getMethod() + " " + uri + " -> " + res.getStatus() + " " + (System.nanoTime() - t0) / 1_000_000 + " ms";
            if (quiet) access.debug(line); else access.info(line);
            MDC.remove(MDC_KEY);
        }
    }
}
