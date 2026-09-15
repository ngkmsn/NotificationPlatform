package com.notification.provider;

public class ProviderSendResult {

    private final boolean success;
    private final String providerMessageId;
    private final Integer httpStatusCode;
    private final String errorMessage;

    public ProviderSendResult(boolean success, String providerMessageId, Integer httpStatusCode, String errorMessage) {
        this.success = success;
        this.providerMessageId = providerMessageId;
        this.httpStatusCode = httpStatusCode;
        this.errorMessage = errorMessage;
    }

    public static ProviderSendResult success(String providerMessageId) {
        return new ProviderSendResult(true, providerMessageId, 200, null);
    }

    public static ProviderSendResult rateLimit(String errorMessage) {
        return new ProviderSendResult(false, null, 429, errorMessage != null ? errorMessage : "Rate limit exceeded (HTTP 429)");
    }

    public static ProviderSendResult serverError(String errorMessage) {
        return new ProviderSendResult(false, null, 500, errorMessage != null ? errorMessage : "Provider internal server error (HTTP 500)");
    }

    public static ProviderSendResult timeout(String errorMessage) {
        return new ProviderSendResult(false, null, 504, errorMessage != null ? errorMessage : "Provider connection timeout (HTTP 504)");
    }

    public static ProviderSendResult failure(int statusCode, String errorMessage) {
        return new ProviderSendResult(false, null, statusCode, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "ProviderSendResult{" +
                "success=" + success +
                ", providerMessageId='" + providerMessageId + '\'' +
                ", httpStatusCode=" + httpStatusCode +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
