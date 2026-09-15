package com.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.api.dto.CreateNotificationRequest;
import com.notification.application.NotificationService;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import com.notification.domain.Priority;
import com.notification.repository.NotificationRepository;
import com.notification.repository.OutboxEventRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class NotificationResourceTest {

    @Inject
    NotificationRepository notificationRepository;

    @Inject
    OutboxEventRepository outboxEventRepository;

    @Inject
    NotificationService notificationService;

    @Inject
    ObjectMapper objectMapper;

    @Test
    public void testCreateNotification_ValidEmail_TransactionalOutbox_Success() throws Exception {
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

        // 1. Verify Notification in PostgreSQL
        Notification savedNotification = notificationRepository.findById(notificationId);
        assertNotNull(savedNotification);
        assertEquals("user@example.com", savedNotification.getRecipient());
        assertEquals(Channel.EMAIL, savedNotification.getChannel());
        assertEquals("Payment successful", savedNotification.getSubject());
        assertEquals("Your payment has been completed.", savedNotification.getContent());
        assertEquals(Priority.HIGH, savedNotification.getPriority());
        assertEquals(NotificationStatus.QUEUED, savedNotification.getStatus());
        assertEquals(0, savedNotification.getRetryCount());
        assertNotNull(savedNotification.getCreatedAt());
        assertNotNull(savedNotification.getUpdatedAt());

        // 2. Verify OutboxEvent in PostgreSQL
        List<OutboxEvent> outboxEvents = outboxEventRepository.find("aggregateId", notificationId).list();
        assertEquals(1, outboxEvents.size(), "Should have exactly 1 OutboxEvent for the notification");

        OutboxEvent outboxEvent = outboxEvents.get(0);
        assertNotNull(outboxEvent.getId());
        assertEquals(notificationId, outboxEvent.getAggregateId());
        assertEquals("NOTIFICATION_CREATED", outboxEvent.getEventType());
        assertEquals(OutboxStatus.PENDING, outboxEvent.getStatus());
        assertNotNull(outboxEvent.getCreatedAt());
        assertNull(outboxEvent.getPublishedAt());

        // 3. Verify OutboxEvent payload JSON
        JsonNode payload = objectMapper.readTree(outboxEvent.getPayload());
        assertEquals(notificationId.toString(), payload.get("id").asText());
        assertEquals("user@example.com", payload.get("recipient").asText());
        assertEquals("EMAIL", payload.get("channel").asText());
        assertEquals("Payment successful", payload.get("subject").asText());
        assertEquals("Your payment has been completed.", payload.get("content").asText());
        assertEquals("HIGH", payload.get("priority").asText());
        assertEquals("QUEUED", payload.get("status").asText());
        assertEquals(0, payload.get("retryCount").asInt());
        assertNotNull(payload.get("createdAt"));
    }

    @Test
    public void testCreateNotification_ValidInApp_Success() {
        String requestBody = """
                {
                  "recipient": "user-12345",
                  "channel": "IN_APP",
                  "content": "You received a new message",
                  "priority": "NORMAL"
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
        assertEquals(Channel.IN_APP, saved.getChannel());
        assertEquals(0, saved.getRetryCount());
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
        List<OutboxEvent> events = outboxEventRepository.find("aggregateId", notificationId).list();
        assertEquals(1, events.size());
        assertEquals("NOTIFICATION_CREATED", events.get(0).getEventType());
        assertEquals(OutboxStatus.PENDING, events.get(0).getStatus());
    }

    @Test
    public void testTransactionRollback_Atomicity() {
        long initialNotificationCount = notificationRepository.count();
        long initialOutboxCount = outboxEventRepository.count();

        CreateNotificationRequest request = new CreateNotificationRequest(
                "rollback-test@example.com",
                "EMAIL",
                "Rollback Test",
                "This should rollback",
                "NORMAL"
        );

        // Expect exception from simulated failure within transactional boundary
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            notificationService.createNotificationWithSimulatedFailure(request);
        });
        assertTrue(exception.getMessage().contains("Simulated unexpected failure"));

        // Verify that neither Notification nor OutboxEvent was committed to DB
        assertEquals(initialNotificationCount, notificationRepository.count(), "Notification count must not increase after rollback");
        assertEquals(initialOutboxCount, outboxEventRepository.count(), "OutboxEvent count must not increase after rollback");
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
