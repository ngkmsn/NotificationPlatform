package com.notification.dlq;

/**
 * Categorized reasons why a notification is routed to the Dead Letter Queue (DLQ).
 */
public enum DlqReason {
    MAX_RETRIES_EXCEEDED,
    NON_RETRYABLE_ERROR,
    NO_PROVIDER_AVAILABLE,
    FATAL_EXCEPTION,
    CIRCUIT_BREAKER_EXHAUSTED
}
