package com.notification.provider;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.Priority;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class FirebaseLiveConnectivityTest {

    @Test
    public void testLiveAuthenticationWithGoogleFirebase() {
        FirebasePushNotificationProvider provider = new FirebasePushNotificationProvider();
        provider.enabled = true;
        provider.projectId = "notification-a0c90";
        provider.serviceAccountLocation = "firebase-service-account.json";
        provider.init();

        assertTrue(provider.isInitialized(), "Firebase Admin SDK should initialize successfully with Google Cloud");

        // Gửi một notification với token giả lập lên trực tiếp máy chủ Google FCM
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setChannel(Channel.PUSH);
        notification.setSubject("Test Live Firebase");
        notification.setContent("Nội dung thông báo thử nghiệm");
        notification.setPriority(Priority.NORMAL);
        // Token giả định để kiểm tra phản hồi từ Google
        notification.setRecipient("bk3RNwTe3H0:CI2k_HHwgIpoDKCIZvvDMExUdFQ3P1...");

        ProviderSendResult result = provider.send(notification);
        System.out.println("=================================================");
        System.out.println("Kết quả phản hồi trực tiếp từ Google Firebase FCM:");
        System.out.println("HTTP Status Code: " + result.getHttpStatusCode());
        System.out.println("Error Message: " + result.getErrorMessage());
        System.out.println("=================================================");

        // Máy chủ Google sẽ trả về mã lỗi HTTP 400 (Client error - INVALID_ARGUMENT hoặc UNREGISTERED)
        // Điều này chứng minh:
        // 1. Private Key hợp lệ và Google đã cấp OAuth2 Access Token thành công
        // 2. Project ID notification-a0c90 kết nối thông suốt tới fcm.googleapis.com
        assertNotNull(result);
        assertEquals(400, result.getHttpStatusCode(), "Google FCM phản hồi 400 (INVALID_ARGUMENT / UNREGISTERED) cho test token");
    }
}
