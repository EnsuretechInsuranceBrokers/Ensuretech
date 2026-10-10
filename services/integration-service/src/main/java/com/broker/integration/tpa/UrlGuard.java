package com.broker.integration.tpa;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Brokers supply the TPA base URL, and this service will call it with our network position: classic SSRF. We require https,
 * refuse embedded credentials and refuse hosts that resolve to loopback / private / link-local / metadata addresses. It is
 * checked when a config is saved and again right before each job runs (DNS can change); a rebinding race between that check
 * and the connect remains, so production should also egress-filter this service at the network layer.
 */
@Component
public class UrlGuard {
    private final boolean allowPrivate;
    private final boolean allowHttp;

    UrlGuard(@Value("${app.tpa.allow-private-hosts:false}") boolean allowPrivate, @Value("${app.tpa.allow-http:false}") boolean allowHttp) {
        this.allowPrivate = allowPrivate;
        this.allowHttp = allowHttp;
    }

    /** @throws IllegalArgumentException with a safe-to-show message */
    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!(scheme.equals("https") || (allowHttp && scheme.equals("http")))) throw new IllegalArgumentException("baseUrl must use https");
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("baseUrl must not contain credentials");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("baseUrl needs a host");
        if (!allowPrivate) {
            try {
                for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                    if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                            || a.isMulticastAddress() || isUniqueLocalV6(a) || isCgnat(a)) {
                        throw new IllegalArgumentException("baseUrl resolves to a non-public address");
                    }
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("baseUrl host does not resolve");
            }
        }
        return uri;
    }

    private static boolean isUniqueLocalV6(InetAddress a) { byte[] b = a.getAddress(); return b.length == 16 && (b[0] & 0xfe) == 0xfc; }

    private static boolean isCgnat(InetAddress a) { byte[] b = a.getAddress(); return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; }
}
