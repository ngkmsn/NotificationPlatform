package com.notification.api.dto;

import com.notification.domain.AttemptStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public class DlqAttemptDto {

    private UUID id;
    private Integer attemptNumber;
    private AttemptStatus status;
    private String provider;
    private String errorMessage;
    private OffsetDateTime attemptedAt;
    private OffsetDateTime completedAt;

    public DlqAttemptDto() {
    }

    public DlqAttemptDto(UUID id, Integer attemptNumber, AttemptStatus status, String provider,
                         String errorMessage, OffsetDateTime attemptedAt, OffsetDateTime completedAt) {
        this.id = id;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.provider = provider;
        this.errorMessage = errorMessage;
        this.attemptedAt = attemptedAt;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Integer getAttemptNumber() {
        return attemptNumber;
    }

    public void setAttemptNumber(Integer attemptNumber) {
        this.attemptNumber = attemptNumber;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public void setStatus(AttemptStatus status) {
        this.status = status;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public OffsetDateTime getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(OffsetDateTime attemptedAt) {
        this.attemptedAt = attemptedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
