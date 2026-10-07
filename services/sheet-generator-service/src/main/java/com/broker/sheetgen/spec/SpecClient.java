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

    SpecClient(ObjectMapper mapper, @Value("${app.policy-service.base-url}") String baseUrl,
               @Value("${app.policy-service.timeout-seconds:10}") long timeoutSeconds) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public JsonNode fetch(long policyId, String authorizationHeader) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/broker/policies/" + policyId + "/sheet-spec"))
                .header("Authorization", authorizationHeader).header("Accept", "application/json").timeout(timeout).GET().build();
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
