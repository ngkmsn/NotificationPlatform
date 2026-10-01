package com.notification.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.application.NotificationService;
import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import com.notification.domain.Priority;
import com.notification.kafka.KafkaProducerService;
import com.notification.repository.OutboxEventRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class OutboxPublisher {

    private static final Logger LOG = Logger.getLogger(OutboxPublisher.class);

    public static final String TOPIC_CRITICAL = "notification-critical";
    public static final String TOPIC_HIGH = "notification-high";
    public static final String TOPIC_NORMAL = "notification-normal";
    public static final String TOPIC_LOW = "notification-low";
    public static final String TOPIC_DLQ = "notification-dlq";

    @Inject
    OutboxEventRepository outboxEventRepository;

    @Inject
    KafkaProducerService kafkaProducerService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    com.notification.guard.SystemLoadGuard systemLoadGuard;

    @ConfigProperty(name = "outbox.publisher.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "outbox.publisher.batch-size", defaultValue = "50")
    int batchSize;

    @ConfigProperty(name = "notification.dlq.topic", defaultValue = "notification-dlq")
    String dlqTopic;

    @Scheduled(every = "${outbox.publisher.interval:2s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void runScheduled() {
        if (enabled) {
            processPendingEvents();
        }
    }

    public int processPendingEvents() {
        // Emergency Cutoff (95% load): Pause background publishing for general traffic,
        // BUT allow a Lifeboat Fast-Path for CRITICAL emergency alerts (e.g. Admin Warning/Cutoff notifications)
        if (systemLoadGuard != null && systemLoadGuard.isCutoff()) {
            List<OutboxEvent> dueEvents = outboxEventRepository.findDuePendingEvents(10);
            List<OutboxEvent> criticalEvents = dueEvents.stream()
                    .filter(this::isCriticalEvent)
                    .limit(2)
                    .toList();
            if (criticalEvents.isEmpty()) {
                LOG.warnf("🚨 [OUTBOX PAUSED] SystemLoadGuard in CRITICAL_CUTOFF (CPU: %.1f%%, RAM: %.1f%%). Pausing OutboxPublisher for normal traffic.",
                        systemLoadGuard.getCurrentCpuUsage(), systemLoadGuard.getCurrentRamUsage());
                return 0;
            }
            LOG.infof("🚨 [LIFEBOAT DISPATCH] SystemLoadGuard in CRITICAL_CUTOFF, dispatching %d CRITICAL emergency event(s) to notify admin.",
                    criticalEvents.size());
            return publishEvents(criticalEvents);
        }

        // Adaptively reduce batch size when hardware load is throttled (85%) to reduce I/O pressure
        int effectiveBatchSize = (systemLoadGuard != null && systemLoadGuard.isThrottled())
                ? Math.max(5, batchSize / 5)
                : batchSize;

        List<OutboxEvent> pendingEvents = outboxEventRepository.findDuePendingEvents(effectiveBatchSize);
        if (pendingEvents.isEmpty()) {
            return 0;
        }

        LOG.debugf("Found %d pending outbox event(s) to publish", pendingEvents.size());
        return publishEvents(pendingEvents);
    }

    private boolean isCriticalEvent(OutboxEvent event) {
        if (event == null || event.getPayload() == null) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            String priorityStr = node.path("priority").asText("");
            return "CRITICAL".equalsIgnoreCase(priorityStr);
        } catch (Exception e) {
            return false;
        }
    }

    private int publishEvents(List<OutboxEvent> events) {
        List<UUID> successfulIds = new java.util.ArrayList<>();
        List<java.util.Map.Entry<OutboxEvent, Future<RecordMetadata>>> inFlight = new java.util.ArrayList<>();

        // 1. Asynchronously dispatch all messages to Kafka producer buffer
        for (OutboxEvent event : events) {
            String topic = resolveTopic(event);
            String key = event.getAggregateId() != null ? event.getAggregateId().toString() : event.getId().toString();
            String payload = event.getPayload();
            try {
                Future<RecordMetadata> future = kafkaProducerService.send(topic, key, payload);
                inFlight.add(new java.util.AbstractMap.SimpleEntry<>(event, future));
            } catch (Exception e) {
                LOG.errorf(e, "Failed to initiate send for outbox event [%s]", event.getId());
            }
        }

        // 2. Await broker ACKs
        for (java.util.Map.Entry<OutboxEvent, Future<RecordMetadata>> entry : inFlight) {
            OutboxEvent event = entry.getKey();
            try {
                RecordMetadata metadata = entry.getValue().get(5, TimeUnit.SECONDS);
                successfulIds.add(event.getId());
            } catch (Exception e) {
                LOG.errorf(e, "Failed to confirm broker ACK for outbox event [%s]. Will remain PENDING.", event.getId());
            }
        }

        // 3. Batch update Outbox table in single atomic transaction
        if (!successfulIds.isEmpty()) {
            QuarkusTransaction.requiringNew().run(() -> {
                outboxEventRepository.markBatchAsPublished(successfulIds);
            });
        }

        return successfulIds.size();
    }

    public String resolveTopic(OutboxEvent event) {
        if (event == null) {
            return TOPIC_NORMAL;
        }

        if (NotificationService.EVENT_TYPE_NOTIFICATION_DEAD_LETTER.equalsIgnoreCase(event.getEventType())) {
            return dlqTopic != null && !dlqTopic.isBlank() ? dlqTopic : TOPIC_DLQ;
        }

        if (event.getPayload() == null || event.getPayload().isBlank()) {
            return TOPIC_NORMAL;
        }

        try {
            JsonNode node = objectMapper.readTree(event.getPayload());
            String priorityStr = node.path("priority").asText("NORMAL");
            Priority priority = Priority.valueOf(priorityStr.toUpperCase());

            return resolveTopicByPriority(priority);
        } catch (Exception e) {
            LOG.warnf("Could not extract priority from payload for outbox event [%s]. Defaulting to NORMAL topic.", event.getId());
            return TOPIC_NORMAL;
        }
    }

    public static String resolveTopicByPriority(Priority priority) {
        if (priority == null) {
            return TOPIC_NORMAL;
        }
        return switch (priority) {
            case CRITICAL -> TOPIC_CRITICAL;
            case HIGH -> TOPIC_HIGH;
            case NORMAL -> TOPIC_NORMAL;
            case LOW -> TOPIC_LOW;
        };
    }
}
