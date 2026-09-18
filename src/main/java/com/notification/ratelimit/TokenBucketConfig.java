package com.notification.ratelimit;

import java.time.Duration;

public class TokenBucketConfig {

    private final long capacity;
    private final double refillRatePerSecond;
    private final long requestedTokens;
    private final Duration ttl;

    public TokenBucketConfig(long capacity, double refillRatePerSecond, long requestedTokens, Duration ttl) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        if (refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("Refill rate must be greater than 0");
        }
        if (requestedTokens <= 0) {
            throw new IllegalArgumentException("Requested tokens must be greater than 0");
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.requestedTokens = requestedTokens;
        this.ttl = ttl != null ? ttl : calculateDefaultTtl(capacity, refillRatePerSecond);
    }

    public static TokenBucketConfig of(long capacity, double refillRatePerSecond) {
        return new TokenBucketConfig(capacity, refillRatePerSecond, 1L, null);
    }

    public static TokenBucketConfig of(long capacity, double refillRatePerSecond, long requestedTokens) {
        return new TokenBucketConfig(capacity, refillRatePerSecond, requestedTokens, null);
    }

    public static TokenBucketConfig of(long capacity, double refillRatePerSecond, long requestedTokens, Duration ttl) {
        return new TokenBucketConfig(capacity, refillRatePerSecond, requestedTokens, ttl);
    }

    private static Duration calculateDefaultTtl(long capacity, double refillRatePerSecond) {
        // Minimum TTL is time needed to refill from 0 to full capacity, plus safety buffer (minimum 1 hour)
        long secondsToFull = (long) Math.ceil(capacity / refillRatePerSecond);
        return Duration.ofSeconds(Math.max(3600, secondsToFull * 2));
    }

    public long getCapacity() {
        return capacity;
    }

    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    public long getRequestedTokens() {
        return requestedTokens;
    }

    public Duration getTtl() {
        return ttl;
    }
}
