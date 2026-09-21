package com.notification.retry;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Random;

@ApplicationScoped
public class ExponentialBackoffStrategy implements BackoffStrategy {

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double multiplier;
    private final boolean jitterEnabled;
    private final Random random;

    @jakarta.inject.Inject
    public ExponentialBackoffStrategy(
            @ConfigProperty(name = "retry.initial-delay-ms", defaultValue = "1000") long initialDelayMs,
            @ConfigProperty(name = "retry.max-delay-ms", defaultValue = "60000") long maxDelayMs,
            @ConfigProperty(name = "retry.multiplier", defaultValue = "2.0") double multiplier,
            @ConfigProperty(name = "retry.jitter-enabled", defaultValue = "true") boolean jitterEnabled) {
        this(initialDelayMs, maxDelayMs, multiplier, jitterEnabled, new Random());
    }

    public ExponentialBackoffStrategy(long initialDelayMs, long maxDelayMs, double multiplier, boolean jitterEnabled, Random random) {
        this.initialDelayMs = Math.max(1, initialDelayMs);
        this.maxDelayMs = Math.max(this.initialDelayMs, maxDelayMs);
        this.multiplier = Math.max(1.0, multiplier);
        this.jitterEnabled = jitterEnabled;
        this.random = random != null ? random : new Random();
    }

    @Override
    public long calculateDelayMs(int attemptNumber, Long suggestedWaitMs) {
        int attemptIndex = Math.max(0, attemptNumber - 1);

        // Exponential backoff base: initialDelay * (multiplier ^ attemptIndex)
        double rawDelay = initialDelayMs * Math.pow(multiplier, attemptIndex);
        long baseDelay = (long) Math.min(rawDelay, maxDelayMs);

        long calculatedDelay;
        if (jitterEnabled && baseDelay > 0) {
            // Full Jitter: Uniform random between [initialDelay / 2, baseDelay]
            long minJitter = Math.min(initialDelayMs / 2, baseDelay);
            long span = Math.max(1L, baseDelay - minJitter);
            calculatedDelay = minJitter + (long) (random.nextDouble() * span);
        } else {
            calculatedDelay = baseDelay;
        }

        // If rate limiter or provider suggested a minimum wait time, take the maximum
        if (suggestedWaitMs != null && suggestedWaitMs > 0) {
            return Math.min(maxDelayMs, Math.max(suggestedWaitMs, calculatedDelay));
        }

        return Math.min(maxDelayMs, Math.max(1L, calculatedDelay));
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public boolean isJitterEnabled() {
        return jitterEnabled;
    }
}
