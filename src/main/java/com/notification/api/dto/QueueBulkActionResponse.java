package com.notification.api.dto;

public record QueueBulkActionResponse(
        int totalAffected,
        String action,
        String message
) {}
