package com.notification.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Inject
    OutboxEventRepository outboxEventRepository;

    @Inject
    KafkaProducerService kafkaProducerService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "outbox.publisher.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "outbox.publisher.batch-size", defaultValue = "50")
    int batchSize;

    @Scheduled(every = "${outbox.publisher.interval:2s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void runScheduled() {
        if (enabled) {
            processPendingEvents();
        }
    }

    public int processPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(batchSize);
        if (pendingEvents.isEmpty()) {
            return 0;
        }

        LOG.debugf("Found %d pending outbox event(s) to publish", pendingEvents.size());
        int publishedCount = 0;

        for (OutboxEvent event : pendingEvents) {
            boolean success = publishSingleEvent(event);
            if (success) {
                publishedCount++;
            }
        }

        return publishedCount;
    }

    private boolean publishSingleEvent(OutboxEvent event) {
        String topic = resolveTopic(event);
        String key = event.getAggregateId() != null ? event.getAggregateId().toString() : event.getId().toString();
        String payload = event.getPayload();

        try {
            LOG.debugf("Publishing outbox event [%s] (aggregate: %s) to topic [%s]", event.getId(), key, topic);
            Future<RecordMetadata> future = kafkaProducerService.send(topic, key, payload);

            // Wait for broker ACK confirmation
            RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);

            LOG.debugf("Event [%s] published to [%s:%d] at offset %d",
                    event.getId(), metadata.topic(), metadata.partition(), metadata.offset());

            // Mark event as PUBLISHED in atomic independent transaction
            markAsPublished(event.getId());
            return true;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish outbox event [%s] to topic [%s]. Event will remain PENDING.", event.getId(), topic);
            return false;
        }
    }

    public void markAsPublished(UUID outboxEventId) {
        QuarkusTransaction.requiringNew().run(() -> {
            outboxEventRepository.update("status = ?1, publishedAt = ?2 WHERE id = ?3 AND status = ?4",
                    OutboxStatus.PUBLISHED, OffsetDateTime.now(), outboxEventId, OutboxStatus.PENDING);
        });
    }

    public String resolveTopic(OutboxEvent event) {
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
