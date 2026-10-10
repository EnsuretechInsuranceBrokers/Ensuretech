package com.broker.billing.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(Resource publicKeyLocation, String issuer) {}
