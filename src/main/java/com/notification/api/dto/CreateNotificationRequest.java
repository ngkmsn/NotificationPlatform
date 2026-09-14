package com.notification.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateNotificationRequest {

    @NotBlank(message = "recipient must not be blank")
    private String recipient;

    @NotNull(message = "channel is required")
    private String channel;

    private String subject;

    @NotBlank(message = "content must not be blank")
    private String content;

    private String priority;

    public CreateNotificationRequest() {
    }

    public CreateNotificationRequest(String recipient, String channel, String subject, String content, String priority) {
        this.recipient = recipient;
        this.channel = channel;
        this.subject = subject;
        this.content = content;
        this.priority = priority;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
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

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }
}
