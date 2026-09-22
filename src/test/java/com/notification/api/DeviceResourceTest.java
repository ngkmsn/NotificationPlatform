package com.notification.api;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.api.dto.RegisterDeviceRequest;
import com.notification.application.DeviceService;
import com.notification.application.NotificationService;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.Priority;
import com.notification.repository.NotificationRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class DeviceResourceTest {

    @Inject
    DeviceService deviceService;

    @Inject
    NotificationService notificationService;

    @Inject
    NotificationRepository notificationRepository;

    @Test
    public void testRegisterDeviceViaApi() {
        RegisterDeviceRequest request = new RegisterDeviceRequest("user_test_123", "fcm_token_sample_12345", "WEB");

        given()
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/v1/devices")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("userId", equalTo("user_test_123"))
                .body("deviceToken", equalTo("fcm_token_sample_12345"))
                .body("platform", equalTo("WEB"))
                .body("isActive", equalTo(true));
    }

    @Test
    public void testGetDevicesByUserId() {
        deviceService.registerDevice(new RegisterDeviceRequest("user_abc", "token_abc_1", "WEB"));
        deviceService.registerDevice(new RegisterDeviceRequest("user_abc", "token_abc_2", "ANDROID"));

        given()
                .when()
                .get("/api/v1/devices/user_abc")
                .then()
                .statusCode(200)
                .body("size()", equalTo(2));
    }

    @Test
    public void testPushNotificationResolvedByUserId() {
        String testUser = "user_push_target";
        String testToken = "token_target_fcm_xyz";

        deviceService.registerDevice(new RegisterDeviceRequest(testUser, testToken, "WEB"));

        CreateNotificationRequest request = new CreateNotificationRequest();
        request.setRecipient(testUser);
        request.setChannel("PUSH");
        request.setPriority("HIGH");
        request.setSubject("Test Order");
        request.setContent("Your order has arrived");

        CreateNotificationResponse response = notificationService.createNotification(request);
        assertNotNull(response.getId());

        Notification persisted = notificationRepository.findById(response.getId());
        assertNotNull(persisted);
        assertEquals(testToken, persisted.getRecipient());
        assertEquals(Channel.PUSH, persisted.getChannel());
        assertEquals(Priority.HIGH, persisted.getPriority());
    }
}
