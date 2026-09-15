package com.notification.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.domain.AttemptStatus;
import com.notification.domain.Notification;
import com.notification.domain.NotificationAttempt;
import com.notification.domain.NotificationStatus;
import com.notification.provider.NotificationProvider;
import com.notification.provider.ProviderRegistry;
import com.notification.provider.ProviderSendResult;
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
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
    ProviderRegistry providerRegistry;

    @Inject
    ObjectMapper objectMapper;

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
        OffsetDateTime attemptedAt = OffsetDateTime.now();

        QuarkusTransaction.requiringNew().run(() -> {
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
                    // Update Notification status to FAILED
                    notification.setStatus(NotificationStatus.FAILED);
                    notification.setRetryCount(attemptNumber);
                    notification.setUpdatedAt(completedAt);

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
        });
    }
}
