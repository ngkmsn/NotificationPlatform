package com.notification.api.dto;

import com.notification.domain.NotificationStatus;

import java.util.UUID;

public class DlqActionResponse {

    private UUID id;
    private NotificationStatus status;
    private String message;

    public DlqActionResponse() {
    }

    public DlqActionResponse(UUID id, NotificationStatus status, String message) {
        this.id = id;
        this.status = status;
        this.message = message;
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
