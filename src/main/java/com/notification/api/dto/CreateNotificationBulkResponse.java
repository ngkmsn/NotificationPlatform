package com.notification.api.dto;

import java.util.List;
import java.util.UUID;

public class CreateNotificationBulkResponse {

    private int total;
    private int accepted;
    private List<UUID> notificationIds;

    public CreateNotificationBulkResponse() {
    }

    public CreateNotificationBulkResponse(int total, int accepted, List<UUID> notificationIds) {
        this.total = total;
        this.accepted = accepted;
        this.notificationIds = notificationIds;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getAccepted() {
        return accepted;
    }

    public void setAccepted(int accepted) {
        this.accepted = accepted;
    }

    public List<UUID> getNotificationIds() {
        return notificationIds;
    }

    public void setNotificationIds(List<UUID> notificationIds) {
        this.notificationIds = notificationIds;
    }
}
