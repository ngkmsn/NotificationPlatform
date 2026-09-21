package com.notification.dlq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.api.dto.DlqActionResponse;
import com.notification.api.dto.DlqNotificationDetailResponse;
import com.notification.api.dto.DlqNotificationSummaryResponse;
import com.notification.api.dto.PageResponse;
import com.notification.application.NotificationService;
import com.notification.domain.*;
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DeadLetterServiceTest {

    private DeadLetterService deadLetterService;
    private NotificationRepository mockNotificationRepository;
    private NotificationAttemptRepository mockNotificationAttemptRepository;
    private OutboxEventRepository mockOutboxEventRepository;
    private ObjectMapper objectMapper;

    @BeforeEach
    public void setup() {
        deadLetterService = new DeadLetterService();
        mockNotificationRepository = mock(NotificationRepository.class);
        mockNotificationAttemptRepository = mock(NotificationAttemptRepository.class);
        mockOutboxEventRepository = mock(OutboxEventRepository.class);
        objectMapper = new ObjectMapper();

        deadLetterService.notificationRepository = mockNotificationRepository;
        deadLetterService.notificationAttemptRepository = mockNotificationAttemptRepository;
        deadLetterService.outboxEventRepository = mockOutboxEventRepository;
        deadLetterService.objectMapper = objectMapper;
    }

    @Test
    @DisplayName("routeToDlq populates rich DLQ payload with all debug and diagnostic metadata")
    public void testRouteToDlqPopulatesRichPayload() throws Exception {
        UUID notificationId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setRecipient("user@example.com");
        notification.setChannel(Channel.EMAIL);
        notification.setSubject("Password Reset");
        notification.setContent("Your reset link is http://...");
        notification.setPriority(Priority.HIGH);
        notification.setStatus(NotificationStatus.RETRYING);
        notification.setRetryCount(3);
        notification.setCreatedAt(OffsetDateTime.now().minusMinutes(5));
        notification.setUpdatedAt(OffsetDateTime.now().minusMinutes(1));

        Map<String, Object> extraMeta = Map.of("circuitBreakerState", "OPEN", "workerHost", "pod-123");

        boolean routed = deadLetterService.routeToDlq(
                notification,
                DlqReason.MAX_RETRIES_EXCEEDED,
                "Gateway Timeout 504",
                504,
                "EmailProvider",
                extraMeta
        );

        assertTrue(routed);
        assertEquals(NotificationStatus.DEAD_LETTER, notification.getStatus());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxEventRepository, times(1)).persist(outboxCaptor.capture());

        OutboxEvent event = outboxCaptor.getValue();
        assertEquals(notificationId, event.getAggregateId());
        assertEquals(NotificationService.EVENT_TYPE_NOTIFICATION_DEAD_LETTER, event.getEventType());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertNotNull(event.getCreatedAt());
        assertNotNull(event.getScheduledAt());

        // Parse and verify rich JSON payload
        JsonNode json = objectMapper.readTree(event.getPayload());
        assertEquals(notificationId.toString(), json.get("notificationId").asText());
        assertEquals("user@example.com", json.get("recipient").asText());
        assertEquals("EMAIL", json.get("channel").asText());
        assertEquals("Password Reset", json.get("subject").asText());
        assertEquals("HIGH", json.get("priority").asText());
        assertEquals("EmailProvider", json.get("provider").asText());
        assertEquals(3, json.get("retryCount").asInt());
        assertEquals("Gateway Timeout 504", json.get("errorMessage").asText());
        assertEquals(504, json.get("httpStatusCode").asInt());
        assertEquals("MAX_RETRIES_EXCEEDED", json.get("reason").asText());
        assertNotNull(json.get("failedAt"));
        assertNotNull(json.get("createdAt"));
        assertEquals("OPEN", json.get("metadata").get("circuitBreakerState").asText());
        assertEquals("pod-123", json.get("metadata").get("workerHost").asText());
    }

    @Test
    @DisplayName("Idempotency guard: duplicate routeToDlq calls on already DEAD_LETTER notification are skipped")
    public void testDuplicateRouteToDlqSkipped() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setStatus(NotificationStatus.DEAD_LETTER);

        boolean routed = deadLetterService.routeToDlq(
                notification,
                DlqReason.MAX_RETRIES_EXCEEDED,
                "Already dead lettered",
                500,
                "ProviderX",
                null
        );

        assertFalse(routed, "Should return false and skip duplicate DLQ routing");
        verify(mockOutboxEventRepository, never()).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Concurrent worker execution only routes to DLQ once per notification")
    public void testConcurrentRoutingDeduplication() throws Exception {
        int threadCount = 20;
        UUID notificationId = UUID.randomUUID();

        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setStatus(NotificationStatus.RETRYING);
        notification.setRetryCount(3);
        notification.setCreatedAt(OffsetDateTime.now());

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean routed;
                    synchronized (notification) {
                        routed = deadLetterService.routeToDlq(
                                notification,
                                DlqReason.NON_RETRYABLE_ERROR,
                                "Bad Request 400",
                                400,
                                "FirebaseProvider",
                                null
                        );
                    }
                    if (routed) {
                        successCount.incrementAndGet();
                    } else {
                        skippedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished);
        assertEquals(1, successCount.get(), "Exactly 1 concurrent worker should succeed in routing to DLQ");
        assertEquals(threadCount - 1, skippedCount.get(), "Remaining workers should skip duplicate DLQ creation");
        assertEquals(NotificationStatus.DEAD_LETTER, notification.getStatus());
        verify(mockOutboxEventRepository, times(1)).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("getDlqNotifications returns paginated DLQ list with correct metadata")
    @SuppressWarnings("unchecked")
    public void testGetDlqNotifications() {
        PanacheQuery<Notification> mockQuery = mock(PanacheQuery.class);
        PanacheQuery<Notification> mockPagedQuery = mock(PanacheQuery.class);

        Notification n1 = new Notification();
        n1.setId(UUID.randomUUID());
        n1.setRecipient("user1@example.com");
        n1.setChannel(Channel.EMAIL);
        n1.setStatus(NotificationStatus.DEAD_LETTER);
        n1.setRetryCount(3);
        n1.setCreatedAt(OffsetDateTime.now());
        n1.setUpdatedAt(OffsetDateTime.now());

        when(mockNotificationRepository.findDlq(Channel.EMAIL, "user1")).thenReturn(mockQuery);
        when(mockQuery.count()).thenReturn(1L);
        when(mockQuery.page(any(Page.class))).thenReturn(mockPagedQuery);
        when(mockPagedQuery.list()).thenReturn(List.of(n1));

        PageResponse<DlqNotificationSummaryResponse> response = deadLetterService.getDlqNotifications(Channel.EMAIL, "user1", 0, 10);

        assertNotNull(response);
        assertEquals(0, response.getPage());
        assertEquals(10, response.getSize());
        assertEquals(1L, response.getTotalElements());
        assertEquals(1, response.getTotalPages());
        assertEquals(1, response.getItems().size());
        assertEquals(n1.getId(), response.getItems().get(0).getId());
        assertEquals("user1@example.com", response.getItems().get(0).getRecipient());
    }

    @Test
    @DisplayName("getDlqDetail returns full detail and attempt history")
    public void testGetDlqDetail() {
        UUID id = UUID.randomUUID();
        Notification n = new Notification();
        n.setId(id);
        n.setRecipient("user@example.com");
        n.setChannel(Channel.SMS);
        n.setSubject(null);
        n.setContent("Your OTP code is 123456");
        n.setPriority(Priority.CRITICAL);
        n.setStatus(NotificationStatus.DEAD_LETTER);
        n.setRetryCount(3);
        n.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        n.setUpdatedAt(OffsetDateTime.now());

        NotificationAttempt attempt1 = new NotificationAttempt();
        attempt1.setId(UUID.randomUUID());
        attempt1.setAttemptNumber(1);
        attempt1.setStatus(AttemptStatus.FAILED);
        attempt1.setErrorMessage("Network timeout");
        attempt1.setAttemptedAt(OffsetDateTime.now().minusMinutes(9));

        NotificationAttempt attempt2 = new NotificationAttempt();
        attempt2.setId(UUID.randomUUID());
        attempt2.setAttemptNumber(2);
        attempt2.setStatus(AttemptStatus.FAILED);
        attempt2.setErrorMessage("Provider 500");
        attempt2.setAttemptedAt(OffsetDateTime.now().minusMinutes(5));

        when(mockNotificationRepository.findById(id)).thenReturn(n);
        when(mockNotificationAttemptRepository.findByNotificationId(id)).thenReturn(List.of(attempt1, attempt2));

        DlqNotificationDetailResponse detail = deadLetterService.getDlqDetail(id);

        assertNotNull(detail);
        assertEquals(id, detail.getId());
        assertEquals(NotificationStatus.DEAD_LETTER, detail.getStatus());
        assertEquals("Your OTP code is 123456", detail.getContent());
        assertEquals(2, detail.getAttempts().size());
        assertEquals("Network timeout", detail.getAttempts().get(0).getErrorMessage());
        assertEquals("Provider 500", detail.getAttempts().get(1).getErrorMessage());
    }

    @Test
    @DisplayName("getDlqDetail throws NotFoundException when item does not exist")
    public void testGetDlqDetailNotFound() {
        UUID id = UUID.randomUUID();
        when(mockNotificationRepository.findById(id)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> deadLetterService.getDlqDetail(id));
    }

    @Test
    @DisplayName("getDlqDetail throws IllegalStateException when item is not in DEAD_LETTER status")
    public void testGetDlqDetailNotDeadLetter() {
        UUID id = UUID.randomUUID();
        Notification n = new Notification();
        n.setId(id);
        n.setStatus(NotificationStatus.DELIVERED);
        when(mockNotificationRepository.findById(id)).thenReturn(n);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> deadLetterService.getDlqDetail(id));
        assertTrue(ex.getMessage().contains("not in DEAD_LETTER"));
    }

    @Test
    @DisplayName("retryDlqNotification transitions status to QUEUED, resets retryCount, creates OutboxEvent and preserves attempts")
    public void testRetryDlqNotificationSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        Notification n = new Notification();
        n.setId(id);
        n.setRecipient("user@example.com");
        n.setChannel(Channel.EMAIL);
        n.setSubject("Test Subject");
        n.setContent("Test Content");
        n.setPriority(Priority.HIGH);
        n.setStatus(NotificationStatus.DEAD_LETTER);
        n.setRetryCount(3);
        n.setCreatedAt(OffsetDateTime.now().minusMinutes(10));
        n.setUpdatedAt(OffsetDateTime.now().minusMinutes(1));

        when(mockNotificationRepository.findByIdForUpdate(id)).thenReturn(n);

        DlqActionResponse response = deadLetterService.retryDlqNotification(id);

        assertNotNull(response);
        assertEquals(id, response.getId());
        assertEquals(NotificationStatus.QUEUED, response.getStatus());
        assertEquals("Notification successfully re-queued for processing", response.getMessage());

        // Verify entity updated
        assertEquals(NotificationStatus.QUEUED, n.getStatus());
        assertEquals(0, n.getRetryCount(), "retryCount should be reset to 0 for a fresh retry budget");

        // Verify OutboxEvent persisted
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxEventRepository, times(1)).persist(captor.capture());
        OutboxEvent event = captor.getValue();
        assertEquals(id, event.getAggregateId());
        assertEquals(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED, event.getEventType());
        assertEquals(OutboxStatus.PENDING, event.getStatus());

        JsonNode payload = objectMapper.readTree(event.getPayload());
        assertEquals(id.toString(), payload.get("id").asText());
        assertEquals("QUEUED", payload.get("status").asText());
        assertEquals(0, payload.get("retryCount").asInt());

        // Verify notification attempts were NOT deleted or modified
        verify(mockNotificationAttemptRepository, never()).delete(any(NotificationAttempt.class));
    }

    @Test
    @DisplayName("retryDlqNotification throws NotFoundException when ID does not exist")
    public void testRetryDlqNotificationNotFound() {
        UUID id = UUID.randomUUID();
        when(mockNotificationRepository.findByIdForUpdate(id)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> deadLetterService.retryDlqNotification(id));
        verify(mockOutboxEventRepository, never()).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("retryDlqNotification throws IllegalStateException when notification is not in DEAD_LETTER")
    public void testRetryDlqNotificationInvalidStatus() {
        UUID id = UUID.randomUUID();
        Notification n = new Notification();
        n.setId(id);
        n.setStatus(NotificationStatus.PROCESSING);
        when(mockNotificationRepository.findByIdForUpdate(id)).thenReturn(n);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> deadLetterService.retryDlqNotification(id));
        assertTrue(ex.getMessage().contains("Only DEAD_LETTER notifications can be retried"));
        verify(mockOutboxEventRepository, never()).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("cancelDlqNotification transitions status to CANCELLED and does not create outbox event")
    public void testCancelDlqNotificationSuccess() {
        UUID id = UUID.randomUUID();
        Notification n = new Notification();
        n.setId(id);
        n.setStatus(NotificationStatus.DEAD_LETTER);
        n.setCreatedAt(OffsetDateTime.now().minusMinutes(5));

        when(mockNotificationRepository.findByIdForUpdate(id)).thenReturn(n);

        DlqActionResponse response = deadLetterService.cancelDlqNotification(id);

        assertNotNull(response);
        assertEquals(id, response.getId());
        assertEquals(NotificationStatus.CANCELLED, response.getStatus());
        assertEquals("Notification successfully cancelled from DLQ", response.getMessage());

        assertEquals(NotificationStatus.CANCELLED, n.getStatus());
        verify(mockOutboxEventRepository, never()).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("cancelDlqNotification throws NotFoundException when ID does not exist")
    public void testCancelDlqNotificationNotFound() {
        UUID id = UUID.randomUUID();
        when(mockNotificationRepository.findByIdForUpdate(id)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> deadLetterService.cancelDlqNotification(id));
    }

    @Test
    @DisplayName("cancelDlqNotification throws IllegalStateException when notification is already CANCELLED or not in DEAD_LETTER")
    public void testCancelDlqNotificationInvalidStatus() {
        UUID id = UUID.randomUUID();
        Notification n = new Notification();
        n.setId(id);
        n.setStatus(NotificationStatus.CANCELLED);
        when(mockNotificationRepository.findByIdForUpdate(id)).thenReturn(n);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> deadLetterService.cancelDlqNotification(id));
        assertTrue(ex.getMessage().contains("Only DEAD_LETTER notifications can be cancelled"));
    }
}
