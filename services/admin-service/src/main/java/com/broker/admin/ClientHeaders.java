package com.broker.admin;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.web.client.HttpHeadersProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Every call Spring Boot Admin makes to a service's /actuator carries the console's SYSTEM token. Without it the service's lock answers 403. */
@Component
class ClientHeaders implements HttpHeadersProvider {
    private final SystemToken token;

    ClientHeaders(SystemToken token) { this.token = token; }

    @Override
    public HttpHeaders getHeaders(Instance instance) {
        HttpHeaders h = new HttpHeaders();
        try { h.setBearerAuth(token.get()); } catch (RuntimeException e) { /* no token: the call goes unauthenticated and is refused, which Admin shows as the instance being restricted */ }
        return h;
    }
}
