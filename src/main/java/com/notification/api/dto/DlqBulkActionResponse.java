package com.notification.api.dto;

public class DlqBulkActionResponse {

    private int processedCount;
    private String status;
    private String message;

    public DlqBulkActionResponse() {
    }

    public DlqBulkActionResponse(int processedCount, String status, String message) {
        this.processedCount = processedCount;
        this.status = status;
        this.message = message;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    public void setProcessedCount(int processedCount) {
        this.processedCount = processedCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
