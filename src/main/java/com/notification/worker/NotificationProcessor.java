package com.notification.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.application.NotificationService;
import com.notification.circuitbreaker.CircuitBreaker;
import com.notification.circuitbreaker.CircuitBreakerConfig;
import com.notification.circuitbreaker.CircuitBreakerResult;
import com.notification.dlq.DeadLetterService;
import com.notification.dlq.DlqReason;
import com.notification.domain.AttemptStatus;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationAttempt;
import com.notification.domain.NotificationStatus;
import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import com.notification.provider.NotificationProvider;
import com.notification.provider.ProviderRegistry;
import com.notification.provider.ProviderSendResult;
import com.notification.ratelimit.TokenBucketConfig;
import com.notification.ratelimit.TokenBucketRateLimiter;
import com.notification.ratelimit.TokenBucketResult;
import com.notification.retry.RetryPolicy;
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import com.notification.metrics.NotificationMetrics;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class NotificationProcessor {

    private static final Logger LOG = Logger.getLogger(NotificationProcessor.class);

    @Inject
    NotificationRepository notificationRepository;

    @Inject
    NotificationAttemptRepository notificationAttemptRepository;

    @Inject
    OutboxEventRepository outboxEventRepository;

    @Inject
    ProviderRegistry providerRegistry;

    @Inject
    NotificationMetrics notificationMetrics;

    @Inject
    TokenBucketRateLimiter tokenBucketRateLimiter;

    @Inject
    public CircuitBreaker circuitBreaker;

    @Inject
    public DeadLetterService deadLetterService;

    @Inject
    public RetryPolicy retryPolicy;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "ratelimit.worker.enabled", defaultValue = "true")
    public boolean rateLimitEnabled = true;

    @ConfigProperty(name = "ratelimit.default.capacity", defaultValue = "100")
    public long defaultCapacity = 100L;

    @ConfigProperty(name = "ratelimit.default.refill-rate", defaultValue = "20.0")
    public double defaultRefillRate = 20.0;

    @ConfigProperty(name = "circuitbreaker.enabled", defaultValue = "true")
    public boolean circuitBreakerEnabled = true;

    @ConfigProperty(name = "circuitbreaker.failure-rate-threshold", defaultValue = "50.0")
    public double cbFailureRateThreshold = 50.0;

    @ConfigProperty(name = "circuitbreaker.minimum-number-of-calls", defaultValue = "5")
    public int cbMinimumNumberOfCalls = 5;

    @ConfigProperty(name = "circuitbreaker.sliding-window-duration-ms", defaultValue = "60000")
    public long cbSlidingWindowDurationMs = 60000L;

    @ConfigProperty(name = "circuitbreaker.wait-duration-in-open-state-ms", defaultValue = "30000")
    public long cbWaitDurationInOpenStateMs = 30000L;

    @ConfigProperty(name = "circuitbreaker.permitted-number-of-calls-in-half-open-state", defaultValue = "3")
    public int cbPermittedNumberOfCallsInHalfOpenState = 3;

    @ConfigProperty(name = "circuitbreaker.half-open-success-threshold", defaultValue = "2")
    public int cbHalfOpenSuccessThreshold = 2;

    @ConfigProperty(name = "notification.worker.max-retries", defaultValue = "3")
    public int maxRetries = 3;

    public void processMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            LOG.warn("Received empty or null notification payload. Skipping.");
            return;
        }

        UUID notificationId;
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode idNode = root.get("id");
            if (idNode == null || idNode.isNull()) {
                idNode = root.get("aggregateId");
            }
            if (idNode == null || idNode.isNull()) {
                LOG.errorf("Payload missing notification id: %s", payload);
                return;
            }
            notificationId = UUID.fromString(idNode.asText());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to deserialize notification payload: %s", payload);
            return;
        }

        processNotification(notificationId);
    }

    public void processNotification(UUID notificationId) {
        QuarkusTransaction.requiringNew().run(() -> {
            doProcessNotification(notificationId);
        });
    }

    @WithSpan("doProcessNotification")
    public void doProcessNotification(@SpanAttribute("notification.id") UUID notificationId) {
        OffsetDateTime attemptedAt = OffsetDateTime.now();

        Notification notification = notificationRepository.findById(notificationId);
        if (notification == null) {
            LOG.warnf("Notification [%s] not found in database. Skipping processing.", notificationId);
            return;
        }

            // Idempotency check: terminal or already delivered
            if (notification.getStatus() == NotificationStatus.DELIVERED) {
                LOG.infof("Notification [%s] is already DELIVERED. Skipping to prevent duplicate processing.", notificationId);
                return;
            }

            if (notification.getStatus() == NotificationStatus.CANCELLED ||
                notification.getStatus() == NotificationStatus.DEAD_LETTER) {
                LOG.infof("Notification [%s] is in terminal status [%s]. Skipping.", notificationId, notification.getStatus());
                return;
            }

            int currentRetryCount = notification.getRetryCount() != null ? notification.getRetryCount() : 0;
            int attemptNumber = currentRetryCount + 1;

            try {
                LOG.debugf("Processing notification [%s] (attempt #%d, channel: %s, recipient: %s)",
                        notification.getId(), attemptNumber, notification.getChannel(), notification.getRecipient());

                // Resolve provider from registry
                Optional<NotificationProvider> providerOpt = providerRegistry.getProviderForChannel(notification.getChannel());
                if (providerOpt.isEmpty()) {
                    String errorMsg = "No provider available for channel: " + notification.getChannel();
                    LOG.errorf("Notification [%s] failed: %s", notificationId, errorMsg);

                    OffsetDateTime failedAt = OffsetDateTime.now();
                    notification.setRetryCount(attemptNumber);
                    notification.setUpdatedAt(failedAt);

                    NotificationAttempt attempt = new NotificationAttempt();
                    attempt.setId(UUID.randomUUID());
                    attempt.setNotification(notification);
                    attempt.setProvider(notification.getProvider());
                    attempt.setAttemptNumber(attemptNumber);
                    attempt.setStatus(AttemptStatus.FAILED);
                    attempt.setErrorMessage(errorMsg);
                    attempt.setAttemptedAt(attemptedAt);
                    attempt.setCompletedAt(failedAt);

                    notificationAttemptRepository.persist(attempt);

                    if (notificationMetrics != null) {
                        notificationMetrics.recordFailed(notification.getChannel(), "NONE", errorMsg);
                    }

                    resolveDeadLetterService().routeToDlq(notification, DlqReason.NO_PROVIDER_AVAILABLE, errorMsg, null, null, null);
                    return;
                }

                NotificationProvider provider = providerOpt.get();

                // Rate Limiting check with Token Bucket (IMP-15)
                if (rateLimitEnabled && tokenBucketRateLimiter != null) {
                    String rateLimitKey = resolveRateLimitKey(notification, provider);
                    TokenBucketConfig config = resolveTokenBucketConfig(notification);
                    TokenBucketResult rateLimitResult = tokenBucketRateLimiter.tryConsume(rateLimitKey, config);

                    if (!rateLimitResult.isAllowed()) {
                        OffsetDateTime throttledAt = OffsetDateTime.now();
                        long waitMs = rateLimitResult.getRetryAfterMs();
                        RetryPolicy policy = resolveRetryPolicy();
                        long delayMs = policy.calculateDelayMs(attemptNumber, waitMs);
                        OffsetDateTime scheduledAt = throttledAt.plus(delayMs, ChronoUnit.MILLIS);
                        String errorMsg = String.format("Rate limit exceeded for channel [%s]. Retry after %d ms",
                                notification.getChannel(), waitMs);

                        LOG.warnf("Notification [%s] throttled by Rate Limiter: %s", notificationId, errorMsg);

                        if (notificationMetrics != null) {
                            notificationMetrics.recordThrottled(notification.getChannel());
                        }

                        // Update Notification status
                        notification.setRetryCount(attemptNumber);
                        notification.setUpdatedAt(throttledAt);

                        // Create throttled NotificationAttempt record
                        NotificationAttempt attempt = new NotificationAttempt();
                        attempt.setId(UUID.randomUUID());
                        attempt.setNotification(notification);
                        attempt.setProvider(notification.getProvider());
                        attempt.setAttemptNumber(attemptNumber);
                        attempt.setStatus(AttemptStatus.FAILED);
                        attempt.setErrorMessage(errorMsg);
                        attempt.setAttemptedAt(attemptedAt);
                        attempt.setCompletedAt(throttledAt);
                        notificationAttemptRepository.persist(attempt);

                        // Schedule / re-queue message asynchronously without blocking the Worker thread
                        if (policy.canRetryRateLimited(attemptNumber)) {
                            notification.setStatus(NotificationStatus.RETRYING);
                            scheduleRetryOutboxEvent(notification, scheduledAt);
                            LOG.infof("Notification [%s] scheduled for retry (attempt #%d <= max %d) at %s (delay %d ms)",
                                    notificationId, attemptNumber, policy.getMaxRetries(), scheduledAt, delayMs);
                        } else {
                            LOG.errorf("Notification [%s] exceeded max retries (%d) during Rate Limiter throttle. Routing to DLQ.",
                                    notificationId, policy.getMaxRetries());
                            resolveDeadLetterService().routeToDlq(notification, DlqReason.MAX_RETRIES_EXCEEDED, errorMsg, 429, provider.getName(), null);
                        }
                        return;
                    }
                }

                // Circuit Breaker check (Fail-Fast for downstream outage protection)
                String cbKey = resolveCircuitBreakerKey(notification, provider);
                CircuitBreakerConfig cbConfig = resolveCircuitBreakerConfig();

                if (circuitBreakerEnabled && circuitBreaker != null) {
                    CircuitBreakerResult cbResult = circuitBreaker.acquirePermission(cbKey, cbConfig);
                    if (notificationMetrics != null && cbResult != null && cbResult.getState() != null) {
                        notificationMetrics.updateCircuitBreakerState(cbKey, cbResult.getState().name());
                    }

                    if (!cbResult.isAllowed()) {
                        OffsetDateTime blockedAt = OffsetDateTime.now();
                        long waitMs = cbResult.getRetryAfterMs();
                        RetryPolicy policy = resolveRetryPolicy();
                        long delayMs = policy.calculateDelayMs(attemptNumber, waitMs);
                        OffsetDateTime scheduledAt = blockedAt.plus(delayMs, ChronoUnit.MILLIS);
                        String errorMsg = String.format("Circuit Breaker is [%s] for provider [%s]. Fail-fast without calling downstream. Retry after %d ms",
                                cbResult.getState(), provider.getName(), waitMs);

                        LOG.warnf("Notification [%s] blocked by Circuit Breaker: %s", notificationId, errorMsg);

                        // Update Notification status
                        notification.setRetryCount(attemptNumber);
                        notification.setUpdatedAt(blockedAt);

                        // Create blocked NotificationAttempt record
                        NotificationAttempt attempt = new NotificationAttempt();
                        attempt.setId(UUID.randomUUID());
                        attempt.setNotification(notification);
                        attempt.setProvider(notification.getProvider());
                        attempt.setAttemptNumber(attemptNumber);
                        attempt.setStatus(AttemptStatus.FAILED);
                        attempt.setErrorMessage(errorMsg);
                        attempt.setAttemptedAt(attemptedAt);
                        attempt.setCompletedAt(blockedAt);
                        notificationAttemptRepository.persist(attempt);

                        // Reschedule via Outbox
                        if (policy.canRetryRateLimited(attemptNumber)) {
                            notification.setStatus(NotificationStatus.RETRYING);
                            scheduleRetryOutboxEvent(notification, scheduledAt);
                            LOG.infof("Notification [%s] scheduled for retry after Circuit Breaker block (attempt #%d <= max %d) at %s (delay %d ms)",
                                    notificationId, attemptNumber, policy.getMaxRetries(), scheduledAt, delayMs);
                        } else {
                            LOG.errorf("Notification [%s] exceeded max retries (%d) during Circuit Breaker block. Routing to DLQ.",
                                    notificationId, policy.getMaxRetries());
                            resolveDeadLetterService().routeToDlq(notification, DlqReason.MAX_RETRIES_EXCEEDED, errorMsg, null, provider.getName(), null);
                        }
                        return;
                    }
                }

                Timer.Sample timerSample = (notificationMetrics != null) ? notificationMetrics.startTimer() : null;
                ProviderSendResult sendResult = provider.send(notification);
                OffsetDateTime completedAt = OffsetDateTime.now();

                if (notificationMetrics != null) {
                    notificationMetrics.stopTimer(timerSample, notification.getChannel(), provider.getName());
                }

                // Record send result to Circuit Breaker
                if (circuitBreakerEnabled && circuitBreaker != null) {
                    circuitBreaker.recordResult(cbKey, sendResult, cbConfig);
                    if (notificationMetrics != null) {
                        com.notification.circuitbreaker.CircuitBreakerState currentState = circuitBreaker.getState(cbKey);
                        if (currentState != null) {
                            notificationMetrics.updateCircuitBreakerState(cbKey, currentState.name());
                        }
                    }
                }

                if (sendResult.isSuccess()) {
                    // Update Notification status to DELIVERED
                    notification.setStatus(NotificationStatus.DELIVERED);
                    notification.setUpdatedAt(completedAt);

                    if (notificationMetrics != null) {
                        notificationMetrics.recordDelivered(notification.getChannel(), provider.getName());
                    }

                    // Create successful NotificationAttempt
                    NotificationAttempt attempt = new NotificationAttempt();
                    attempt.setId(UUID.randomUUID());
                    attempt.setNotification(notification);
                    attempt.setProvider(notification.getProvider());
                    attempt.setAttemptNumber(attemptNumber);
                    attempt.setStatus(AttemptStatus.SUCCESS);
                    attempt.setErrorMessage(null);
                    attempt.setAttemptedAt(attemptedAt);
                    attempt.setCompletedAt(completedAt);

                    notificationAttemptRepository.persist(attempt);

                    LOG.infof("Notification [%s] successfully DELIVERED via provider [%s] on attempt #%d",
                            notificationId, provider.getName(), attemptNumber);
                } else {
                    // Update Notification status and determine retryability
                    OffsetDateTime failedAt = OffsetDateTime.now();
                    notification.setRetryCount(attemptNumber);
                    notification.setUpdatedAt(failedAt);

                    if (notificationMetrics != null) {
                        notificationMetrics.recordFailed(notification.getChannel(), provider.getName(), sendResult.getErrorMessage());
                    }

                    RetryPolicy policy = resolveRetryPolicy();
                    boolean isRetryable = policy.getRetryClassifier().isRetryable(sendResult);

                    if (!isRetryable) {
                        LOG.warnf("Notification [%s] delivery FAILED with non-retryable error via [%s] on attempt #%d: %s. Routing to DLQ.",
                                notificationId, provider.getName(), attemptNumber, sendResult.getErrorMessage());
                        resolveDeadLetterService().routeToDlq(notification, DlqReason.NON_RETRYABLE_ERROR, sendResult.getErrorMessage(), sendResult.getHttpStatusCode(), provider.getName(), null);
                    } else if (attemptNumber <= policy.getMaxRetries()) {
                        long delayMs = policy.calculateDelayMs(attemptNumber, null);
                        OffsetDateTime scheduledAt = failedAt.plus(delayMs, ChronoUnit.MILLIS);

                        notification.setStatus(NotificationStatus.RETRYING);
                        scheduleRetryOutboxEvent(notification, scheduledAt);
                        LOG.infof("Notification [%s] send failed via [%s] (retryable). Scheduled for retry #%d at %s (delay %d ms).",
                                notificationId, provider.getName(), attemptNumber, scheduledAt, delayMs);
                    } else {
                        LOG.errorf("Notification [%s] exceeded max retries (%d). Routing to DLQ.",
                                notificationId, policy.getMaxRetries());
                        resolveDeadLetterService().routeToDlq(notification, DlqReason.MAX_RETRIES_EXCEEDED, sendResult.getErrorMessage(), sendResult.getHttpStatusCode(), provider.getName(), null);
                    }

                    // Create failed NotificationAttempt
                    NotificationAttempt attempt = new NotificationAttempt();
                    attempt.setId(UUID.randomUUID());
                    attempt.setNotification(notification);
                    attempt.setProvider(notification.getProvider());
                    attempt.setAttemptNumber(attemptNumber);
                    attempt.setStatus(AttemptStatus.FAILED);
                    attempt.setErrorMessage(sendResult.getErrorMessage());
                    attempt.setAttemptedAt(attemptedAt);
                    attempt.setCompletedAt(completedAt);

                    notificationAttemptRepository.persist(attempt);
                }
            } catch (Exception ex) {
                LOG.errorf(ex, "Unexpected error processing notification [%s] on attempt #%d", notificationId, attemptNumber);

                if (circuitBreakerEnabled && circuitBreaker != null) {
                    String cbKey = resolveCircuitBreakerKey(notification, null);
                    circuitBreaker.recordException(cbKey, ex, resolveCircuitBreakerConfig());
                }

                OffsetDateTime failedAt = OffsetDateTime.now();
                notification.setRetryCount(attemptNumber);
                notification.setUpdatedAt(failedAt);

                RetryPolicy policy = resolveRetryPolicy();
                boolean isRetryable = policy.getRetryClassifier().isRetryableException(ex);

                if (isRetryable && attemptNumber <= policy.getMaxRetries()) {
                    long delayMs = policy.calculateDelayMs(attemptNumber, null);
                    OffsetDateTime scheduledAt = failedAt.plus(delayMs, ChronoUnit.MILLIS);
                    notification.setStatus(NotificationStatus.RETRYING);
                    scheduleRetryOutboxEvent(notification, scheduledAt);
                } else if (attemptNumber > policy.getMaxRetries()) {
                    resolveDeadLetterService().routeToDlq(notification, DlqReason.MAX_RETRIES_EXCEEDED, ex.getMessage(), null, null, null);
                } else {
                    resolveDeadLetterService().routeToDlq(notification, DlqReason.FATAL_EXCEPTION, ex.getMessage(), null, null, null);
                }

                NotificationAttempt attempt = new NotificationAttempt();
                attempt.setId(UUID.randomUUID());
                attempt.setNotification(notification);
                attempt.setProvider(notification.getProvider());
                attempt.setAttemptNumber(attemptNumber);
                attempt.setStatus(AttemptStatus.FAILED);
                attempt.setErrorMessage(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                attempt.setAttemptedAt(attemptedAt);
                attempt.setCompletedAt(failedAt);

                notificationAttemptRepository.persist(attempt);
            }
        }

    public DeadLetterService resolveDeadLetterService() {
        if (deadLetterService != null) {
            return deadLetterService;
        }
        DeadLetterService fallback = new DeadLetterService();
        fallback.notificationRepository = this.notificationRepository;
        fallback.outboxEventRepository = this.outboxEventRepository;
        fallback.objectMapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();
        return fallback;
    }

    public RetryPolicy resolveRetryPolicy() {
        if (retryPolicy != null) {
            return retryPolicy;
        }
        return new RetryPolicy(maxRetries, new com.notification.retry.ExponentialBackoffStrategy(1000L, 60000L, 2.0, false), new com.notification.retry.RetryClassifier());
    }

    public String resolveRateLimitKey(Notification notification, NotificationProvider provider) {
        if (notification.getChannel() != null) {
            return "channel:" + notification.getChannel().name().toLowerCase();
        }
        if (provider != null && provider.getName() != null) {
            return "provider:" + provider.getName().toLowerCase();
        }
        return "default";
    }

    public String resolveCircuitBreakerKey(Notification notification, NotificationProvider provider) {
        if (provider != null && provider.getName() != null) {
            return "provider:" + provider.getName().toLowerCase();
        }
        if (notification != null && notification.getChannel() != null) {
            return "channel:" + notification.getChannel().name().toLowerCase();
        }
        return "default";
    }

    public CircuitBreakerConfig resolveCircuitBreakerConfig() {
        return new CircuitBreakerConfig(
                cbFailureRateThreshold,
                cbMinimumNumberOfCalls,
                Duration.ofMillis(cbSlidingWindowDurationMs),
                Duration.ofMillis(cbWaitDurationInOpenStateMs),
                cbPermittedNumberOfCallsInHalfOpenState,
                cbHalfOpenSuccessThreshold,
                Duration.ofHours(24)
        );
    }

    public TokenBucketConfig resolveTokenBucketConfig(Notification notification) {
        return TokenBucketConfig.of(defaultCapacity, defaultRefillRate, 1L);
    }

    private void scheduleRetryOutboxEvent(Notification notification, OffsetDateTime scheduledAt) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateId(notification.getId());
        outboxEvent.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED);
        outboxEvent.setPayload(serializePayload(notification));
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setCreatedAt(OffsetDateTime.now());
        outboxEvent.setScheduledAt(scheduledAt != null ? scheduledAt : OffsetDateTime.now());
        outboxEvent.setPublishedAt(null);
        outboxEventRepository.persist(outboxEvent);
    }

    private String serializePayload(Notification notification) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", notification.getId().toString());
            payload.put("recipient", notification.getRecipient());
            payload.put("channel", notification.getChannel() != null ? notification.getChannel().name() : null);
            payload.put("subject", notification.getSubject());
            payload.put("content", notification.getContent());
            payload.put("priority", notification.getPriority() != null ? notification.getPriority().name() : null);
            payload.put("status", notification.getStatus() != null ? notification.getStatus().name() : null);
            payload.put("retryCount", notification.getRetryCount());
            payload.put("createdAt", notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null);
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize notification payload", e);
        }
    }
}
