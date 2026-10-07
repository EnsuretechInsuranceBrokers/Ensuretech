package com.broker.sheetgen.api;

public class ForbiddenTenantException extends RuntimeException {
    public ForbiddenTenantException(String msg) { super(msg); }
}
