package com.broker.policy.api;

public class ForbiddenTenantException extends RuntimeException {
    public ForbiddenTenantException(String msg) { super(msg); }
}
