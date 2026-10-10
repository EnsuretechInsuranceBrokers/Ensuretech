package com.broker.billing.api;

public class ForbiddenTenantException extends RuntimeException {
    public ForbiddenTenantException(String msg) { super(msg); }
}
