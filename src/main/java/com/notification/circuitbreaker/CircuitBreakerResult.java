package com.notification.circuitbreaker;

import java.util.Objects;

/**
 * Result of a Circuit Breaker permission acquisition.
 */
public class CircuitBreakerResult {

    private final boolean allowed;
    private final CircuitBreakerState state;
    private final long retryAfterMs;
    private final boolean probe;

    public CircuitBreakerResult(boolean allowed, CircuitBreakerState state, long retryAfterMs, boolean probe) {
        this.allowed = allowed;
        this.state = Objects.requireNonNull(state, "CircuitBreakerState must not be null");
        this.retryAfterMs = Math.max(0, retryAfterMs);
        this.probe = probe;
    }

    public static CircuitBreakerResult allowed(CircuitBreakerState state, long retryAfterMs, boolean probe) {
        return new CircuitBreakerResult(true, state, retryAfterMs, probe);
    }

    public static CircuitBreakerResult denied(CircuitBreakerState state, long retryAfterMs) {
        return new CircuitBreakerResult(false, state, retryAfterMs, false);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public CircuitBreakerState getState() {
        return state;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    public boolean isProbe() {
        return probe;
    }

    @Override
    public String toString() {
        return "CircuitBreakerResult{" +
                "allowed=" + allowed +
                ", state=" + state +
                ", retryAfterMs=" + retryAfterMs +
                ", probe=" + probe +
                '}';
    }
}
