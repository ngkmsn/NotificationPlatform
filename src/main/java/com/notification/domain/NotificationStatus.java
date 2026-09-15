package com.notification.domain;

public enum NotificationStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    DELIVERED,
    FAILED,
    RETRYING,
    DEAD_LETTER,
    CANCELLED
}
