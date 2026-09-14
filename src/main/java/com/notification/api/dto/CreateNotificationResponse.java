package com.notification.api.dto;

import com.notification.domain.NotificationStatus;
import java.util.UUID;

public class CreateNotificationResponse {

    private UUID id;
    private NotificationStatus status;

    public CreateNotificationResponse() {
    }

    public CreateNotificationResponse(UUID id, NotificationStatus status) {
        this.id = id;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationStatus status) {
        this.status = status;
    }
}
