package com.notification.retry;

import com.notification.provider.ProviderSendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    @DisplayName("Exponential backoff scales exponentially across attempts without jitter")
    void testExponentialBackoffWithoutJitter() {
        ExponentialBackoffStrategy backoff = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, false, null);

        assertEquals(1000L, backoff.calculateDelayMs(1, null));
        assertEquals(2000L, backoff.calculateDelayMs(2, null));
        assertEquals(4000L, backoff.calculateDelayMs(3, null));
        assertEquals(8000L, backoff.calculateDelayMs(4, null));
        assertEquals(16000L, backoff.calculateDelayMs(5, null));
    }

    @Test
    @DisplayName("Exponential backoff is capped by maxDelayMs")
    void testMaxDelayCap() {
        ExponentialBackoffStrategy backoff = new ExponentialBackoffStrategy(1000L, 5000L, 2.0, false, null);

        assertEquals(1000L, backoff.calculateDelayMs(1, null));
        assertEquals(2000L, backoff.calculateDelayMs(2, null));
        assertEquals(4000L, backoff.calculateDelayMs(3, null));
        assertEquals(5000L, backoff.calculateDelayMs(4, null)); // Capped at 5000
        assertEquals(5000L, backoff.calculateDelayMs(10, null)); // Capped at 5000
    }

    @Test
    @DisplayName("Rate limiter wait time takes precedence when larger than backoff")
    void testRateLimiterWaitTimePriority() {
        ExponentialBackoffStrategy backoff = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, false, null);

        // Attempt 1 base delay is 1000ms, but rate limiter suggests 4500ms -> delay must be 4500ms
        assertEquals(4500L, backoff.calculateDelayMs(1, 4500L));

        // Attempt 5 base delay is 16000ms, rate limiter suggests 2000ms -> delay must be 16000ms
        assertEquals(16000L, backoff.calculateDelayMs(5, 2000L));
    }

    @Test
    @DisplayName("Full Jitter produces delays within valid bounds")
    void testFullJitterBounds() {
        Random deterministicRandom = new Random(42);
        ExponentialBackoffStrategy backoff = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, true, deterministicRandom);

        for (int attempt = 1; attempt <= 5; attempt++) {
            long delay = backoff.calculateDelayMs(attempt, null);
            assertTrue(delay >= 500L, "Delay must be at least min jitter: " + delay);
            assertTrue(delay <= 60000L, "Delay must be <= max delay: " + delay);
        }
    }

    @Test
    @DisplayName("RetryClassifier correctly classifies retryable HTTP status codes")
    void testRetryableStatusCodes() {
        RetryClassifier classifier = new RetryClassifier();

        assertTrue(classifier.isRetryable(ProviderSendResult.rateLimit("Too Many Requests"))); // 429
        assertTrue(classifier.isRetryable(ProviderSendResult.serverError("Internal Error"))); // 500
        assertTrue(classifier.isRetryable(ProviderSendResult.timeout("Gateway Timeout"))); // 504
        assertTrue(classifier.isRetryable(ProviderSendResult.failure(502, "Bad Gateway")));
        assertTrue(classifier.isRetryable(ProviderSendResult.failure(503, "Service Unavailable")));
        assertTrue(classifier.isRetryable(ProviderSendResult.failure(408, "Request Timeout")));
    }

    @Test
    @DisplayName("RetryClassifier correctly classifies non-retryable fatal errors")
    void testNonRetryableStatusCodes() {
        RetryClassifier classifier = new RetryClassifier();

        assertFalse(classifier.isRetryable(ProviderSendResult.failure(400, "Bad Request")));
        assertFalse(classifier.isRetryable(ProviderSendResult.failure(401, "Unauthorized")));
        assertFalse(classifier.isRetryable(ProviderSendResult.failure(403, "Forbidden")));
        assertFalse(classifier.isRetryable(ProviderSendResult.failure(404, "Not Found")));
        assertFalse(classifier.isRetryable(ProviderSendResult.failure(422, "Unprocessable Entity")));
        assertFalse(classifier.isRetryable(ProviderSendResult.failure(0, "UNREGISTERED: device token no longer active")));
        assertFalse(classifier.isRetryable(ProviderSendResult.failure(0, "INVALID_ARGUMENT: malformed token")));
    }

    @Test
    @DisplayName("RetryClassifier classifies exceptions correctly")
    void testExceptionClassification() {
        RetryClassifier classifier = new RetryClassifier();

        assertTrue(classifier.isRetryableException(new SocketTimeoutException("Read timed out")));
        assertTrue(classifier.isRetryableException(new ConnectException("Connection refused")));
        assertTrue(classifier.isRetryableException(new IOException("Broken pipe")));
        assertTrue(classifier.isRetryableException(new RuntimeException("Wrapped", new SocketTimeoutException("timeout"))));

        assertFalse(classifier.isRetryableException(new IllegalArgumentException("Invalid token format")));
        assertFalse(classifier.isRetryableException(new NullPointerException("Recipient is null")));
    }

    @Test
    @DisplayName("RetryPolicy respects max-retries configuration")
    void testMaxRetriesEvaluation() {
        ExponentialBackoffStrategy backoff = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, false, null);
        RetryClassifier classifier = new RetryClassifier();
        RetryPolicy policy = new RetryPolicy(3, backoff, classifier);

        ProviderSendResult retryableResult = ProviderSendResult.serverError("Server Error");
        ProviderSendResult nonRetryableResult = ProviderSendResult.failure(400, "Bad Request");

        // Attempt 1, 2, 3 are within limit (<= 3)
        assertTrue(policy.shouldRetry(1, retryableResult));
        assertTrue(policy.shouldRetry(2, retryableResult));
        assertTrue(policy.shouldRetry(3, retryableResult));

        // Attempt 4 exceeds max retries
        assertFalse(policy.shouldRetry(4, retryableResult));

        // Non-retryable error should never retry even on attempt 1
        assertFalse(policy.shouldRetry(1, nonRetryableResult));

        // Rate limit checks
        assertTrue(policy.canRetryRateLimited(1));
        assertTrue(policy.canRetryRateLimited(3));
        assertFalse(policy.canRetryRateLimited(4));
    }
}
