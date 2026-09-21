package com.notification.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.domain.*;
import com.notification.provider.NotificationProvider;
import com.notification.provider.ProviderRegistry;
import com.notification.provider.ProviderSendResult;
import com.notification.ratelimit.TokenBucketConfig;
import com.notification.ratelimit.TokenBucketRateLimiter;
import com.notification.ratelimit.TokenBucketResult;
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import com.notification.retry.ExponentialBackoffStrategy;
import com.notification.retry.RetryClassifier;
import com.notification.retry.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class WorkerRetryPolicyTest {

    private NotificationProcessor processor;
    private NotificationRepository mockNotificationRepository;
    private NotificationAttemptRepository mockAttemptRepository;
    private OutboxEventRepository mockOutboxRepository;
    private ProviderRegistry mockProviderRegistry;
    private TokenBucketRateLimiter mockRateLimiter;
    private NotificationProvider mockProvider;
    private RetryPolicy retryPolicy;

    @BeforeEach
    public void setup() {
        processor = new NotificationProcessor();

        mockNotificationRepository = mock(NotificationRepository.class);
        mockAttemptRepository = mock(NotificationAttemptRepository.class);
        mockOutboxRepository = mock(OutboxEventRepository.class);
        mockProviderRegistry = mock(ProviderRegistry.class);
        mockRateLimiter = mock(TokenBucketRateLimiter.class);
        mockProvider = mock(NotificationProvider.class);

        // Deterministic backoff: 1000ms initial, 60000ms max, 2.0 multiplier, no random jitter
        ExponentialBackoffStrategy backoffStrategy = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, false, null);
        RetryClassifier retryClassifier = new RetryClassifier();
        retryPolicy = new RetryPolicy(3, backoffStrategy, retryClassifier);

        processor.notificationRepository = mockNotificationRepository;
        processor.notificationAttemptRepository = mockAttemptRepository;
        processor.outboxEventRepository = mockOutboxRepository;
        processor.providerRegistry = mockProviderRegistry;
        processor.tokenBucketRateLimiter = mockRateLimiter;
        processor.retryPolicy = retryPolicy;
        processor.objectMapper = new ObjectMapper();

        processor.rateLimitEnabled = true;
        processor.defaultCapacity = 100L;
        processor.defaultRefillRate = 20.0;
        processor.maxRetries = 3;

        when(mockProvider.getName()).thenReturn("FirebasePushProvider");
        when(mockProviderRegistry.getProviderForChannel(any())).thenReturn(Optional.of(mockProvider));
        when(mockRateLimiter.tryConsume(any(), any(TokenBucketConfig.class)))
                .thenReturn(TokenBucketResult.allowed(10.0, 10, 2.0));
    }

    @Test
    @DisplayName("Retryable provider error (500) transitions to RETRYING and schedules delayed OutboxEvent")
    public void testRetryableProviderErrorSchedulesDelayedOutboxEvent() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.serverError("Internal Server Error 500"));

        OffsetDateTime beforeProcess = OffsetDateTime.now();
        processor.doProcessNotification(notificationId);

        // Verification
        assertEquals(NotificationStatus.RETRYING, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxRepository, times(1)).persist(outboxCaptor.capture());

        OutboxEvent scheduledEvent = outboxCaptor.getValue();
        assertNotNull(scheduledEvent.getScheduledAt());
        // For attempt 1, initial delay is 1000ms
        assertTrue(scheduledEvent.getScheduledAt().isAfter(beforeProcess.plusNanos(500_000_000L)),
                "ScheduledAt should be delayed by ~1000ms: " + scheduledEvent.getScheduledAt());
        assertEquals(OutboxStatus.PENDING, scheduledEvent.getStatus());
    }

    @Test
    @DisplayName("Non-retryable provider error (400) transitions directly to FAILED and does not re-queue")
    public void testNonRetryableProviderErrorFailsImmediately() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.failure(400, "Bad Request: Invalid Device Token"));

        processor.doProcessNotification(notificationId);

        // Status should be terminal FAILED immediately
        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        // Must NOT schedule an OutboxEvent for non-retryable error
        verify(mockOutboxRepository, never()).persist(any(OutboxEvent.class));

        // Attempt must be logged as FAILED
        verify(mockAttemptRepository, times(1)).persist(any(NotificationAttempt.class));
    }

    @Test
    @DisplayName("Exceeding max retries transitions to DEAD_LETTER and stops scheduling")
    public void testExceedingMaxRetriesTransitionsToDeadLetter() {
        UUID notificationId = UUID.randomUUID();
        // Notification has already been retried 3 times (attempt 4 will exceed maxRetries = 3)
        Notification notification = createSampleNotification(notificationId, 3);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.serverError("Server still down 503"));

        processor.doProcessNotification(notificationId);

        // Status must be DEAD_LETTER
        assertEquals(NotificationStatus.DEAD_LETTER, notification.getStatus());
        assertEquals(4, notification.getRetryCount());

        // No more outbox events scheduled
        verify(mockOutboxRepository, never()).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Rate limiter throttle schedules delayed retry respecting retryAfterMs")
    public void testRateLimiterThrottleWithRetryAfterMs() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        // Rate limiter rejects request with 5000ms wait recommendation
        when(mockRateLimiter.tryConsume(any(), any(TokenBucketConfig.class)))
                .thenReturn(TokenBucketResult.denied(0.0, 5000L, 10, 2.0));

        OffsetDateTime beforeProcess = OffsetDateTime.now();
        processor.doProcessNotification(notificationId);

        assertEquals(NotificationStatus.RETRYING, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        // Provider must NOT have been called
        verify(mockProvider, never()).send(any());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxRepository, times(1)).persist(outboxCaptor.capture());

        OutboxEvent scheduledEvent = outboxCaptor.getValue();
        assertNotNull(scheduledEvent.getScheduledAt());
        // Delay should be at least 4500ms into the future
        assertTrue(scheduledEvent.getScheduledAt().isAfter(beforeProcess.plusSeconds(4)),
                "ScheduledAt should reflect retryAfterMs (5000ms): " + scheduledEvent.getScheduledAt());
    }

    private Notification createSampleNotification(UUID notificationId, int retryCount) {
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setChannel(Channel.PUSH);
        notification.setRecipient("test-recipient-device");
        notification.setContent("Retry test notification content");
        notification.setPriority(Priority.HIGH);
        notification.setStatus(retryCount == 0 ? NotificationStatus.QUEUED : NotificationStatus.RETRYING);
        notification.setRetryCount(retryCount);
        notification.setCreatedAt(OffsetDateTime.now());
        notification.setUpdatedAt(OffsetDateTime.now());
        return notification;
    }
}
