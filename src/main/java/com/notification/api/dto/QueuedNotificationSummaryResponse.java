package com.notification.api.dto;

import com.notification.domain.Channel;
import com.notification.domain.Priority;

import java.time.OffsetDateTime;
import java.util.UUID;

public record QueuedNotificationSummaryResponse(
        UUID id,
        String recipient,
        Channel channel,
        Priority priority,
        String subject,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long queueAgeSeconds
) {}
