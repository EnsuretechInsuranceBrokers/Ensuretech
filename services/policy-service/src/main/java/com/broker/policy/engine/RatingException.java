package com.broker.policy.engine;

/** The rate card cannot price this input (e.g. no age band for age 70). Maps to FAILED_PRECONDITION / 422. */
public class RatingException extends RuntimeException {
    public RatingException(String message) { super(message); }
}
