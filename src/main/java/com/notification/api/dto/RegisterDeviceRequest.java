package com.notification.api.dto;

import jakarta.validation.constraints.NotBlank;

public class RegisterDeviceRequest {

    @NotBlank(message = "userId must not be blank")
    private String userId;

    @NotBlank(message = "deviceToken must not be blank")
    private String deviceToken;

    private String platform = "WEB";

    public RegisterDeviceRequest() {
    }

    public RegisterDeviceRequest(String userId, String deviceToken, String platform) {
        this.userId = userId;
        this.deviceToken = deviceToken;
        this.platform = platform;
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
}
