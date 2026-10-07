package com.broker.integration.tpa;

import com.broker.integration.domain.TpaConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Every call to a TPA goes through here: one circuit breaker per (broker, TPA) so one broker's dead TPA cannot trip another's,
 * a short in-call retry for blips, and a clear split between "try again later" (retryable / breaker open) and "will never work".
 * Only retryable failures count against the breaker; a TPA that politely rejects bad data is not an unhealthy TPA.
 */
@Component
public class TpaGateway {
    private final Map<TpaConfig.Adapter, TpaAdapter> adapters = new EnumMap<>(TpaConfig.Adapter.class);
    private final CircuitBreakerRegistry breakers;
    private final Environment env;
    private final int callRetries;
    private final long retryBaseMs;
    private final UrlGuard guard;

    TpaGateway(List<TpaAdapter> all, Environment env, UrlGuard guard,
               @Value("${app.sync.call-retries:3}") int callRetries, @Value("${app.sync.call-retry-base-ms:1000}") long retryBaseMs,
               @Value("${app.sync.breaker-window:10}") int window, @Value("${app.sync.breaker-min-calls:5}") int minCalls,
               @Value("${app.sync.breaker-failure-rate:50}") float failureRate, @Value("${app.sync.breaker-open-seconds:30}") long openSeconds) {
        all.forEach(a -> adapters.put(a.type(), a));
        this.env = env;
        this.guard = guard;
        this.callRetries = callRetries;
        this.retryBaseMs = retryBaseMs;
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(window).minimumNumberOfCalls(minCalls).failureRateThreshold(failureRate)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .recordException(e -> e instanceof TpaAdapter.TpaException t && t.isRetryable())
                .build());
    }

    /** @throws CallNotPermittedException breaker open: the caller should wait, not count an attempt. */
    public List<TpaAdapter.MemberResult> enroll(TpaConfig cfg, TpaAdapter.SyncBatch batch) {
        TpaAdapter adapter = adapters.get(cfg.getAdapter());
        if (adapter == null) throw new TpaAdapter.TpaException("no adapter for " + cfg.getAdapter(), false);
        try {
            guard.check(cfg.getBaseUrl());
        } catch (IllegalArgumentException e) {
            throw new TpaAdapter.TpaException("TPA base URL rejected: " + e.getMessage(), false);
        }
        TpaAdapter.Credentials creds = credentials(cfg);
        CircuitBreaker cb = breaker(cfg);
        Retry retry = Retry.of("tpa-" + cfg.getId(), RetryConfig.custom()
                .maxAttempts(Math.max(1, callRetries))
                .intervalFunction(n -> retryBaseMs * (1L << (n - 1)))                      // 1s, 2s, 4s ...
                .retryOnException(e -> e instanceof TpaAdapter.TpaException t && t.isRetryable())   // never retry CallNotPermitted
                .build());
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, () -> adapter.enroll(cfg, creds, batch))).get();
    }

    private TpaAdapter.Credentials credentials(TpaConfig cfg) {
        String ref = cfg.getSecretRef();
        return switch (cfg.getAuthType()) {
            case NONE -> TpaAdapter.Credentials.NONE;
            case BASIC -> new TpaAdapter.Credentials(require(ref + "_USERNAME"), require(ref + "_PASSWORD"), null);
            case BEARER -> new TpaAdapter.Credentials(null, null, require(ref + "_TOKEN"));
        };
    }

    private String require(String key) {
        String v = env.getProperty(key);
        if (v == null || v.isBlank()) throw new TpaAdapter.TpaException("credential " + key + " is not configured in this deployment", false);
        return v;
    }

    private CircuitBreaker breaker(TpaConfig cfg) { return breakers.circuitBreaker("tpa-" + cfg.getBrokerId() + "-" + cfg.getTpaId()); }

    public Map<String, String> states(long brokerId) {
        Map<String, String> out = new LinkedHashMap<>();
        breakers.getAllCircuitBreakers().stream().filter(c -> c.getName().startsWith("tpa-" + brokerId + "-"))
                .forEach(c -> out.put(c.getName().substring(("tpa-" + brokerId + "-").length()), c.getState().name()));
        return out;
    }

}
