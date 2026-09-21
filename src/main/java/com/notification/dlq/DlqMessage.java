package com.notification.dlq;

import com.notification.domain.Channel;
import com.notification.domain.Priority;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Rich payload structure for messages routed to Kafka Dead Letter Queue (DLQ).
 * Contains all original notification details, error diagnosis, retry count, and metadata
 * necessary for troubleshooting and reprocessing.
 */
public class DlqMessage {

    private UUID notificationId;
    private String recipient;
    private Channel channel;
    private String subject;
    private String content;
    private Priority priority;
    private String provider;
    private int retryCount;
    private String errorMessage;
    private Integer httpStatusCode;
    private DlqReason reason;
    private OffsetDateTime failedAt;
    private OffsetDateTime createdAt;
    private Map<String, Object> metadata;

    public DlqMessage() {
    }

    public DlqMessage(
            UUID notificationId,
            String recipient,
            Channel channel,
            String subject,
            String content,
            Priority priority,
            String provider,
            int retryCount,
            String errorMessage,
            Integer httpStatusCode,
            DlqReason reason,
            OffsetDateTime failedAt,
            OffsetDateTime createdAt,
            Map<String, Object> metadata) {
        this.notificationId = notificationId;
        this.recipient = recipient;
        this.channel = channel;
        this.subject = subject;
        this.content = content;
        this.priority = priority;
        this.provider = provider;
        this.retryCount = retryCount;
        this.errorMessage = errorMessage;
        this.httpStatusCode = httpStatusCode;
        this.reason = reason;
        this.failedAt = failedAt;
        this.createdAt = createdAt;
        this.metadata = metadata;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(UUID notificationId) {
        this.notificationId = notificationId;
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

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(Integer httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public DlqReason getReason() {
        return reason;
    }

    public void setReason(DlqReason reason) {
        this.reason = reason;
    }

    public OffsetDateTime getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(OffsetDateTime failedAt) {
        this.failedAt = failedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
