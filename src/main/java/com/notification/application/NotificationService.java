package com.notification.application;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.domain.Priority;
import com.notification.repository.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

@ApplicationScoped
public class NotificationService {

    @Inject
    NotificationRepository notificationRepository;

    @Transactional
    public CreateNotificationResponse createNotification(CreateNotificationRequest request) {
        // Validate recipient
        if (request.getRecipient() == null || request.getRecipient().isBlank()) {
            throw new ValidationException("recipient must not be blank");
        }

        // Validate content
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new ValidationException("content must not be blank");
        }

        // Validate & parse channel
        Channel channel = parseChannel(request.getChannel());

        // Validate & parse priority (IMP-06)
        Priority priority = parsePriority(request.getPriority());

        // Validate subject for EMAIL channel
        if (channel == Channel.EMAIL && (request.getSubject() == null || request.getSubject().isBlank())) {
            throw new ValidationException("subject is required for EMAIL channel");
        }

        // Build notification entity
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setRecipient(request.getRecipient().trim());
        notification.setChannel(channel);
        notification.setSubject(request.getSubject() != null ? request.getSubject().trim() : null);
        notification.setContent(request.getContent());
        notification.setPriority(priority);
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setCreatedAt(OffsetDateTime.now());
        notification.setUpdatedAt(OffsetDateTime.now());

        // Persist to PostgreSQL
        notificationRepository.persist(notification);

        return new CreateNotificationResponse(notification.getId(), notification.getStatus());
    }

    private Channel parseChannel(String channelStr) {
        if (channelStr == null || channelStr.isBlank()) {
            throw new ValidationException("channel is required");
        }
        try {
            return Channel.valueOf(channelStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid channel: " + channelStr + ". Supported channels: " + Arrays.toString(Channel.values()));
        }
    }

    private Priority parsePriority(String priorityStr) {
        if (priorityStr == null || priorityStr.isBlank()) {
            return Priority.NORMAL;
        }
        try {
            return Priority.valueOf(priorityStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid priority: " + priorityStr + ". Supported priorities: " + Arrays.toString(Priority.values()));
        }
    }
}
