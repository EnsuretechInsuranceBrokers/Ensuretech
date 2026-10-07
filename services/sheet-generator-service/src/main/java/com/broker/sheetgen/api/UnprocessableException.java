package com.broker.sheetgen.api;

import java.util.List;

/** The request is well-formed but the policy cannot be generated for (for example its sheet-spec still has gaps). */
public class UnprocessableException extends RuntimeException {
    private final List<String> details;

    public UnprocessableException(String message, List<String> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<String> details() { return details; }
}
