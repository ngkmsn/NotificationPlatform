package com.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.application.NotificationService;
import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import com.notification.domain.Priority;
import com.notification.repository.OutboxEventRepository;
import com.notification.worker.OutboxPublisher;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class OutboxPublisherTest {

    @Inject
    NotificationService notificationService;

    @Inject
    OutboxEventRepository outboxEventRepository;

    @Inject
    OutboxPublisher outboxPublisher;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "kafka.bootstrap.servers", defaultValue = "localhost:9092")
    String bootstrapServers;

    @Test
    public void testTopicResolutionByPriority() {
        assertEquals("notification-critical", OutboxPublisher.resolveTopicByPriority(Priority.CRITICAL));
        assertEquals("notification-high", OutboxPublisher.resolveTopicByPriority(Priority.HIGH));
        assertEquals("notification-normal", OutboxPublisher.resolveTopicByPriority(Priority.NORMAL));
        assertEquals("notification-low", OutboxPublisher.resolveTopicByPriority(Priority.LOW));
        assertEquals("notification-normal", OutboxPublisher.resolveTopicByPriority(null));
    }

    @Test
    public void testOutboxPublish_AllPriorities_Success() throws Exception {
        // 1. Create notifications with all 4 priorities
        CreateNotificationResponse resCritical = notificationService.createNotification(
                new CreateNotificationRequest("critical@example.com", "SMS", null, "Emergency Alert", "CRITICAL")
        );
        CreateNotificationResponse resHigh = notificationService.createNotification(
                new CreateNotificationRequest("high@example.com", "EMAIL", "Security Warning", "Password change detected", "HIGH")
        );
        CreateNotificationResponse resNormal = notificationService.createNotification(
                new CreateNotificationRequest("normal@example.com", "IN_APP", null, "New comment on your post", "NORMAL")
        );
        CreateNotificationResponse resLow = notificationService.createNotification(
                new CreateNotificationRequest("low@example.com", "EMAIL", "Weekly Newsletter", "Here is your weekly summary", "LOW")
        );

        // 2. Trigger Outbox Publisher
        int processed = outboxPublisher.processPendingEvents();
        assertTrue(processed >= 4, "Should have processed at least 4 outbox events");

        // 3. Verify in PostgreSQL that outbox events are now PUBLISHED
        verifyEventPublished(resCritical.getId(), Priority.CRITICAL);
        verifyEventPublished(resHigh.getId(), Priority.HIGH);
        verifyEventPublished(resNormal.getId(), Priority.NORMAL);
        verifyEventPublished(resLow.getId(), Priority.LOW);

        // 4. Verify Kafka receiving messages on corresponding topics
        verifyKafkaMessageReceived("notification-critical", resCritical.getId().toString());
        verifyKafkaMessageReceived("notification-high", resHigh.getId().toString());
        verifyKafkaMessageReceived("notification-normal", resNormal.getId().toString());
        verifyKafkaMessageReceived("notification-low", resLow.getId().toString());
    }

    private void verifyEventPublished(UUID notificationId, Priority expectedPriority) throws Exception {
        outboxEventRepository.getEntityManager().clear();
        List<OutboxEvent> events = outboxEventRepository.find("aggregateId", notificationId).list();
        assertEquals(1, events.size());

        OutboxEvent event = events.get(0);
        assertEquals(OutboxStatus.PUBLISHED, event.getStatus(), "Outbox event status must be PUBLISHED");
        assertNotNull(event.getPublishedAt(), "publishedAt must not be null after publishing");

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertEquals(notificationId.toString(), payload.get("id").asText());
        assertEquals(expectedPriority.name(), payload.get("priority").asText());
    }

    private void verifyKafkaMessageReceived(String topic, String expectedKey) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            boolean messageFound = false;
            long deadline = System.currentTimeMillis() + 8000;

            while (System.currentTimeMillis() < deadline && !messageFound) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (expectedKey.equals(record.key())) {
                        messageFound = true;
                        assertNotNull(record.value());
                        assertTrue(record.value().contains(expectedKey));
                        break;
                    }
                }
            }

            assertTrue(messageFound, "Message with key " + expectedKey + " should be received on topic " + topic);
        }
    }
}
