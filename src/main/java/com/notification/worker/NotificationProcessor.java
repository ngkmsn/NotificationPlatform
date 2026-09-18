package com.notification.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.application.NotificationService;
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
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
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
    TokenBucketRateLimiter tokenBucketRateLimiter;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "ratelimit.worker.enabled", defaultValue = "true")
    public boolean rateLimitEnabled = true;

    @ConfigProperty(name = "ratelimit.default.capacity", defaultValue = "100")
    public long defaultCapacity = 100L;

    @ConfigProperty(name = "ratelimit.default.refill-rate", defaultValue = "20.0")
    public double defaultRefillRate = 20.0;

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

    public void doProcessNotification(UUID notificationId) {
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
                    notification.setStatus(NotificationStatus.FAILED);
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
                        String errorMsg = String.format("Rate limit exceeded for channel [%s]. Retry after %d ms",
                                notification.getChannel(), waitMs);

                        LOG.warnf("Notification [%s] throttled by Rate Limiter: %s", notificationId, errorMsg);

                        // Update Notification status to RETRYING
                        notification.setStatus(NotificationStatus.RETRYING);
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
                        if (attemptNumber <= maxRetries) {
                            scheduleRetryOutboxEvent(notification);
                            LOG.infof("Notification [%s] scheduled for retry (attempt #%d <= max %d)",
                                    notificationId, attemptNumber, maxRetries);
                        } else {
                            notification.setStatus(NotificationStatus.DEAD_LETTER);
                            LOG.errorf("Notification [%s] exceeded max retries (%d). Marked as DEAD_LETTER.",
                                    notificationId, maxRetries);
                        }
                        return;
                    }
                }

                ProviderSendResult sendResult = provider.send(notification);
                OffsetDateTime completedAt = OffsetDateTime.now();

                if (sendResult.isSuccess()) {
                    // Update Notification status to DELIVERED
                    notification.setStatus(NotificationStatus.DELIVERED);
                    notification.setUpdatedAt(completedAt);

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
                    // Update Notification status to FAILED or RETRYING
                    OffsetDateTime failedAt = OffsetDateTime.now();
                    notification.setRetryCount(attemptNumber);
                    notification.setUpdatedAt(failedAt);

                    if (attemptNumber <= maxRetries) {
                        notification.setStatus(NotificationStatus.RETRYING);
                        scheduleRetryOutboxEvent(notification);
                        LOG.infof("Notification [%s] send failed via [%s]. Re-queued for retry #%d.",
                                notificationId, provider.getName(), attemptNumber);
                    } else {
                        notification.setStatus(NotificationStatus.FAILED);
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

                    LOG.warnf("Notification [%s] delivery FAILED via provider [%s] on attempt #%d: %s",
                            notificationId, provider.getName(), attemptNumber, sendResult.getErrorMessage());
                }
            } catch (Exception ex) {
                LOG.errorf(ex, "Unexpected error processing notification [%s] on attempt #%d", notificationId, attemptNumber);

                OffsetDateTime failedAt = OffsetDateTime.now();

                notification.setStatus(NotificationStatus.FAILED);
                notification.setRetryCount(attemptNumber);
                notification.setUpdatedAt(failedAt);

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

    public String resolveRateLimitKey(Notification notification, NotificationProvider provider) {
        if (notification.getChannel() != null) {
            return "channel:" + notification.getChannel().name().toLowerCase();
        }
        if (provider != null && provider.getName() != null) {
            return "provider:" + provider.getName().toLowerCase();
        }
        return "default";
    }

    public TokenBucketConfig resolveTokenBucketConfig(Notification notification) {
        return TokenBucketConfig.of(defaultCapacity, defaultRefillRate, 1L);
    }

    private void scheduleRetryOutboxEvent(Notification notification) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateId(notification.getId());
        outboxEvent.setEventType(NotificationService.EVENT_TYPE_NOTIFICATION_CREATED);
        outboxEvent.setPayload(serializePayload(notification));
        outboxEvent.setStatus(OutboxStatus.PENDING);
        outboxEvent.setCreatedAt(OffsetDateTime.now());
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
