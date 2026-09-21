package com.notification.retry;

import com.notification.provider.ProviderSendResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class RetryClassifier {

    /**
     * Determines whether a provider send result represents a retryable (transient) error.
     *
     * @param result the ProviderSendResult
     * @return true if retryable, false if non-retryable (fatal) or success
     */
    public boolean isRetryable(ProviderSendResult result) {
        if (result == null || result.isSuccess()) {
            return false;
        }

        Integer statusCode = result.getHttpStatusCode();
        if (statusCode != null) {
            return isRetryableStatusCode(statusCode);
        }

        String errorMessage = result.getErrorMessage();
        return isRetryableErrorMessage(errorMessage);
    }

    /**
     * Determines whether an HTTP status code is retryable.
     *
     * @param statusCode HTTP status code
     * @return true if retryable (429, 5xx), false for 4xx client errors
     */
    public boolean isRetryableStatusCode(int statusCode) {
        // 429 Too Many Requests / Rate Limited
        if (statusCode == 429) {
            return true;
        }

        // 5xx Server Errors (500, 502, 503, 504, 507, 508, etc.)
        if (statusCode >= 500 && statusCode < 600) {
            return true;
        }

        // 408 Request Timeout
        if (statusCode == 408) {
            return true;
        }

        // 4xx Client Errors (400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 422 Unprocessable)
        return false;
    }

    /**
     * Determines whether an exception is retryable.
     *
     * @param throwable the caught exception
     * @return true if network/timeout/transient, false otherwise
     */
    public boolean isRetryableException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        if (throwable instanceof TimeoutException
                || throwable instanceof SocketTimeoutException
                || throwable instanceof ConnectException
                || throwable instanceof IOException) {
            return true;
        }

        // Inspect cause recursively
        if (throwable.getCause() != null && throwable.getCause() != throwable) {
            return isRetryableException(throwable.getCause());
        }

        return isRetryableErrorMessage(throwable.getMessage());
    }

    /**
     * Inspects error message keywords when status code is missing.
     */
    public boolean isRetryableErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }

        String lower = errorMessage.toLowerCase(Locale.ROOT);

        // Non-retryable keywords (explicit fatal checks)
        if (lower.contains("unregistered")
                || lower.contains("invalid-argument")
                || lower.contains("invalid token")
                || lower.contains("bad request")
                || lower.contains("not found")
                || lower.contains("unauthorized")
                || lower.contains("permission denied")) {
            return false;
        }

        // Retryable keywords
        return lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("rate limit")
                || lower.contains("too many requests")
                || lower.contains("throttled")
                || lower.contains("connection refused")
                || lower.contains("connection reset")
                || lower.contains("unavailable")
                || lower.contains("server error")
                || lower.contains("internal error")
                || lower.contains("service unavailable");
    }
}
