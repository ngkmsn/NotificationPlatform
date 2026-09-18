package com.notification;

import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.Priority;
import com.notification.provider.FirebasePushNotificationProvider;
import com.notification.provider.ProviderSendResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * Công cụ CLI để gửi thử Push Notification trực tiếp đến Token thiết bị thật từ dòng lệnh.
 * Cách dùng:
 *   mvn test -Dtest=FirebaseTestCli -Dfcm.token="<TOKEN_THIẾT_BỊ_CỦA_BẠN>"
 */
public class FirebaseTestCli {

    @Test
    public void sendPushNotificationToDevice() {
        String token = System.getProperty("fcm.token");
        if (token == null || token.isBlank()) {
            System.out.println("⚠️  Chưa truyền tham số -Dfcm.token");
            System.out.println("👉 Cú pháp gửi test: mvn test -Dtest=FirebaseTestCli -Dfcm.token=\"<TOKEN_CỦA_BẠN>\"");
            return;
        }

        System.out.println("🚀 Đang gửi Push Notification tới thiết bị có FCM Token: " + token);

        FirebasePushNotificationProvider provider = new FirebasePushNotificationProvider();
        provider.enabled = true;
        provider.projectId = "notification-a0c90";
        provider.serviceAccountLocation = "firebase-service-account.json";
        provider.init();

        Notification notification = new Notification();
        notification.setId(UUID.randomUUID());
        notification.setChannel(Channel.PUSH);
        notification.setSubject("🔔 Thông báo thử nghiệm từ Notification Platform");
        notification.setContent("Xin chào! Tính năng Push Notification qua Firebase FCM đã hoạt động thành công 100%!");
        notification.setPriority(Priority.HIGH);
        notification.setRecipient(token.trim());

        ProviderSendResult result = provider.send(notification);

        System.out.println("=================================================");
        if (result.isSuccess()) {
            System.out.println("✅ GỬI THÀNH CÔNG ĐẾN THIẾT BỊ!");
            System.out.println("FCM Message ID: " + result.getProviderMessageId());
            System.out.println("Hãy kiểm tra thanh thông báo trên thiết bị của bạn.");
        } else {
            System.out.println("❌ GỬI THẤT BẠI:");
            System.out.println("Mã lỗi HTTP: " + result.getHttpStatusCode());
            System.out.println("Chi tiết lỗi: " + result.getErrorMessage());
        }
        System.out.println("=================================================");
    }
}
