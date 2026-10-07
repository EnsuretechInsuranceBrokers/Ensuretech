package com.broker.integration.api;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String msg) { super(msg); }
}
