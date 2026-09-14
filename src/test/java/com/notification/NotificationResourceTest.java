package com.notification;

import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.domain.Priority;
import com.notification.repository.NotificationRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class NotificationResourceTest {

    @Inject
    NotificationRepository notificationRepository;

    @Test
    public void testCreateNotification_ValidEmail_Success() {
        String requestBody = """
                {
                  "recipient": "user@example.com",
                  "channel": "EMAIL",
                  "subject": "Payment successful",
                  "content": "Your payment has been completed.",
                  "priority": "HIGH"
                }
                """;

        String idStr = given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/v1/notifications")
                .then()
                .statusCode(202)
                .body("id", notNullValue())
                .body("status", is("QUEUED"))
                .extract()
                .path("id");

        UUID notificationId = UUID.fromString(idStr);
        Notification saved = notificationRepository.findById(notificationId);
        assertNotNull(saved);
        assertEquals("user@example.com", saved.getRecipient());
        assertEquals(Channel.EMAIL, saved.getChannel());
        assertEquals("Payment successful", saved.getSubject());
        assertEquals("Your payment has been completed.", saved.getContent());
        assertEquals(Priority.HIGH, saved.getPriority());
        assertEquals(NotificationStatus.QUEUED, saved.getStatus());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    public void testCreateNotification_ValidSmsWithoutSubject_Success() {
        String requestBody = """
                {
                  "recipient": "+84901234567",
                  "channel": "SMS",
                  "content": "Your OTP code is 123456",
                  "priority": "CRITICAL"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/v1/notifications")
                .then()
                .statusCode(202)
                .body("id", notNullValue())
                .body("status", is("QUEUED"));
    }

    @Test
    public void testCreateNotification_MissingRecipient_BadRequest() {
        String requestBody = """
                {
                  "recipient": "",
                  "channel": "EMAIL",
                  "subject": "Test",
                  "content": "Hello",
                  "priority": "NORMAL"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/v1/notifications")
                .then()
                .statusCode(400);
    }

    @Test
    public void testCreateNotification_InvalidChannel_BadRequest() {
        String requestBody = """
                {
                  "recipient": "user@example.com",
                  "channel": "INVALID_CHANNEL",
                  "subject": "Test",
                  "content": "Hello",
                  "priority": "NORMAL"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/v1/notifications")
                .then()
                .statusCode(400);
    }

    @Test
    public void testCreateNotification_InvalidPriority_BadRequest() {
        String requestBody = """
                {
                  "recipient": "user@example.com",
                  "channel": "EMAIL",
                  "subject": "Test",
                  "content": "Hello",
                  "priority": "ULTRA_HIGH"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/v1/notifications")
                .then()
                .statusCode(400);
    }

    @Test
    public void testCreateNotification_MissingSubjectForEmail_BadRequest() {
        String requestBody = """
                {
                  "recipient": "user@example.com",
                  "channel": "EMAIL",
                  "content": "Email without subject",
                  "priority": "NORMAL"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/v1/notifications")
                .then()
                .statusCode(400)
                .body("message", is("subject is required for EMAIL channel"));
    }

    @Test
    public void testCreateNotification_MissingContent_BadRequest() {
        String requestBody = """
                {
                  "recipient": "user@example.com",
                  "channel": "EMAIL",
                  "subject": "Test",
                  "content": "",
                  "priority": "NORMAL"
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/api/v1/notifications")
                .then()
                .statusCode(400);
    }
}
