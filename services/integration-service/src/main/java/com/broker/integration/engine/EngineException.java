package com.broker.integration.engine;

public class EngineException extends RuntimeException {
    private final boolean retryable;

    public EngineException(String message, boolean retryable) { super(message); this.retryable = retryable; }

    public boolean isRetryable() { return retryable; }
}
