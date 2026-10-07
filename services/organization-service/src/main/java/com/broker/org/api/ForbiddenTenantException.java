package com.broker.org.api;

public class ForbiddenTenantException extends RuntimeException {
    public ForbiddenTenantException(String msg) { super(msg); }
}
