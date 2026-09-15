package com.notification;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.application.NotificationService;
import com.notification.domain.AttemptStatus;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationAttempt;
import com.notification.domain.NotificationStatus;
import com.notification.provider.MockNotificationProvider;
import com.notification.provider.NotificationProvider;
import com.notification.provider.ProviderRegistry;
import com.notification.repository.NotificationAttemptRepository;
import com.notification.repository.NotificationRepository;
import com.notification.worker.NotificationProcessor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class ProviderClientTest {

    @Inject
    ProviderRegistry providerRegistry;

    @Inject
    MockNotificationProvider mockNotificationProvider;

    @Inject
    NotificationService notificationService;

    @Inject
    NotificationProcessor notificationProcessor;

    @Inject
    NotificationRepository notificationRepository;

    @Inject
    NotificationAttemptRepository notificationAttemptRepository;

    @BeforeEach
    public void setup() {
        mockNotificationProvider.setEnabled(true);
        mockNotificationProvider.resetSimulationMode();
    }

    @AfterEach
    public void teardown() {
        mockNotificationProvider.setEnabled(true);
        mockNotificationProvider.resetSimulationMode();
    }

    @Test
    public void testProviderRegistry_AllChannelsSupported() {
        for (Channel channel : Channel.values()) {
            Optional<NotificationProvider> providerOpt = providerRegistry.getProviderForChannel(channel);
            assertTrue(providerOpt.isPresent(), "Should find provider for channel: " + channel);
            assertEquals(MockNotificationProvider.PROVIDER_NAME, providerOpt.get().getName());
        }
    }

    @Test
    public void testProvider_SuccessfulDelivery() {
        mockNotificationProvider.setSimulationMode(MockNotificationProvider.SimulationMode.SUCCESS);

        CreateNotificationResponse res = notificationService.createNotification(
                new CreateNotificationRequest("success@example.com", "SMS", null, "Hello Success", "NORMAL")
        );
        UUID notificationId = res.getId();

        notificationProcessor.processNotification(notificationId);

        notificationRepository.getEntityManager().clear();
        Notification notification = notificationRepository.findById(notificationId);
        assertNotNull(notification);
        assertEquals(NotificationStatus.DELIVERED, notification.getStatus());

        notificationAttemptRepository.getEntityManager().clear();
        List<NotificationAttempt> attempts = notificationAttemptRepository.findByNotificationId(notificationId);
        assertEquals(1, attempts.size());

        NotificationAttempt attempt = attempts.get(0);
        assertEquals(AttemptStatus.SUCCESS, attempt.getStatus());
        assertNull(attempt.getErrorMessage());
        assertEquals(1, attempt.getAttemptNumber());
        assertNotNull(attempt.getAttemptedAt());
        assertNotNull(attempt.getCompletedAt());
    }

    @Test
    public void testProvider_RateLimit429_Failure() {
        mockNotificationProvider.setSimulationMode(MockNotificationProvider.SimulationMode.RATE_LIMIT_429);

        CreateNotificationResponse res = notificationService.createNotification(
                new CreateNotificationRequest("ratelimit@example.com", "SMS", null, "Hello Rate Limit", "HIGH")
        );
        UUID notificationId = res.getId();

        notificationProcessor.processNotification(notificationId);

        notificationRepository.getEntityManager().clear();
        Notification notification = notificationRepository.findById(notificationId);
        assertNotNull(notification);
        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        notificationAttemptRepository.getEntityManager().clear();
        List<NotificationAttempt> attempts = notificationAttemptRepository.findByNotificationId(notificationId);
        assertEquals(1, attempts.size());

        NotificationAttempt attempt = attempts.get(0);
        assertEquals(AttemptStatus.FAILED, attempt.getStatus());
        assertNotNull(attempt.getErrorMessage());
        assertTrue(attempt.getErrorMessage().contains("429"), "Error message should contain 429: " + attempt.getErrorMessage());
    }

    @Test
    public void testProvider_ServerError500_Failure() {
        mockNotificationProvider.setSimulationMode(MockNotificationProvider.SimulationMode.SERVER_ERROR_500);

        CreateNotificationResponse res = notificationService.createNotification(
                new CreateNotificationRequest("servererror@example.com", "EMAIL", "Server Error Subject", "Hello 500", "CRITICAL")
        );
        UUID notificationId = res.getId();

        notificationProcessor.processNotification(notificationId);

        notificationRepository.getEntityManager().clear();
        Notification notification = notificationRepository.findById(notificationId);
        assertNotNull(notification);
        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        notificationAttemptRepository.getEntityManager().clear();
        List<NotificationAttempt> attempts = notificationAttemptRepository.findByNotificationId(notificationId);
        assertEquals(1, attempts.size());

        NotificationAttempt attempt = attempts.get(0);
        assertEquals(AttemptStatus.FAILED, attempt.getStatus());
        assertNotNull(attempt.getErrorMessage());
        assertTrue(attempt.getErrorMessage().contains("500"), "Error message should contain 500: " + attempt.getErrorMessage());
    }

    @Test
    public void testProvider_Timeout_Failure() {
        mockNotificationProvider.setSimulationMode(MockNotificationProvider.SimulationMode.TIMEOUT);

        CreateNotificationResponse res = notificationService.createNotification(
                new CreateNotificationRequest("timeout@example.com", "IN_APP", null, "Hello Timeout", "LOW")
        );
        UUID notificationId = res.getId();

        notificationProcessor.processNotification(notificationId);

        notificationRepository.getEntityManager().clear();
        Notification notification = notificationRepository.findById(notificationId);
        assertNotNull(notification);
        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        assertEquals(1, notification.getRetryCount());

        notificationAttemptRepository.getEntityManager().clear();
        List<NotificationAttempt> attempts = notificationAttemptRepository.findByNotificationId(notificationId);
        assertEquals(1, attempts.size());

        NotificationAttempt attempt = attempts.get(0);
        assertEquals(AttemptStatus.FAILED, attempt.getStatus());
        assertNotNull(attempt.getErrorMessage());
        assertTrue(attempt.getErrorMessage().toLowerCase().contains("timeout") || attempt.getErrorMessage().contains("504"),
                "Error message should mention timeout: " + attempt.getErrorMessage());
    }

    @Test
    public void testProvider_Disabled_NoProviderAvailable() {
        mockNotificationProvider.setEnabled(false);

        CreateNotificationResponse res = notificationService.createNotification(
                new CreateNotificationRequest("noprovider@example.com", "SMS", null, "No Provider", "NORMAL")
        );
        UUID notificationId = res.getId();

        notificationProcessor.processNotification(notificationId);

        notificationRepository.getEntityManager().clear();
        Notification notification = notificationRepository.findById(notificationId);
        assertNotNull(notification);
        assertEquals(NotificationStatus.FAILED, notification.getStatus());

        notificationAttemptRepository.getEntityManager().clear();
        List<NotificationAttempt> attempts = notificationAttemptRepository.findByNotificationId(notificationId);
        assertEquals(1, attempts.size());

        NotificationAttempt attempt = attempts.get(0);
        assertEquals(AttemptStatus.FAILED, attempt.getStatus());
        assertNotNull(attempt.getErrorMessage());
        assertTrue(attempt.getErrorMessage().contains("No provider available"),
                "Error message should state no provider: " + attempt.getErrorMessage());
    }
}
