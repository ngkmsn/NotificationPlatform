package com.notification.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public class CreateNotificationBulkRequest {

    @NotEmpty(message = "notifications list must not be empty")
    @Size(max = 1000, message = "bulk size cannot exceed 1000 notifications")
    @Valid
    private List<CreateNotificationRequest> notifications;

    public CreateNotificationBulkRequest() {
    }

    public CreateNotificationBulkRequest(List<CreateNotificationRequest> notifications) {
        this.notifications = notifications;
    }

    public List<CreateNotificationRequest> getNotifications() {
        return notifications;
    }

    public void setNotifications(List<CreateNotificationRequest> notifications) {
        this.notifications = notifications;
    }
}
