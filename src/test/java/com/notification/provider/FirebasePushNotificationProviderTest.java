package com.notification.provider;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FirebasePushNotificationProviderTest {

    private FirebasePushNotificationProvider provider;

    @BeforeEach
    public void setup() {
        provider = new FirebasePushNotificationProvider();
        provider.enabled = true;
        provider.projectId = "notification-a0c90";
        provider.serviceAccountLocation = "firebase-service-account.json";
        provider.init();
    }

    @Test
    public void testInitialization() {
        assertTrue(provider.isInitialized(), "FirebasePushNotificationProvider should be initialized from service account JSON");
        assertEquals("FirebasePushNotificationProvider", provider.getName());
    }

    @Test
    public void testSupportsChannel() {
        assertTrue(provider.supportsChannel(Channel.PUSH), "Should support PUSH channel");
        assertFalse(provider.supportsChannel(Channel.EMAIL), "Should not support EMAIL channel");
        assertFalse(provider.supportsChannel(Channel.SMS), "Should not support SMS channel");
        assertFalse(provider.supportsChannel(Channel.IN_APP), "Should not support IN_APP channel");
        assertFalse(provider.supportsChannel(null), "Should not support null channel");
    }

    @Test
    public void testSendWithMissingRecipient() {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setChannel(Channel.PUSH);
        notification.setSubject("Test Title");
        notification.setContent("Test Body");
        notification.setRecipient(null);

        ProviderSendResult result = provider.send(notification);
        assertFalse(result.isSuccess());
        assertEquals(400, result.getHttpStatusCode());
        assertTrue(result.getErrorMessage().contains("Recipient FCM token is missing"));
    }

    @Test
    public void testSendSuccessWithMockMessaging() throws Exception {
        FirebaseMessaging mockMessaging = mock(FirebaseMessaging.class);
        when(mockMessaging.send(any(Message.class))).thenReturn("projects/notification-a0c90/messages/mock-fcm-12345");

        FirebaseMessaging originalMessaging = null;
        try {
            provider.setFirebaseMessaging(mockMessaging);

            Notification notification = new Notification();
            notification.setId(UUID.randomUUID());
            notification.setChannel(Channel.PUSH);
            notification.setRecipient("fake-device-fcm-token");
            notification.setSubject("Special Promotion");
            notification.setContent("Get 50% off today only!");
            notification.setPriority(Priority.HIGH);

            ProviderSendResult result = provider.send(notification);

            assertTrue(result.isSuccess());
            assertEquals("projects/notification-a0c90/messages/mock-fcm-12345", result.getProviderMessageId());
            assertEquals(200, result.getHttpStatusCode());
            assertNull(result.getErrorMessage());
        } finally {
            provider.init(); // Reset to normal instance
        }
    }

    @Test
    public void testSendFailureWithUnexpectedException() throws Exception {
        FirebaseMessaging mockMessaging = mock(FirebaseMessaging.class);
        when(mockMessaging.send(any(Message.class))).thenThrow(new RuntimeException("Connection reset"));

        try {
            provider.setFirebaseMessaging(mockMessaging);

            Notification notification = new Notification();
            notification.setId(UUID.randomUUID());
            notification.setChannel(Channel.PUSH);
            notification.setRecipient("fake-device-fcm-token");
            notification.setSubject("Alert");
            notification.setContent("Critical update");

            ProviderSendResult result = provider.send(notification);

            assertFalse(result.isSuccess());
            assertEquals(500, result.getHttpStatusCode());
            assertTrue(result.getErrorMessage().contains("Connection reset"));
        } finally {
            provider.init();
        }
    }

    @Test
    public void testMapFirebaseErrorCodes() {
        ProviderSendResult quotaRes = provider.mapFirebaseErrorCode(MessagingErrorCode.QUOTA_EXCEEDED, "Daily limit reached");
        assertEquals(429, quotaRes.getHttpStatusCode());
        assertFalse(quotaRes.isSuccess());

        ProviderSendResult unavailRes = provider.mapFirebaseErrorCode(MessagingErrorCode.UNAVAILABLE, "FCM down");
        assertEquals(504, unavailRes.getHttpStatusCode());

        ProviderSendResult invalidRes = provider.mapFirebaseErrorCode(MessagingErrorCode.INVALID_ARGUMENT, "Bad token");
        assertEquals(400, invalidRes.getHttpStatusCode());

        ProviderSendResult unregRes = provider.mapFirebaseErrorCode(MessagingErrorCode.UNREGISTERED, "Token expired");
        assertEquals(400, unregRes.getHttpStatusCode());

        ProviderSendResult internalRes = provider.mapFirebaseErrorCode(MessagingErrorCode.INTERNAL, "Server crashed");
        assertEquals(500, internalRes.getHttpStatusCode());
    }
}
