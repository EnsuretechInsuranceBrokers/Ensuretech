package com.broker.integration.api;

public class ForbiddenTenantException extends RuntimeException {
    public ForbiddenTenantException(String msg) { super(msg); }
}
