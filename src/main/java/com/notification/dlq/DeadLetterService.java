package com.notification.dlq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.api.dto.DlqActionResponse;
import com.notification.api.dto.DlqAttemptDto;
import com.notification.api.dto.DlqNotificationDetailResponse;
import com.notification.api.dto.DlqNotificationSummaryResponse;
import com.notification.api.dto.PageResponse;
import com.notification.application.NotificationService;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationAttempt;
import com.notification.domain.NotificationStatus;
import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class DeadLetterService {

    private static final Logger LOG = Logger.getLogger(DeadLetterService.class);

    @Inject
    public NotificationRepository notificationRepository;

    @Inject
    public NotificationAttemptRepository notificationAttemptRepository;

    @Inject
    public OutboxEventRepository outboxEventRepository;

    @Inject
    public ObjectMapper objectMapper;

    public DeadLetterService() {
    }

    public DeadLetterService(NotificationRepository notificationRepository,
                             NotificationAttemptRepository notificationAttemptRepository,
                             OutboxEventRepository outboxEventRepository,
                             ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.notificationAttemptRepository = notificationAttemptRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public DeadLetterService(NotificationRepository notificationRepository,
                             OutboxEventRepository outboxEventRepository,
                             ObjectMapper objectMapper) {
        this(notificationRepository, null, outboxEventRepository, objectMapper);
    }

    /**
     * Atomically transitions notification to DEAD_LETTER and persists a DLQ event in Outbox.
     * Prevents duplicate DLQ events under concurrent execution or restart.
     */
    public boolean routeToDlq(
            Notification notification,
            DlqReason reason,
            String errorMessage,
            Integer httpStatusCode,
            String providerName,
            Map<String, Object> additionalMetadata) {

        if (notification == null) {
            LOG.warn("Cannot route null notification to DLQ");
            return false;
        }

        // Idempotency check: if notification is already in DEAD_LETTER, do not duplicate DLQ event
        if (notification.getStatus() == NotificationStatus.DEAD_LETTER) {
            LOG.infof("Notification [%s] is already in DEAD_LETTER status. Skipping duplicate DLQ routing.", notification.getId());
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now();
        notification.setStatus(NotificationStatus.DEAD_LETTER);
        notification.setUpdatedAt(now);

        Map<String, Object> meta = new LinkedHashMap<>();
        if (additionalMetadata != null) {
            meta.putAll(additionalMetadata);
        }
        meta.put("routedAt", now.toString());
        meta.put("originalCreatedAt", notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null);

        DlqMessage dlqMessage = new DlqMessage(
                notification.getId(),
                notification.getRecipient(),
                notification.getChannel(),
                notification.getSubject(),
                notification.getContent(),
                notification.getPriority(),
                providerName != null ? providerName : (notification.getProvider() != null ? notification.getProvider().getName() : null),
                notification.getRetryCount() != null ? notification.getRetryCount() : 0,
                errorMessage,
                httpStatusCode,
                reason != null ? reason : DlqReason.FATAL_EXCEPTION,
                now,
                notification.getCreatedAt(),
                meta
        );

        String payload = serializeDlqMessage(dlqMessage);

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateId(notification.getId());
        outboxEvent.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_DEAD_LETTER);
        outboxEvent.setPayload(payload);
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setCreatedAt(now);
        outboxEvent.setScheduledAt(now);
        outboxEvent.setPublishedAt(null);

        outboxEventRepository.persist(outboxEvent);

        LOG.errorf("Notification [%s] routed to DEAD_LETTER (Reason: %s, Provider: %s, Error: %s)",
                notification.getId(), reason, providerName, errorMessage);

        return true;
    }

    /**
     * List / paginate notifications in DEAD_LETTER status with optional channel & recipient filters.
     */
    public PageResponse<DlqNotificationSummaryResponse> getDlqNotifications(Channel channel, String recipient, int page, int size) {
        int effectivePage = Math.max(0, page);
        int effectiveSize = (size <= 0) ? 20 : Math.min(size, 100);

        PanacheQuery<Notification> query = notificationRepository.findDlq(channel, recipient);
        long totalElements = query.count();
        List<Notification> list = query.page(Page.of(effectivePage, effectiveSize)).list();

        List<DlqNotificationSummaryResponse> items = list.stream().map(n -> new DlqNotificationSummaryResponse(
                n.getId(),
                n.getRecipient(),
                n.getChannel(),
                n.getSubject(),
                n.getPriority(),
                n.getStatus(),
                n.getProvider() != null ? n.getProvider().getName() : null,
                n.getRetryCount(),
                n.getCreatedAt(),
                n.getUpdatedAt()
        )).collect(Collectors.toList());

        return new PageResponse<>(items, effectivePage, effectiveSize, totalElements);
    }

    /**
     * Get details of a single notification in DLQ along with its attempt history.
     */
    public DlqNotificationDetailResponse getDlqDetail(UUID id) {
        Notification notification = notificationRepository.findById(id);
        if (notification == null) {
            throw new NotFoundException("Notification not found in DLQ with id: " + id);
        }

        if (notification.getStatus() != NotificationStatus.DEAD_LETTER) {
            throw new IllegalStateException("Notification [" + id + "] is in status [" + notification.getStatus() + "], not in DEAD_LETTER");
        }

        List<NotificationAttempt> attempts = Collections.emptyList();
        if (notificationAttemptRepository != null) {
            attempts = notificationAttemptRepository.findByNotificationId(id);
        }

        List<DlqAttemptDto> attemptDtos = attempts.stream().map(a -> new DlqAttemptDto(
                a.getId(),
                a.getAttemptNumber(),
                a.getStatus(),
                a.getProvider() != null ? a.getProvider().getName() : null,
                a.getErrorMessage(),
                a.getAttemptedAt(),
                a.getCompletedAt()
        )).collect(Collectors.toList());

        return new DlqNotificationDetailResponse(
                notification.getId(),
                notification.getRecipient(),
                notification.getChannel(),
                notification.getSubject(),
                notification.getContent(),
                notification.getPriority(),
                notification.getStatus(),
                notification.getProvider() != null ? notification.getProvider().getName() : null,
                notification.getRetryCount(),
                notification.getCreatedAt(),
                notification.getUpdatedAt(),
                attemptDtos
        );
    }

    /**
     * Re-queues a DEAD_LETTER notification back into the normal processing flow.
     * Resets retry count to 0 for a fresh retry budget while keeping attempt history intact.
     */
    @Transactional
    public DlqActionResponse retryDlqNotification(UUID id) {
        Notification notification = notificationRepository.findByIdForUpdate(id);
        if (notification == null) {
            throw new NotFoundException("Notification not found in DLQ with id: " + id);
        }

        if (notification.getStatus() != NotificationStatus.DEAD_LETTER) {
            throw new IllegalStateException("Cannot retry notification [" + id + "] with status [" + notification.getStatus() + "]. Only DEAD_LETTER notifications can be retried.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setRetryCount(0);
        notification.setUpdatedAt(now);

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateId(notification.getId());
        outboxEvent.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED);
        outboxEvent.setPayload(serializeNotificationPayload(notification));
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setCreatedAt(now);
        outboxEvent.setScheduledAt(now);
        outboxEvent.setPublishedAt(null);

        outboxEventRepository.persist(outboxEvent);

        LOG.infof("Notification [%s] successfully retried from DLQ and re-queued into Outbox", id);

        return new DlqActionResponse(id, NotificationStatus.QUEUED, "Notification successfully re-queued for processing");
    }

    /**
     * Cancels a DEAD_LETTER notification permanently so it is never re-queued or sent again.
     */
    @Transactional
    public DlqActionResponse cancelDlqNotification(UUID id) {
        Notification notification = notificationRepository.findByIdForUpdate(id);
        if (notification == null) {
            throw new NotFoundException("Notification not found in DLQ with id: " + id);
        }

        if (notification.getStatus() != NotificationStatus.DEAD_LETTER) {
            throw new IllegalStateException("Cannot cancel notification [" + id + "] with status [" + notification.getStatus() + "]. Only DEAD_LETTER notifications can be cancelled.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        notification.setStatus(NotificationStatus.CANCELLED);
        notification.setUpdatedAt(now);

        LOG.infof("Notification [%s] successfully cancelled from DLQ", id);

        return new DlqActionResponse(id, NotificationStatus.CANCELLED, "Notification successfully cancelled from DLQ");
    }

    /**
     * Retries all DEAD_LETTER notifications (optionally filtered by channel).
     * Re-queues them into outbox with fresh retry budget.
     */
    @Transactional
    public com.notification.api.dto.DlqBulkActionResponse retryAllDlq(Channel channel) {
        PanacheQuery<Notification> query = notificationRepository.findDlq(channel, null);
        List<Notification> list = query.list();
        if (list.isEmpty()) {
            return new com.notification.api.dto.DlqBulkActionResponse(0, NotificationStatus.QUEUED.name(), "No DEAD_LETTER notifications found to retry");
        }

        OffsetDateTime now = OffsetDateTime.now();
        int count = 0;
        for (Notification notification : list) {
            notification.setStatus(NotificationStatus.QUEUED);
            notification.setRetryCount(0);
            notification.setUpdatedAt(now);

            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.setId(UUID.randomUUID());
            outboxEvent.setAggregateId(notification.getId());
            outboxEvent.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED);
            outboxEvent.setPayload(serializeNotificationPayload(notification));
            outboxEvent.setStatus(OutboxStatus.PENDING);
            outboxEvent.setCreatedAt(now);
            outboxEvent.setScheduledAt(now);
            outboxEvent.setPublishedAt(null);

            outboxEventRepository.persist(outboxEvent);
            count++;
        }

        LOG.infof("Bulk retry completed: %d notification(s) re-queued from DLQ into Outbox", count);
        return new com.notification.api.dto.DlqBulkActionResponse(count, NotificationStatus.QUEUED.name(), String.format("Successfully retried %d notification(s)", count));
    }

    /**
     * Cancels all DEAD_LETTER notifications (optionally filtered by channel).
     */
    @Transactional
    public com.notification.api.dto.DlqBulkActionResponse cancelAllDlq(Channel channel) {
        PanacheQuery<Notification> query = notificationRepository.findDlq(channel, null);
        List<Notification> list = query.list();
        if (list.isEmpty()) {
            return new com.notification.api.dto.DlqBulkActionResponse(0, NotificationStatus.CANCELLED.name(), "No DEAD_LETTER notifications found to cancel");
        }

        OffsetDateTime now = OffsetDateTime.now();
        int count = 0;
        for (Notification notification : list) {
            notification.setStatus(NotificationStatus.CANCELLED);
            notification.setUpdatedAt(now);
            count++;
        }

        LOG.infof("Bulk cancel completed: %d notification(s) cancelled from DLQ", count);
        return new com.notification.api.dto.DlqBulkActionResponse(count, NotificationStatus.CANCELLED.name(), String.format("Successfully cancelled %d notification(s)", count));
    }

    private String serializeNotificationPayload(Notification notification) {
        try {
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            payloadMap.put("id", notification.getId().toString());
            payloadMap.put("recipient", notification.getRecipient());
            payloadMap.put("channel", notification.getChannel() != null ? notification.getChannel().name() : null);
            payloadMap.put("subject", notification.getSubject());
            payloadMap.put("content", notification.getContent());
            payloadMap.put("priority", notification.getPriority() != null ? notification.getPriority().name() : null);
            payloadMap.put("status", notification.getStatus().name());
            payloadMap.put("retryCount", notification.getRetryCount());
            payloadMap.put("providerId", notification.getProvider() != null ? notification.getProvider().getId().toString() : null);
            payloadMap.put("createdAt", notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null);
            return objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize notification payload for outbox: " + notification.getId(), e);
        }
    }

    private String serializeDlqMessage(DlqMessage dlqMessage) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("notificationId", dlqMessage.getNotificationId() != null ? dlqMessage.getNotificationId().toString() : null);
            map.put("recipient", dlqMessage.getRecipient());
            map.put("channel", dlqMessage.getChannel() != null ? dlqMessage.getChannel().name() : null);
            map.put("subject", dlqMessage.getSubject());
            map.put("content", dlqMessage.getContent());
            map.put("priority", dlqMessage.getPriority() != null ? dlqMessage.getPriority().name() : null);
            map.put("provider", dlqMessage.getProvider());
            map.put("retryCount", dlqMessage.getRetryCount());
            map.put("errorMessage", dlqMessage.getErrorMessage());
            map.put("httpStatusCode", dlqMessage.getHttpStatusCode());
            map.put("reason", dlqMessage.getReason() != null ? dlqMessage.getReason().name() : null);
            map.put("failedAt", dlqMessage.getFailedAt() != null ? dlqMessage.getFailedAt().toString() : null);
            map.put("createdAt", dlqMessage.getCreatedAt() != null ? dlqMessage.getCreatedAt().toString() : null);
            map.put("metadata", dlqMessage.getMetadata());
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize DLQ message for notification: " + dlqMessage.getNotificationId(), e);
        }
    }
}
