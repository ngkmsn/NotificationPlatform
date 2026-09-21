package com.notification.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.application.NotificationService;
import com.notification.circuitbreaker.CircuitBreaker;
import com.notification.circuitbreaker.CircuitBreakerConfig;
import com.notification.circuitbreaker.CircuitBreakerResult;
import com.notification.circuitbreaker.CircuitBreakerState;
import com.notification.dlq.DeadLetterService;
import com.notification.dlq.DlqReason;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class WorkerDlqTest {

    private NotificationProcessor processor;
    private NotificationRepository mockNotificationRepository;
    private NotificationAttemptRepository mockAttemptRepository;
    private OutboxEventRepository mockOutboxRepository;
    private ProviderRegistry mockProviderRegistry;
    private TokenBucketRateLimiter mockRateLimiter;
    private CircuitBreaker mockCircuitBreaker;
    private NotificationProvider mockProvider;
    private DeadLetterService deadLetterService;
    private ObjectMapper objectMapper;

    @BeforeEach
    public void setup() {
        processor = new NotificationProcessor();
        objectMapper = new ObjectMapper();

        mockNotificationRepository = mock(NotificationRepository.class);
        mockAttemptRepository = mock(NotificationAttemptRepository.class);
        mockOutboxRepository = mock(OutboxEventRepository.class);
        mockProviderRegistry = mock(ProviderRegistry.class);
        mockRateLimiter = mock(TokenBucketRateLimiter.class);
        mockCircuitBreaker = mock(CircuitBreaker.class);
        mockProvider = mock(NotificationProvider.class);

        deadLetterService = new DeadLetterService();
        deadLetterService.notificationRepository = mockNotificationRepository;
        deadLetterService.outboxEventRepository = mockOutboxRepository;
        deadLetterService.objectMapper = objectMapper;

        ExponentialBackoffStrategy backoffStrategy = new ExponentialBackoffStrategy(1000L, 60000L, 2.0, false, null);
        RetryClassifier retryClassifier = new RetryClassifier();
        RetryPolicy retryPolicy = new RetryPolicy(3, backoffStrategy, retryClassifier);

        processor.notificationRepository = mockNotificationRepository;
        processor.notificationAttemptRepository = mockAttemptRepository;
        processor.outboxEventRepository = mockOutboxRepository;
        processor.providerRegistry = mockProviderRegistry;
        processor.tokenBucketRateLimiter = mockRateLimiter;
        processor.circuitBreaker = mockCircuitBreaker;
        processor.retryPolicy = retryPolicy;
        processor.deadLetterService = deadLetterService;
        processor.objectMapper = objectMapper;

        processor.rateLimitEnabled = true;
        processor.circuitBreakerEnabled = true;
        processor.maxRetries = 3;

        when(mockProvider.getName()).thenReturn("FirebasePushProvider");
        when(mockProviderRegistry.getProviderForChannel(any())).thenReturn(Optional.of(mockProvider));
        when(mockRateLimiter.tryConsume(any(), any(TokenBucketConfig.class)))
                .thenReturn(TokenBucketResult.allowed(10.0, 10, 2.0));
        when(mockCircuitBreaker.acquirePermission(any(), any(CircuitBreakerConfig.class)))
                .thenReturn(CircuitBreakerResult.allowed(CircuitBreakerState.CLOSED, 0, false));
    }

    @Test
    @DisplayName("Max retries exceeded routes notification to DLQ with reason MAX_RETRIES_EXCEEDED")
    public void testMaxRetriesExceededRoutesToDlq() throws Exception {
        UUID notificationId = UUID.randomUUID();
        // Notification has already reached retry count 3 -> next attempt is #4 (> maxRetries = 3)
        Notification notification = createSampleNotification(notificationId, 3);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.serverError("Upstream 503 Service Unavailable"));

        processor.doProcessNotification(notificationId);

        // Verification
        assertEquals(NotificationStatus.DEAD_LETTER, notification.getStatus());
        assertEquals(4, notification.getRetryCount());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxRepository, times(1)).persist(outboxCaptor.capture());

        OutboxEvent dlqEvent = outboxCaptor.getValue();
        assertEquals(NotificationService.EVENT_TYPE_NOTIFICATION_DEAD_LETTER, dlqEvent.getEventType());
        assertEquals(notificationId, dlqEvent.getAggregateId());
        assertEquals(OutboxStatus.PENDING, dlqEvent.getStatus());

        JsonNode payload = objectMapper.readTree(dlqEvent.getPayload());
        assertEquals(notificationId.toString(), payload.get("notificationId").asText());
        assertEquals("MAX_RETRIES_EXCEEDED", payload.get("reason").asText());
        assertEquals(4, payload.get("retryCount").asInt());
        assertTrue(payload.get("errorMessage").asText().contains("503"));
    }

    @Test
    @DisplayName("Non-retryable fatal provider error (400) immediately routes to DLQ with reason NON_RETRYABLE_ERROR")
    public void testNonRetryableErrorRoutesToDlqImmediately() throws Exception {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.failure(400, "Bad Request: Malformed token"));

        processor.doProcessNotification(notificationId);

        // Status must transition to DEAD_LETTER immediately without retrying
        assertEquals(NotificationStatus.DEAD_LETTER, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxRepository, times(1)).persist(outboxCaptor.capture());

        OutboxEvent dlqEvent = outboxCaptor.getValue();
        assertEquals(NotificationService.EVENT_TYPE_NOTIFICATION_DEAD_LETTER, dlqEvent.getEventType());

        JsonNode payload = objectMapper.readTree(dlqEvent.getPayload());
        assertEquals("NON_RETRYABLE_ERROR", payload.get("reason").asText());
        assertEquals(400, payload.get("httpStatusCode").asInt());
        assertEquals("Bad Request: Malformed token", payload.get("errorMessage").asText());
    }

    @Test
    @DisplayName("Missing provider for channel routes to DLQ with reason NO_PROVIDER_AVAILABLE")
    public void testMissingProviderRoutesToDlq() throws Exception {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        // No provider found
        when(mockProviderRegistry.getProviderForChannel(any())).thenReturn(Optional.empty());

        processor.doProcessNotification(notificationId);

        assertEquals(NotificationStatus.DEAD_LETTER, notification.getStatus());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxRepository, times(1)).persist(outboxCaptor.capture());

        OutboxEvent dlqEvent = outboxCaptor.getValue();
        assertEquals(NotificationService.EVENT_TYPE_NOTIFICATION_DEAD_LETTER, dlqEvent.getEventType());

        JsonNode payload = objectMapper.readTree(dlqEvent.getPayload());
        assertEquals("NO_PROVIDER_AVAILABLE", payload.get("reason").asText());
    }

    @Test
    @DisplayName("Successful notification delivers and NEVER routes to DLQ")
    public void testSuccessfulNotificationNeverRoutesToDlq() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = createSampleNotification(notificationId, 0);

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.success("fcm-ok-123"));

        processor.doProcessNotification(notificationId);

        assertEquals(NotificationStatus.DELIVERED, notification.getStatus());
        verify(mockOutboxRepository, never()).persist(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("OutboxPublisher correctly routes NOTIFICATION_DEAD_LETTER event to notification-dlq topic")
    public void testOutboxPublisherRoutesDlqTopic() {
        OutboxPublisher publisher = new OutboxPublisher();
        publisher.dlqTopic = "notification-dlq";
        publisher.objectMapper = objectMapper;

        OutboxEvent dlqEvent = new OutboxEvent();
        dlqEvent.setId(UUID.randomUUID());
        dlqEvent.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_DEAD_LETTER);
        dlqEvent.setPayload("{\"notificationId\":\"123\"}");

        String resolvedTopic = publisher.resolveTopic(dlqEvent);
        assertEquals("notification-dlq", resolvedTopic, "DLQ events must route to notification-dlq topic");

        // Regular events route to priority topics
        OutboxEvent normalEvent = new OutboxEvent();
        normalEvent.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED);
        normalEvent.setPayload("{\"priority\":\"HIGH\"}");
        assertEquals("notification-high", publisher.resolveTopic(normalEvent));
    }

    private Notification createSampleNotification(UUID notificationId, int retryCount) {
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setChannel(Channel.PUSH);
        notification.setRecipient("test-recipient-device");
        notification.setContent("DLQ test notification content");
        notification.setPriority(Priority.HIGH);
        notification.setStatus(retryCount == 0 ? NotificationStatus.QUEUED : NotificationStatus.RETRYING);
        notification.setRetryCount(retryCount);
        notification.setCreatedAt(OffsetDateTime.now());
        notification.setUpdatedAt(OffsetDateTime.now());
        return notification;
    }
}
