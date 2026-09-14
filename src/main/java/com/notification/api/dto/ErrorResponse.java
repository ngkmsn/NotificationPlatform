package com.notification.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public class ErrorResponse {

    private String message;
    private List<String> details;
    private OffsetDateTime timestamp;

    public ErrorResponse() {
        this.timestamp = OffsetDateTime.now();
    }

    public ErrorResponse(String message) {
        this.message = message;
        this.details = List.of(message);
        this.timestamp = OffsetDateTime.now();
    }

    public ErrorResponse(String message, List<String> details) {
        this.message = message;
        this.details = details;
        this.timestamp = OffsetDateTime.now();
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
