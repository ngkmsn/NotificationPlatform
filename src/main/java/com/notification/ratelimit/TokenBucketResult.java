package com.notification.ratelimit;

import java.time.Duration;

public class TokenBucketResult {

    private final boolean allowed;
    private final double remainingTokens;
    private final long retryAfterMs;
    private final long capacity;
    private final double refillRatePerSecond;

    public TokenBucketResult(boolean allowed, double remainingTokens, long retryAfterMs, long capacity, double refillRatePerSecond) {
        this.allowed = allowed;
        this.remainingTokens = Math.max(0, remainingTokens);
        this.retryAfterMs = Math.max(0, retryAfterMs);
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
    }

    public static TokenBucketResult allowed(double remainingTokens, long capacity, double refillRatePerSecond) {
        return new TokenBucketResult(true, remainingTokens, 0L, capacity, refillRatePerSecond);
    }

    public static TokenBucketResult denied(double remainingTokens, long retryAfterMs, long capacity, double refillRatePerSecond) {
        return new TokenBucketResult(false, remainingTokens, retryAfterMs, capacity, refillRatePerSecond);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public double getRemainingTokens() {
        return remainingTokens;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }

    public Duration getWaitTime() {
        return Duration.ofMillis(retryAfterMs);
    }

    public long getCapacity() {
        return capacity;
    }

    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    @Override
    public String toString() {
        return "TokenBucketResult{" +
                "allowed=" + allowed +
                ", remainingTokens=" + remainingTokens +
                ", retryAfterMs=" + retryAfterMs +
                ", capacity=" + capacity +
                ", refillRatePerSecond=" + refillRatePerSecond +
                '}';
    }
}
