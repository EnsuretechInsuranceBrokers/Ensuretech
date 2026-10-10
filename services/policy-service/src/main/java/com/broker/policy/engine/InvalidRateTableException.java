package com.broker.policy.engine;

import java.util.List;

public class InvalidRateTableException extends RuntimeException {
    private final List<String> problems;

    public InvalidRateTableException(List<String> problems) {
        super("Invalid rate table: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() { return problems; }
}
