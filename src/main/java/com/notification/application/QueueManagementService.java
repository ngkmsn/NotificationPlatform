package com.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.api.dto.PageResponse;
import com.notification.api.dto.QueueActionResponse;
import com.notification.api.dto.QueueBulkActionResponse;
import com.notification.api.dto.QueuedNotificationSummaryResponse;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import com.notification.domain.Priority;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class QueueManagementService {

    private static final Logger LOG = Logger.getLogger(QueueManagementService.class);

    @Inject
    NotificationRepository notificationRepository;

    @Inject
    OutboxEventRepository outboxEventRepository;

    @Inject
    ObjectMapper objectMapper;

    public PageResponse<QueuedNotificationSummaryResponse> getQueuedNotifications(
            Channel channel, Priority priority, String recipient, int page, int size) {

        int validPage = Math.max(0, page);
        int validSize = (size <= 0) ? 20 : Math.min(100, size);

        PanacheQuery<Notification> query = notificationRepository.findQueued(channel, priority, recipient);
        long totalElements = query.count();
        int totalPages = (int) Math.ceil((double) totalElements / validSize);

        List<Notification> items = query.page(Page.of(validPage, validSize)).list();
        OffsetDateTime now = OffsetDateTime.now();

        List<QueuedNotificationSummaryResponse> dtos = items.stream().map(n -> {
            long lagSeconds = n.getCreatedAt() != null ? Math.max(0, Duration.between(n.getCreatedAt(), now).getSeconds()) : 0;
            return new QueuedNotificationSummaryResponse(
                    n.getId(),
                    n.getRecipient(),
                    n.getChannel(),
                    n.getPriority(),
                    n.getSubject(),
                    n.getStatus().name(),
                    n.getCreatedAt(),
                    n.getUpdatedAt(),
                    lagSeconds
            );
        }).toList();

        return new PageResponse<>(dtos, validPage, validSize, totalElements);
    }

    @Transactional
    public QueueBulkActionResponse reEnqueueAll(Channel channel, Priority priority) {
        PanacheQuery<Notification> query = notificationRepository.findQueued(channel, priority, null);
        List<Notification> queuedList = query.list();

        if (queuedList.isEmpty()) {
            return new QueueBulkActionResponse(0, "RE_ENQUEUE_ALL", "No queued notifications found to re-enqueue.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        int count = 0;

        for (Notification n : queuedList) {
            // Create a fresh pending outbox event so OutboxPublisher can publish it to Kafka
            OutboxEvent event = new OutboxEvent();
            event.setId(UUID.randomUUID());
            event.setAggregateId(n.getId());
            event.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED);
            event.setPayload(serializeNotificationPayload(n));
            event.setStatus(OutboxStatus.PENDING);
            event.setCreatedAt(now);
            event.setScheduledAt(now);
            event.setPublishedAt(null);

            outboxEventRepository.persist(event);
            n.setUpdatedAt(now);
            count++;
        }

        LOG.infof("Successfully re-enqueued %d stuck in-flight notification(s) into Outbox", count);
        return new QueueBulkActionResponse(count, "RE_ENQUEUE_ALL", String.format("Successfully re-enqueued %d notification(s) into Outbox queue.", count));
    }

    @Transactional
    public QueueBulkActionResponse cancelAll(Channel channel, Priority priority) {
        PanacheQuery<Notification> query = notificationRepository.findQueued(channel, priority, null);
        List<Notification> queuedList = query.list();

        if (queuedList.isEmpty()) {
            return new QueueBulkActionResponse(0, "CANCEL_ALL", "No queued notifications found to cancel.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        int count = 0;

        for (Notification n : queuedList) {
            n.setStatus(NotificationStatus.CANCELLED);
            n.setUpdatedAt(now);
            count++;
        }

        // Also cancel any corresponding pending outbox events
        outboxEventRepository.update("status = ?1 WHERE status = ?2", OutboxStatus.FAILED, OutboxStatus.PENDING);

        LOG.infof("Successfully cancelled %d in-flight notification(s)", count);
        return new QueueBulkActionResponse(count, "CANCEL_ALL", String.format("Successfully cancelled %d queued notification(s).", count));
    }

    @Transactional
    public QueueActionResponse reEnqueueSingle(UUID id) {
        Notification notification = notificationRepository.findByIdForUpdate(id);
        if (notification == null) {
            throw new NotFoundException("Notification not found with id: " + id);
        }

        if (notification.getStatus() == NotificationStatus.DELIVERED) {
            return new QueueActionResponse(id, notification.getStatus(), "Notification has already been delivered.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setUpdatedAt(now);

        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateId(notification.getId());
        event.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED);
        event.setPayload(serializeNotificationPayload(notification));
        event.setStatus(OutboxStatus.PENDING);
        event.setCreatedAt(now);
        event.setScheduledAt(now);
        event.setPublishedAt(null);

        outboxEventRepository.persist(event);

        LOG.infof("Notification [%s] re-enqueued into Outbox for immediate processing", id);
        return new QueueActionResponse(id, NotificationStatus.QUEUED, "Notification re-enqueued successfully.");
    }

    @Transactional
    public QueueActionResponse cancelSingle(UUID id) {
        Notification notification = notificationRepository.findByIdForUpdate(id);
        if (notification == null) {
            throw new NotFoundException("Notification not found with id: " + id);
        }

        OffsetDateTime now = OffsetDateTime.now();
        notification.setStatus(NotificationStatus.CANCELLED);
        notification.setUpdatedAt(now);

        LOG.infof("Notification [%s] cancelled by administrator", id);
        return new QueueActionResponse(id, NotificationStatus.CANCELLED, "Notification cancelled successfully.");
    }

    @Transactional
    public QueueActionResponse promoteToCritical(UUID id) {
        Notification notification = notificationRepository.findByIdForUpdate(id);
        if (notification == null) {
            throw new NotFoundException("Notification not found with id: " + id);
        }

        OffsetDateTime now = OffsetDateTime.now();
        notification.setPriority(Priority.CRITICAL);
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setUpdatedAt(now);

        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateId(notification.getId());
        event.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED);
        event.setPayload(serializeNotificationPayload(notification));
        event.setStatus(OutboxStatus.PENDING);
        event.setCreatedAt(now);
        event.setScheduledAt(now);
        event.setPublishedAt(null);

        outboxEventRepository.persist(event);

        LOG.infof("Notification [%s] promoted to CRITICAL and dispatched to high-priority Kafka topic", id);
        return new QueueActionResponse(id, NotificationStatus.QUEUED, "Notification promoted to CRITICAL and re-enqueued.");
    }

    private String serializeNotificationPayload(Notification notification) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", notification.getId().toString());
            map.put("recipient", notification.getRecipient());
            map.put("channel", notification.getChannel() != null ? notification.getChannel().name() : null);
            map.put("priority", notification.getPriority() != null ? notification.getPriority().name() : "NORMAL");
            map.put("subject", notification.getSubject());
            map.put("content", notification.getContent());
            map.put("status", notification.getStatus() != null ? notification.getStatus().name() : "QUEUED");
            map.put("retryCount", notification.getRetryCount() != null ? notification.getRetryCount() : 0);
            map.put("createdAt", notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : OffsetDateTime.now().toString());

            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize notification payload for outbox", e);
        }
    }
}
