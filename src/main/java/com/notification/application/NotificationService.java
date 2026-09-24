package com.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import com.notification.domain.Priority;
import com.notification.domain.UserDevice;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import com.notification.repository.UserDeviceRepository;
import com.notification.metrics.NotificationMetrics;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class);

    public static final String EVENT_TYPE_NOTIFICATION_CREATED = "NOTIFICATION_CREATED";
    public static final String EVENT_TYPE_NOTIFICATION_DEAD_LETTER = "NOTIFICATION_DEAD_LETTER";

    @Inject
    NotificationRepository notificationRepository;

    @Inject
    OutboxEventRepository outboxEventRepository;

    @Inject
    UserDeviceRepository userDeviceRepository;

    @Inject
    NotificationMetrics notificationMetrics;

    @Inject
    ObjectMapper objectMapper;

    @Transactional
    @WithSpan("createNotification")
    public CreateNotificationResponse createNotification(CreateNotificationRequest request) {
        return createNotificationInternal(request, false);
    }

    @Transactional
    public CreateNotificationResponse createNotificationWithSimulatedFailure(CreateNotificationRequest request) {
        return createNotificationInternal(request, true);
    }

    private CreateNotificationResponse createNotificationInternal(CreateNotificationRequest request,
            boolean simulateFailure) {
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

        String rawRecipient = request.getRecipient().trim();

        // 1. Check if broadcast to all active devices
        if (channel == Channel.PUSH && (rawRecipient.equalsIgnoreCase("ALL")
                || rawRecipient.equalsIgnoreCase("BROADCAST") || rawRecipient.equalsIgnoreCase("@ALL"))) {
            List<UserDevice> allDevices = userDeviceRepository.findAllActiveDevices();
            if (!allDevices.isEmpty()) {
                UUID primaryId = null;
                NotificationStatus primaryStatus = null;
                for (UserDevice device : allDevices) {
                    Notification notification = persistNotificationAndOutbox(device.getDeviceToken(), channel, priority,
                            request.getSubject(), request.getContent());
                    if (primaryId == null) {
                        primaryId = notification.getId();
                        primaryStatus = notification.getStatus();
                    }
                }
                if (simulateFailure) {
                    throw new RuntimeException("Simulated unexpected failure to trigger transaction rollback");
                }
                return new CreateNotificationResponse(primaryId, primaryStatus);
            }
        }

        // 2. Parse comma/semicolon separated recipients
        String[] recipientList = rawRecipient.split("[,;]");
        UUID primaryId = null;
        NotificationStatus primaryStatus = null;

        for (String singleRecipient : recipientList) {
            String trimmed = singleRecipient.trim();
            if (trimmed.isBlank())
                continue;

            if (channel == Channel.PUSH) {
                List<UserDevice> activeDevices = userDeviceRepository.findActiveDevicesByUserId(trimmed);
                if (!activeDevices.isEmpty()) {
                    for (UserDevice device : activeDevices) {
                        Notification notification = persistNotificationAndOutbox(device.getDeviceToken(), channel,
                                priority, request.getSubject(), request.getContent());
                        if (primaryId == null) {
                            primaryId = notification.getId();
                            primaryStatus = notification.getStatus();
                        }
                    }
                    continue;
                }
            }

            // Default: direct dispatch to raw recipient (email, phone, or raw FCM token)
            Notification notification = persistNotificationAndOutbox(trimmed, channel, priority, request.getSubject(),
                    request.getContent());
            if (primaryId == null) {
                primaryId = notification.getId();
                primaryStatus = notification.getStatus();
            }
        }

        if (simulateFailure) {
            throw new RuntimeException("Simulated unexpected failure to trigger transaction rollback");
        }

        return new CreateNotificationResponse(primaryId != null ? primaryId : UUID.randomUUID(),
                primaryStatus != null ? primaryStatus : NotificationStatus.QUEUED);
    }

    private Notification persistNotificationAndOutbox(String recipient, Channel channel, Priority priority,
            String subject, String content) {
        // 1. Build notification entity
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setRecipient(recipient);
        notification.setChannel(channel);
        notification.setSubject(subject != null ? subject.trim() : null);
        notification.setContent(content);
        notification.setPriority(priority);
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setRetryCount(0);
        notification.setCreatedAt(OffsetDateTime.now());
        notification.setUpdatedAt(OffsetDateTime.now());

        // 2. Persist Notification in current transaction
        notificationRepository.persist(notification);

        // 3. Build OutboxEvent entity
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateId(notification.getId());
        outboxEvent.setEventType(EVENT_TYPE_NOTIFICATION_CREATED);
        outboxEvent.setPayload(serializePayload(notification));
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setCreatedAt(OffsetDateTime.now());
        outboxEvent.setPublishedAt(null);

        // 4. Persist OutboxEvent in the same transaction
        outboxEventRepository.persist(outboxEvent);

        // 5. Record Prometheus metric
        if (notificationMetrics != null) {
            notificationMetrics.recordCreated(channel, priority);
        }

        return notification;
    }

    private String serializePayload(Notification notification) {
        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("id", notification.getId().toString());
            payloadMap.put("recipient", notification.getRecipient());
            payloadMap.put("channel", notification.getChannel().name());
            payloadMap.put("subject", notification.getSubject());
            payloadMap.put("content", notification.getContent());
            payloadMap.put("priority", notification.getPriority().name());
            payloadMap.put("status", notification.getStatus().name());
            payloadMap.put("retryCount", notification.getRetryCount());
            payloadMap.put("providerId",
                    notification.getProvider() != null ? notification.getProvider().getId().toString() : null);
            payloadMap.put("createdAt", notification.getCreatedAt().toString());
            return objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }

    private Channel parseChannel(String channelStr) {
        if (channelStr == null || channelStr.isBlank()) {
            throw new ValidationException("channel is required");
        }
        try {
            return Channel.valueOf(channelStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "Invalid channel: " + channelStr + ". Supported channels: " + Arrays.toString(Channel.values()));
        }
    }

    private Priority parsePriority(String priorityStr) {
        if (priorityStr == null || priorityStr.isBlank()) {
            return Priority.NORMAL;
        }
        try {
            return Priority.valueOf(priorityStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid priority: " + priorityStr + ". Supported priorities: "
                    + Arrays.toString(Priority.values()));
        }
    }
}
