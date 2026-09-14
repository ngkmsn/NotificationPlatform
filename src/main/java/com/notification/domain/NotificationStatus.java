package com.notification.domain;

public enum NotificationStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    DELIVERED,
    RETRYING,
    DEAD_LETTER,
    CANCELLED
}
