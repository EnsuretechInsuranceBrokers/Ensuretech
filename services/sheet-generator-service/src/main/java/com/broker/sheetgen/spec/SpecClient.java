package com.broker.sheetgen.spec;

import com.broker.sheetgen.api.BadRequestException;
import com.broker.sheetgen.api.NotFoundException;
import com.broker.sheetgen.api.UpstreamException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.broker.sheetgen.security.GatewaySeal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fetches a policy's sheet-spec from policy-service with the caller's own bearer token, so policy-service (not this service)
 * decides whether that broker may see that policy. A policy of another broker is a 404 here exactly as it is there.
 */
@Component
public class SpecClient {
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final GatewaySeal seal;

    SpecClient(ObjectMapper mapper, @Value("${app.policy-service.base-url}") String baseUrl,
               @Value("${app.policy-service.timeout-seconds:10}") long timeoutSeconds, @Value("${gateway.secret:}") String gatewaySecret) {
        this.mapper = mapper;
        this.seal = gatewaySecret.isBlank() ? null : new GatewaySeal(gatewaySecret);      // policy-service accepts only signed calls; this service holds the same secret as the BFF
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public JsonNode fetch(long policyId, String authorizationHeader) {
        String path = "/api/v1/broker/policies/" + policyId + "/sheet-spec";
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", authorizationHeader).header("Accept", "application/json").header("X-Correlation-Id", java.util.Optional.ofNullable(com.broker.sheetgen.logging.CorrelationFilter.current()).orElseGet(() -> java.util.UUID.randomUUID().toString())).timeout(timeout).GET();
        if (seal != null) {
            String ts = String.valueOf(System.currentTimeMillis() / 1000), nonce = java.util.UUID.randomUUID().toString().replace("-", "");
            rb.header("X-Gateway-Timestamp", ts).header("X-Gateway-Nonce", nonce).header("X-Gateway-Signature", seal.sign(ts, nonce, "GET", path, null, authorizationHeader));
        }
        HttpRequest req = rb.build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return switch (res.statusCode()) {
                case 200 -> mapper.readTree(res.body());
                case 404 -> throw new NotFoundException("Policy not found");
                case 401, 403 -> throw new BadRequestException("policy-service refused the token for this policy (" + res.statusCode() + ")");
                default -> throw new UpstreamException("policy-service answered " + res.statusCode(), null);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("interrupted while reading the sheet-spec", e);
        } catch (IOException e) {
            throw new UpstreamException("policy-service unreachable", e);
        }
    }
}
