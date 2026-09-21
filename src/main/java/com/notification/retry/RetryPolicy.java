package com.notification.retry;

import com.notification.provider.ProviderSendResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class RetryPolicy {

    private final int maxRetries;
    private final BackoffStrategy backoffStrategy;
    private final RetryClassifier retryClassifier;

    @Inject
    public RetryPolicy(
            @ConfigProperty(name = "notification.worker.max-retries", defaultValue = "3") int maxRetries,
            BackoffStrategy backoffStrategy,
            RetryClassifier retryClassifier) {
        this.maxRetries = Math.max(0, maxRetries);
        this.backoffStrategy = backoffStrategy;
        this.retryClassifier = retryClassifier;
    }

    /**
     * Checks whether an attempt should be retried based on attempt number and error type.
     */
    public boolean shouldRetry(int currentAttempt, ProviderSendResult result) {
        if (currentAttempt > maxRetries) {
            return false;
        }
        return retryClassifier.isRetryable(result);
    }

    /**
     * Checks whether a rate-limited attempt can still be retried.
     */
    public boolean canRetryRateLimited(int currentAttempt) {
        return currentAttempt <= maxRetries;
    }

    /**
     * Computes the scheduled retry timestamp in the future.
     */
    public OffsetDateTime calculateNextRetryTime(int attemptNumber, Long suggestedWaitMs) {
        long delayMs = backoffStrategy.calculateDelayMs(attemptNumber, suggestedWaitMs);
        return OffsetDateTime.now().plus(delayMs, ChronoUnit.MILLIS);
    }

    /**
     * Computes raw delay in milliseconds.
     */
    public long calculateDelayMs(int attemptNumber, Long suggestedWaitMs) {
        return backoffStrategy.calculateDelayMs(attemptNumber, suggestedWaitMs);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public BackoffStrategy getBackoffStrategy() {
        return backoffStrategy;
    }

    public RetryClassifier getRetryClassifier() {
        return retryClassifier;
    }
}
