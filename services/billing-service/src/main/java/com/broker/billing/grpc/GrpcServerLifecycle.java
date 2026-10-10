package com.broker.billing.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Runs the gRPC server next to the HTTP server. Plaintext for now; TLS/mTLS is added with the deployment work. */
@Component
@ConditionalOnProperty(name = "app.grpc.enabled", havingValue = "true", matchIfMissing = true)
public class GrpcServerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final CdBalanceGrpcService service;
    private final GrpcAuthInterceptor auth;
    private final int configuredPort;
    private volatile Server server;

    GrpcServerLifecycle(CdBalanceGrpcService service, GrpcAuthInterceptor auth, @Value("${app.grpc.port:9091}") int port) {
        this.service = service;
        this.auth = auth;
        this.configuredPort = port;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(configuredPort).intercept(auth).addService(service)
                    .maxInboundMessageSize(4 * 1024 * 1024).build().start();
            log.info("gRPC server listening on {}", server.getPort());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start gRPC server on port " + configuredPort, e);
        }
    }

    /** Actual bound port (useful when configured as 0). */
    public int port() { return server.getPort(); }

    @Override
    public void stop() {
        Server s = server;
        if (s == null) return;
        s.shutdown();
        try {
            if (!s.awaitTermination(5, TimeUnit.SECONDS)) s.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() { return server != null && !server.isShutdown(); }
}
