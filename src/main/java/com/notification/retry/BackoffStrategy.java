package com.notification.retry;

/**
 * Strategy interface for calculating retry delays.
 */
public interface BackoffStrategy {

    /**
     * Calculates the delay in milliseconds before the next retry attempt.
     *
     * @param attemptNumber the current attempt number (1-based)
     * @param suggestedWaitMs optional minimum wait time suggested by the error or rate limiter (e.g., retryAfterMs)
     * @return delay in milliseconds
     */
    long calculateDelayMs(int attemptNumber, Long suggestedWaitMs);
}
