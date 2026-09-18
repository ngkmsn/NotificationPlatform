package com.notification.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.domain.*;
import com.notification.provider.MockNotificationProvider;
import com.notification.provider.NotificationProvider;
import com.notification.provider.ProviderRegistry;
import com.notification.provider.ProviderSendResult;
import com.notification.ratelimit.TokenBucketConfig;
import com.notification.ratelimit.TokenBucketRateLimiter;
import com.notification.ratelimit.TokenBucketResult;
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class WorkerRateLimitTest {

    private NotificationProcessor processor;
    private NotificationRepository mockNotificationRepository;
    private NotificationAttemptRepository mockAttemptRepository;
    private OutboxEventRepository mockOutboxRepository;
    private ProviderRegistry mockProviderRegistry;
    private TokenBucketRateLimiter mockRateLimiter;
    private NotificationProvider mockProvider;

    @BeforeEach
    public void setup() {
        processor = new NotificationProcessor();

        mockNotificationRepository = mock(NotificationRepository.class);
        mockAttemptRepository = mock(NotificationAttemptRepository.class);
        mockOutboxRepository = mock(OutboxEventRepository.class);
        mockProviderRegistry = mock(ProviderRegistry.class);
        mockRateLimiter = mock(TokenBucketRateLimiter.class);
        mockProvider = mock(NotificationProvider.class);

        processor.notificationRepository = mockNotificationRepository;
        processor.notificationAttemptRepository = mockAttemptRepository;
        processor.outboxEventRepository = mockOutboxRepository;
        processor.providerRegistry = mockProviderRegistry;
        processor.tokenBucketRateLimiter = mockRateLimiter;
        processor.objectMapper = new ObjectMapper();

        processor.rateLimitEnabled = true;
        processor.defaultCapacity = 100L;
        processor.defaultRefillRate = 20.0;
        processor.maxRetries = 3;

        when(mockProvider.getName()).thenReturn("MockProvider");
        when(mockProviderRegistry.getProviderForChannel(any())).thenReturn(Optional.of(mockProvider));
    }

    @Test
    public void testWorkerProcessesAllowedNotificationSuccessfully() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setChannel(Channel.PUSH);
        notification.setRecipient("test-fcm-token");
        notification.setContent("Test message");
        notification.setPriority(Priority.HIGH);
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setRetryCount(0);
        notification.setCreatedAt(OffsetDateTime.now());
        notification.setUpdatedAt(OffsetDateTime.now());

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        // Rate limiter allows consumption
        when(mockRateLimiter.tryConsume(eq("channel:push"), any(TokenBucketConfig.class)))
                .thenReturn(TokenBucketResult.allowed(99.0, 100, 20.0));
        when(mockProvider.send(notification)).thenReturn(ProviderSendResult.success("msg-123"));

        // Process notification directly in worker logic
        processor.doProcessNotification(notificationId);

        // Verify provider was invoked
        verify(mockProvider, times(1)).send(notification);
        // Verify notification state transitioned to DELIVERED
        assertEquals(NotificationStatus.DELIVERED, notification.getStatus());
        // Verify successful attempt was persisted
        verify(mockAttemptRepository, times(1)).persist(any(NotificationAttempt.class));
        // Verify no retry outbox event was generated
        verify(mockOutboxRepository, never()).persist(any(OutboxEvent.class));
    }

    @Test
    public void testWorkerThrottlesWhenRateLimitExceededAndQueuesRetryWithoutBlocking() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setChannel(Channel.EMAIL);
        notification.setRecipient("user@example.com");
        notification.setContent("Invoice details");
        notification.setPriority(Priority.NORMAL);
        notification.setStatus(NotificationStatus.QUEUED);
        notification.setRetryCount(0);
        notification.setCreatedAt(OffsetDateTime.now());
        notification.setUpdatedAt(OffsetDateTime.now());

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        // Rate limiter rejects consumption: bucket empty, wait 500ms
        when(mockRateLimiter.tryConsume(eq("channel:email"), any(TokenBucketConfig.class)))
                .thenReturn(TokenBucketResult.denied(0.0, 500L, 50, 10.0));

        long startTime = System.currentTimeMillis();
        processor.doProcessNotification(notificationId);
        long elapsed = System.currentTimeMillis() - startTime;

        // Worker thread must NOT be blocked
        assertTrue(elapsed < 200, "Worker processing must be non-blocking and return immediately");

        // Provider must NOT be called when rate limited
        verify(mockProvider, never()).send(any());

        // Status transitioned to RETRYING and retryCount incremented
        assertEquals(NotificationStatus.RETRYING, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        // Failed attempt recorded with throttle message
        org.mockito.ArgumentCaptor<NotificationAttempt> attemptCaptor = org.mockito.ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(mockAttemptRepository, times(1)).persist(attemptCaptor.capture());
        NotificationAttempt recordedAttempt = attemptCaptor.getValue();
        assertEquals(AttemptStatus.FAILED, recordedAttempt.getStatus());
        assertTrue(recordedAttempt.getErrorMessage().contains("Rate limit exceeded"));

        // Re-queued in Outbox for asynchronous pickup
        org.mockito.ArgumentCaptor<OutboxEvent> outboxCaptor = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mockOutboxRepository, times(1)).persist(outboxCaptor.capture());
        OutboxEvent recordedEvent = outboxCaptor.getValue();
        assertEquals(notificationId, recordedEvent.getAggregateId());
        assertEquals(OutboxStatus.PENDING, recordedEvent.getStatus());
    }

    @Test
    public void testWorkerTransitionsToDeadLetterWhenMaxRetriesExceeded() {
        UUID notificationId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setId(notificationId);
        notification.setChannel(Channel.SMS);
        notification.setRecipient("+1234567890");
        notification.setContent("SMS Alert");
        notification.setPriority(Priority.HIGH);
        notification.setStatus(NotificationStatus.RETRYING);
        notification.setRetryCount(3); // Already at max retries (3)
        notification.setCreatedAt(OffsetDateTime.now());
        notification.setUpdatedAt(OffsetDateTime.now());

        when(mockNotificationRepository.findById(notificationId)).thenReturn(notification);
        when(mockRateLimiter.tryConsume(eq("channel:sms"), any(TokenBucketConfig.class)))
                .thenReturn(TokenBucketResult.denied(0.0, 1000L, 20, 5.0));

        processor.doProcessNotification(notificationId);

        // Transitioned to DEAD_LETTER after exceeding max retries
        assertEquals(NotificationStatus.DEAD_LETTER, notification.getStatus());
        assertEquals(4, notification.getRetryCount());

        // No new outbox retry event scheduled once DEAD_LETTER
        verify(mockOutboxRepository, never()).persist(any(OutboxEvent.class));
    }

    @Test
    public void testConcurrentWorkerConsumptionAcrossMultipleThreads() throws Exception {
        int workerCount = 10;
        int notificationsPerWorker = 10;
        int totalNotifications = workerCount * notificationsPerWorker; // 100 notifications

        // Simulate a bucket with capacity=40, rate=0 (strict 40 allowed, 60 throttled)
        long capacity = 40;
        AtomicInteger tokens = new AtomicInteger((int) capacity);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        AtomicInteger throttledCount = new AtomicInteger(0);

        TokenBucketRateLimiter concurrentRateLimiter = new TokenBucketRateLimiter() {
            @Override
            public TokenBucketResult tryConsume(String key, TokenBucketConfig config) {
                while (true) {
                    int current = tokens.get();
                    if (current > 0) {
                        if (tokens.compareAndSet(current, current - 1)) {
                            return TokenBucketResult.allowed(current - 1, capacity, 0.0);
                        }
                    } else {
                        return TokenBucketResult.denied(0, 1000L, capacity, 0.0);
                    }
                }
            }

            @Override
            public boolean reset(String key) {
                tokens.set((int) capacity);
                return true;
            }
        };

        NotificationProvider fastProvider = mock(NotificationProvider.class);
        when(fastProvider.getName()).thenReturn("FastProvider");
        when(fastProvider.send(any())).thenAnswer(invocation -> {
            deliveredCount.incrementAndGet();
            return ProviderSendResult.success("fast-ok");
        });

        NotificationProcessor concurrentProcessor = new NotificationProcessor();
        concurrentProcessor.notificationRepository = mockNotificationRepository;
        concurrentProcessor.notificationAttemptRepository = mockAttemptRepository;
        concurrentProcessor.outboxEventRepository = mockOutboxRepository;
        concurrentProcessor.providerRegistry = mockProviderRegistry;
        concurrentProcessor.tokenBucketRateLimiter = concurrentRateLimiter;
        concurrentProcessor.objectMapper = new ObjectMapper();
        concurrentProcessor.rateLimitEnabled = true;

        when(mockProviderRegistry.getProviderForChannel(any())).thenReturn(Optional.of(fastProvider));

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalNotifications);

        for (int i = 0; i < totalNotifications; i++) {
            UUID nid = UUID.randomUUID();
            Notification n = new Notification();
            n.setId(nid);
            n.setChannel(Channel.PUSH);
            n.setRecipient("device-" + i);
            n.setContent("Alert " + i);
            n.setStatus(NotificationStatus.QUEUED);
            n.setRetryCount(0);
            when(mockNotificationRepository.findById(nid)).thenReturn(n);

            executor.submit(() -> {
                try {
                    startLatch.await();
                    concurrentProcessor.doProcessNotification(nid);
                    if (n.getStatus() == NotificationStatus.RETRYING) {
                        throttledCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed);
        assertEquals(capacity, deliveredCount.get(), "Exactly capacity (40) notifications must be delivered");
        assertEquals(totalNotifications - capacity, throttledCount.get(), "Remaining (60) must be throttled to RETRYING");
    }
}
