package com.notification.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.circuitbreaker.CircuitBreaker;
import com.notification.circuitbreaker.CircuitBreakerConfig;
import com.notification.circuitbreaker.CircuitBreakerResult;
import com.notification.circuitbreaker.CircuitBreakerState;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class WorkerCircuitBreakerTest {

    private NotificationProcessor processor;
    private NotificationRepository mockNotificationRepository;
    private NotificationAttemptRepository mockAttemptRepository;
    private OutboxEventRepository mockOutboxRepository;
    private ProviderRegistry mockProviderRegistry;
    private TokenBucketRateLimiter mockRateLimiter;
    private CircuitBreaker mockCircuitBreaker;
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
        mockCircuitBreaker = mock(CircuitBreaker.class);
        mockProvider = mock(NotificationProvider.class);

        ExponentialBackoffStrategy backoffStrategy = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, false, null);
        RetryClassifier retryClassifier = new RetryClassifier();
        retryPolicy = new RetryPolicy(3, backoffStrategy, retryClassifier);

        processor.notificationRepository = mockNotificationRepository;
        processor.notificationAttemptRepository = mockAttemptRepository;
        processor.outboxEventRepository = mockOutboxRepository;
        processor.providerRegistry = mockProviderRegistry;
        processor.tokenBucketRateLimiter = mockRateLimiter;
        processor.circuitBreaker = mockCircuitBreaker;
        processor.retryPolicy = retryPolicy;
        processor.objectMapper = new ObjectMapper();

        processor.rateLimitEnabled = true;
        processor.circuitBreakerEnabled = true;
        processor.defaultCapacity = 100L;
        processor.defaultRefillRate = 20.0;
        processor.maxRetries = 3;

        when(mockProvider.getName()).thenReturn("FirebasePushProvider");
        when(mockProviderRegistry.getProviderForChannel(any())).thenReturn(Optional.of(mockProvider));
        when(mockRateLimiter.tryConsume(any(), any(TokenBucketConfig.class)))
                .thenReturn(TokenBucketResult.allowed(10.0, 10, 2.0));
    }

    @Test
    @DisplayName("When Circuit Breaker is CLOSED, request executes normally and records result")
    public void testClosedCircuitBreakerAllowsExecution() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockCircuitBreaker.acquirePermission(any(), any(CircuitBreakerConfig.class)))
                .thenReturn(CircuitBreakerResult.allowed(CircuitBreakerState.CLOSED, 0, false));
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.success("fcm-msg-123"));

        processor.doProcessNotification(notificationId);

        // Verification
        assertEquals(NotificationStatus.DELIVERED, notification.getStatus());
        verify(mockProvider, times(1)).send(notification);
        verify(mockCircuitBreaker, times(1)).recordResult(any(), any(ProviderSendResult.class), any(CircuitBreakerConfig.class));

        ArgumentCaptor<NotificationAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(mockAttemptRepository, times(1)).persist(attemptCaptor.capture());
        assertEquals(AttemptStatus.SUCCESS, attemptCaptor.getValue().getStatus());
    }

    @Test
    @DisplayName("When Circuit Breaker is OPEN, request fails fast without calling downstream and reschedules via Outbox")
    public void testOpenCircuitBreakerFailsFastAndReschedulesViaOutbox() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        // Circuit Breaker is OPEN, requests must wait 30,000ms
        when(mockCircuitBreaker.acquirePermission(any(), any(CircuitBreakerConfig.class)))
                .thenReturn(CircuitBreakerResult.denied(CircuitBreakerState.OPEN, 30000L));

        OffsetDateTime beforeProcess = OffsetDateTime.now();
        processor.doProcessNotification(notificationId);

        // Provider MUST NOT be called (Fail-Fast protection)
        verify(mockProvider, never()).send(any());

        // Status updated to RETRYING
        assertEquals(NotificationStatus.RETRYING, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        // Attempt logged as FAILED with Circuit Breaker message
        ArgumentCaptor<NotificationAttempt> attemptCaptor = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(mockAttemptRepository, times(1)).persist(attemptCaptor.capture());
        assertTrue(attemptCaptor.getValue().getErrorMessage().contains("Circuit Breaker is [OPEN]"));

        // Outbox event scheduled for retry with cooldown delay (at least 30s)
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxRepository, times(1)).persist(outboxCaptor.capture());

        OutboxEvent scheduledEvent = outboxCaptor.getValue();
        assertNotNull(scheduledEvent.getScheduledAt());
        assertTrue(scheduledEvent.getScheduledAt().isAfter(beforeProcess.plusSeconds(25)),
                "ScheduledAt should be delayed by Circuit Breaker cooldown: " + scheduledEvent.getScheduledAt());
        assertEquals(OutboxStatus.PENDING, scheduledEvent.getStatus());
    }

    @Test
    @DisplayName("When Circuit Breaker is in HALF_OPEN, probe request is allowed to execute and probe result is recorded")
    public void testHalfOpenCircuitBreakerAllowsProbe() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 1);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        // Circuit Breaker in HALF_OPEN grants probe
        when(mockCircuitBreaker.acquirePermission(any(), any(CircuitBreakerConfig.class)))
                .thenReturn(CircuitBreakerResult.allowed(CircuitBreakerState.HALF_OPEN, 0, true));
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.success("fcm-msg-probe-success"));

        processor.doProcessNotification(notificationId);

        // Probe should reach provider
        verify(mockProvider, times(1)).send(notification);
        assertEquals(NotificationStatus.DELIVERED, notification.getStatus());
        verify(mockCircuitBreaker, times(1)).recordResult(any(), any(ProviderSendResult.class), any(CircuitBreakerConfig.class));
    }

    @Test
    @DisplayName("When Circuit Breaker is in HALF_OPEN and probe quota exhausted, fail-fast and reschedule")
    public void testHalfOpenProbeQuotaExhaustedFailsFast() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        // Probe quota exhausted in HALF_OPEN
        when(mockCircuitBreaker.acquirePermission(any(), any(CircuitBreakerConfig.class)))
                .thenReturn(CircuitBreakerResult.denied(CircuitBreakerState.HALF_OPEN, 15000L));

        processor.doProcessNotification(notificationId);

        // Fail fast: no provider send
        verify(mockProvider, never()).send(any());
        assertEquals(NotificationStatus.RETRYING, notification.getStatus());
        verify(mockOutboxRepository, times(1)).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Retryable provider error records failure to Circuit Breaker and schedules retry")
    public void testRetryableProviderErrorRecordsFailureToCircuitBreaker() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockCircuitBreaker.acquirePermission(any(), any(CircuitBreakerConfig.class)))
                .thenReturn(CircuitBreakerResult.allowed(CircuitBreakerState.CLOSED, 0, false));
        ProviderSendResult serverErrorResult = ProviderSendResult.serverError("Internal 500");
        when(mockProvider.send(notification)).thenReturn(serverErrorResult);

        processor.doProcessNotification(notificationId);

        verify(mockProvider, times(1)).send(notification);
        verify(mockCircuitBreaker, times(1)).recordResult(any(), eq(serverErrorResult), any(CircuitBreakerConfig.class));
        assertEquals(NotificationStatus.RETRYING, notification.getStatus());
        verify(mockOutboxRepository, times(1)).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Fatal non-retryable provider error fails immediately without re-queueing")
    public void testFatalErrorFailsImmediately() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockCircuitBreaker.acquirePermission(any(), any(CircuitBreakerConfig.class)))
                .thenReturn(CircuitBreakerResult.allowed(CircuitBreakerState.CLOSED, 0, false));
        ProviderSendResult fatalResult = ProviderSendResult.failure(400, "Bad Request: Malformed Payload");
        when(mockProvider.send(notification)).thenReturn(fatalResult);

        processor.doProcessNotification(notificationId);

        verify(mockProvider, times(1)).send(notification);
        verify(mockCircuitBreaker, times(1)).recordResult(any(), eq(fatalResult), any(CircuitBreakerConfig.class));
        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        verify(mockOutboxRepository, never()).persist(any(OutboxEvent.class));
    }

    private Notification createSampleNotification(UUID notificationId, int retryCount) {
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setChannel(Channel.PUSH);
        notification.setRecipient("test-recipient-device");
        notification.setContent("Circuit breaker test notification content");
        notification.setPriority(Priority.HIGH);
        notification.setStatus(retryCount == 0 ? NotificationStatus.QUEUED : NotificationStatus.RETRYING);
        notification.setRetryCount(retryCount);
        notification.setCreatedAt(OffsetDateTime.now());
        notification.setUpdatedAt(OffsetDateTime.now());
        return notification;
    }
}
