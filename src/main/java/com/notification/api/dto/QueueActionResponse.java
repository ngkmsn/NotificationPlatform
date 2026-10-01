package com.notification.api.dto;

import com.notification.domain.NotificationStatus;
import java.util.UUID;

public record QueueActionResponse(
        UUID id,
        NotificationStatus status,
        String message
) {}
