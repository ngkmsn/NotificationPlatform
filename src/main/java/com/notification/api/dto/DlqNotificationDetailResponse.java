package com.notification.api.dto;

import com.notification.domain.Channel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.Priority;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class DlqNotificationDetailResponse {

    private UUID id;
    private String recipient;
    private Channel channel;
    private String subject;
    private String content;
    private Priority priority;
    private NotificationStatus status;
    private String provider;
    private Integer retryCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<DlqAttemptDto> attempts;

    public DlqNotificationDetailResponse() {
        this.attempts = Collections.emptyList();
    }

    public DlqNotificationDetailResponse(UUID id, String recipient, Channel channel, String subject,
                                         String content, Priority priority, NotificationStatus status,
                                         String provider, Integer retryCount, OffsetDateTime createdAt,
                                         OffsetDateTime updatedAt, List<DlqAttemptDto> attempts) {
        this.id = id;
        this.recipient = recipient;
        this.channel = channel;
        this.subject = subject;
        this.content = content;
        this.priority = priority;
        this.status = status;
        this.provider = provider;
        this.retryCount = retryCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.attempts = attempts != null ? attempts : Collections.emptyList();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationStatus status) {
        this.status = status;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<DlqAttemptDto> getAttempts() {
        return attempts;
    }

    public void setAttempts(List<DlqAttemptDto> attempts) {
        this.attempts = attempts;
    }
}
