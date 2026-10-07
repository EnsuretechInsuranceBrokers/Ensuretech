package com.broker.policy.api;

import java.util.List;

/** Request is well-formed but violates domain rules; carries machine-readable issues. */
public class ValidationFailedException extends RuntimeException {
    public record Issue(String code, String message) {}

    private final List<Issue> issues;

    public ValidationFailedException(List<Issue> issues) {
        super(issues.stream().map(Issue::message).reduce((a, b) -> a + "; " + b).orElse("validation failed"));
        this.issues = List.copyOf(issues);
    }

    public ValidationFailedException(String code, String message) {
        this(List.of(new Issue(code, message)));
    }

    public List<Issue> issues() { return issues; }
}
