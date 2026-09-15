package com.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.application.NotificationService;
import com.notification.domain.AttemptStatus;
import com.notification.domain.Notification;
import com.notification.domain.NotificationAttempt;
import com.notification.domain.NotificationStatus;
import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import com.notification.provider.MockNotificationProvider;
import com.notification.worker.NotificationProcessor;
import com.notification.worker.OutboxPublisher;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class NotificationWorkerTest {

    @Inject
    NotificationService notificationService;

    @Inject
    NotificationRepository notificationRepository;

    @Inject
    NotificationAttemptRepository notificationAttemptRepository;

    @Inject
    OutboxEventRepository outboxEventRepository;

    @Inject
    OutboxPublisher outboxPublisher;

    @Inject
    NotificationProcessor notificationProcessor;

    @Inject
    MockNotificationProvider mockNotificationProvider;

    @Inject
    ObjectMapper objectMapper;

    @org.junit.jupiter.api.BeforeEach
    public void setup() {
        mockNotificationProvider.setEnabled(true);
        mockNotificationProvider.resetSimulationMode();
    }


    @Test
    public void testEndToEndFlow_NotificationCreation_To_WorkerDelivery() throws Exception {
        // 1. Create notifications across different priorities
        CreateNotificationResponse resCritical = notificationService.createNotification(
                new CreateNotificationRequest("worker-crit@example.com", "SMS", null, "Critical Worker Alert", "CRITICAL")
        );
        CreateNotificationResponse resHigh = notificationService.createNotification(
                new CreateNotificationRequest("worker-high@example.com", "EMAIL", "High Alert", "High Worker Alert", "HIGH")
        );
        CreateNotificationResponse resNormal = notificationService.createNotification(
                new CreateNotificationRequest("worker-norm@example.com", "IN_APP", null, "Normal Worker Alert", "NORMAL")
        );
        CreateNotificationResponse resLow = notificationService.createNotification(
                new CreateNotificationRequest("worker-low@example.com", "EMAIL", "Weekly Digest", "Low Worker Alert", "LOW")
        );

        UUID idCrit = resCritical.getId();
        UUID idHigh = resHigh.getId();
        UUID idNorm = resNormal.getId();
        UUID idLow = resLow.getId();

        // 2. Verify Initial State: Notification is QUEUED, Outbox is PENDING
        verifyInitialState(idCrit);
        verifyInitialState(idHigh);
        verifyInitialState(idNorm);
        verifyInitialState(idLow);

        // 3. Trigger Outbox Publisher -> Publishes to 4 Kafka topics -> Outbox becomes PUBLISHED
        outboxPublisher.processPendingEvents();

        // 4. Verify Outbox is PUBLISHED
        verifyOutboxPublished(idCrit);
        verifyOutboxPublished(idHigh);
        verifyOutboxPublished(idNorm);
        verifyOutboxPublished(idLow);

        // 5. Wait for Worker to consume from Kafka topics and mark Notification as DELIVERED
        awaitNotificationDelivered(idCrit);
        awaitNotificationDelivered(idHigh);
        awaitNotificationDelivered(idNorm);
        awaitNotificationDelivered(idLow);

        // 6. Verify Notification Attempts in PostgreSQL
        verifySuccessfulAttempt(idCrit);
        verifySuccessfulAttempt(idHigh);
        verifySuccessfulAttempt(idNorm);
        verifySuccessfulAttempt(idLow);
    }

    @Test
    public void testWorkerIdempotency() {
        // Create and manually process notification
        CreateNotificationResponse res = notificationService.createNotification(
                new CreateNotificationRequest("idempotency@example.com", "SMS", null, "Idempotency Test", "NORMAL")
        );
        UUID notificationId = res.getId();

        // Direct process #1
        notificationProcessor.processNotification(notificationId);

        notificationRepository.getEntityManager().clear();
        Notification n1 = notificationRepository.findById(notificationId);
        assertEquals(NotificationStatus.DELIVERED, n1.getStatus());

        List<NotificationAttempt> attempts1 = notificationAttemptRepository.findByNotificationId(notificationId);
        assertEquals(1, attempts1.size());

        // Direct process #2 (Duplicate message simulation)
        notificationProcessor.processNotification(notificationId);

        notificationRepository.getEntityManager().clear();
        Notification n2 = notificationRepository.findById(notificationId);
        assertEquals(NotificationStatus.DELIVERED, n2.getStatus());

        List<NotificationAttempt> attempts2 = notificationAttemptRepository.findByNotificationId(notificationId);
        assertEquals(1, attempts2.size(), "Duplicate message should be ignored and not produce redundant attempts");
    }

    private void verifyInitialState(UUID id) {
        notificationRepository.getEntityManager().clear();
        Notification n = notificationRepository.findById(id);
        assertNotNull(n);
        assertEquals(NotificationStatus.QUEUED, n.getStatus());

        outboxEventRepository.getEntityManager().clear();
        List<OutboxEvent> events = outboxEventRepository.find("aggregateId", id).list();
        assertEquals(1, events.size());
    }

    private void verifyOutboxPublished(UUID id) {
        outboxEventRepository.getEntityManager().clear();
        List<OutboxEvent> events = outboxEventRepository.find("aggregateId", id).list();
        assertEquals(1, events.size());
        assertEquals(OutboxStatus.PUBLISHED, events.get(0).getStatus());
    }

    private void awaitNotificationDelivered(UUID id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10000;
        boolean delivered = false;

        while (System.currentTimeMillis() < deadline) {
            notificationRepository.getEntityManager().clear();
            Notification notification = notificationRepository.findById(id);
            if (notification != null && notification.getStatus() == NotificationStatus.DELIVERED) {
                delivered = true;
                break;
            }
            Thread.sleep(200);
        }

        assertTrue(delivered, "Notification [" + id + "] should be updated to DELIVERED by Worker");
    }

    private void verifySuccessfulAttempt(UUID id) {
        notificationAttemptRepository.getEntityManager().clear();
        List<NotificationAttempt> attempts = notificationAttemptRepository.findByNotificationId(id);
        assertEquals(1, attempts.size(), "Should have exactly 1 attempt record for notification [" + id + "]");

        NotificationAttempt attempt = attempts.get(0);
        assertEquals(AttemptStatus.SUCCESS, attempt.getStatus());
        assertEquals(1, attempt.getAttemptNumber());
        assertNull(attempt.getErrorMessage());
        assertNotNull(attempt.getAttemptedAt());
        assertNotNull(attempt.getCompletedAt());
        assertTrue(attempt.getCompletedAt().isAfter(attempt.getAttemptedAt()) || attempt.getCompletedAt().isEqual(attempt.getAttemptedAt()));
    }
}
