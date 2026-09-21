package com.notification.circuitbreaker;

/**
 * Represents the 3 states of a Circuit Breaker according to standard fault tolerance patterns.
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
