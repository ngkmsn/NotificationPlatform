package com.notification.circuitbreaker;

import com.notification.provider.ProviderSendResult;

/**
 * Interface for distributed Circuit Breaker pattern.
 */
public interface CircuitBreaker {

    CircuitBreakerResult acquirePermission(String key);

    CircuitBreakerResult acquirePermission(String key, CircuitBreakerConfig config);

    void recordResult(String key, ProviderSendResult result);

    void recordResult(String key, ProviderSendResult result, CircuitBreakerConfig config);

    void recordException(String key, Throwable throwable);

    void recordException(String key, Throwable throwable, CircuitBreakerConfig config);

    CircuitBreakerState getState(String key);

    boolean reset(String key);
}
