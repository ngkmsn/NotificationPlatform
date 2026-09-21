package com.notification.circuitbreaker;

import java.time.Duration;

/**
 * Configuration parameters for a distributed Circuit Breaker instance.
 */
public class CircuitBreakerConfig {

    private final double failureRateThreshold;
    private final int minimumNumberOfCalls;
    private final Duration slidingWindowDuration;
    private final Duration waitDurationInOpenState;
    private final int permittedNumberOfCallsInHalfOpenState;
    private final int halfOpenSuccessThreshold;
    private final Duration ttl;

    public CircuitBreakerConfig(
            double failureRateThreshold,
            int minimumNumberOfCalls,
            Duration slidingWindowDuration,
            Duration waitDurationInOpenState,
            int permittedNumberOfCallsInHalfOpenState,
            int halfOpenSuccessThreshold,
            Duration ttl) {
        if (failureRateThreshold <= 0.0 || failureRateThreshold > 100.0) {
            throw new IllegalArgumentException("failureRateThreshold must be between 0.0 and 100.0, given: " + failureRateThreshold);
        }
        if (minimumNumberOfCalls <= 0) {
            throw new IllegalArgumentException("minimumNumberOfCalls must be positive, given: " + minimumNumberOfCalls);
        }
        if (permittedNumberOfCallsInHalfOpenState <= 0) {
            throw new IllegalArgumentException("permittedNumberOfCallsInHalfOpenState must be positive, given: " + permittedNumberOfCallsInHalfOpenState);
        }
        if (halfOpenSuccessThreshold <= 0) {
            throw new IllegalArgumentException("halfOpenSuccessThreshold must be positive, given: " + halfOpenSuccessThreshold);
        }

        this.failureRateThreshold = failureRateThreshold;
        this.minimumNumberOfCalls = minimumNumberOfCalls;
        this.slidingWindowDuration = slidingWindowDuration != null ? slidingWindowDuration : Duration.ofSeconds(60);
        this.waitDurationInOpenState = waitDurationInOpenState != null ? waitDurationInOpenState : Duration.ofSeconds(30);
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
        this.ttl = ttl != null ? ttl : Duration.ofHours(24);
    }

    public static CircuitBreakerConfig ofDefaults() {
        return new CircuitBreakerConfig(
                50.0,
                5,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                3,
                2,
                Duration.ofHours(24)
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public Duration getSlidingWindowDuration() {
        return slidingWindowDuration;
    }

    public Duration getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    public int getPermittedNumberOfCallsInHalfOpenState() {
        return permittedNumberOfCallsInHalfOpenState;
    }

    public int getHalfOpenSuccessThreshold() {
        return halfOpenSuccessThreshold;
    }

    public Duration getTtl() {
        return ttl;
    }

    public static class Builder {
        private double failureRateThreshold = 50.0;
        private int minimumNumberOfCalls = 5;
        private Duration slidingWindowDuration = Duration.ofSeconds(60);
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        private int permittedNumberOfCallsInHalfOpenState = 3;
        private int halfOpenSuccessThreshold = 2;
        private Duration ttl = Duration.ofHours(24);

        public Builder failureRateThreshold(double failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
            return this;
        }

        public Builder minimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
            return this;
        }

        public Builder slidingWindowDuration(Duration slidingWindowDuration) {
            this.slidingWindowDuration = slidingWindowDuration;
            return this;
        }

        public Builder waitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
            return this;
        }

        public Builder permittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
            return this;
        }

        public Builder halfOpenSuccessThreshold(int halfOpenSuccessThreshold) {
            this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
            return this;
        }

        public Builder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(
                    failureRateThreshold,
                    minimumNumberOfCalls,
                    slidingWindowDuration,
                    waitDurationInOpenState,
                    permittedNumberOfCallsInHalfOpenState,
                    halfOpenSuccessThreshold,
                    ttl
            );
        }
    }
}
