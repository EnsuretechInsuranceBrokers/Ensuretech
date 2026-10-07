package com.broker.billing.api;

public class ConflictException extends RuntimeException {
    public ConflictException(String msg) { super(msg); }
}
