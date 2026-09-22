package com.notification.api.dto;

import java.util.UUID;

public class DeviceResponse {

    private UUID id;
    private String userId;
    private String deviceToken;
    private String platform;
    private Boolean isActive;
    private String message;

    public DeviceResponse() {
    }

    public DeviceResponse(UUID id, String userId, String deviceToken, String platform, Boolean isActive, String message) {
        this.id = id;
        this.userId = userId;
        this.deviceToken = deviceToken;
        this.platform = platform;
        this.isActive = isActive;
        this.message = message;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean active) {
        isActive = active;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
