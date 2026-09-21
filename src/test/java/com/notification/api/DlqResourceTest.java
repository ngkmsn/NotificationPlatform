package com.notification.api;

import com.notification.api.dto.DlqActionResponse;
import com.notification.api.dto.DlqAttemptDto;
import com.notification.api.dto.DlqNotificationDetailResponse;
import com.notification.api.dto.DlqNotificationSummaryResponse;
import com.notification.api.dto.PageResponse;
import com.notification.application.ValidationException;
import com.notification.dlq.DeadLetterService;
import com.notification.domain.AttemptStatus;
import com.notification.domain.Channel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.Priority;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class DlqResourceTest {

    private DlqResource dlqResource;
    private DeadLetterService mockDeadLetterService;

    @BeforeEach
    public void setup() {
        dlqResource = new DlqResource();
        mockDeadLetterService = mock(DeadLetterService.class);
        dlqResource.deadLetterService = mockDeadLetterService;
    }

    @Test
    @DisplayName("GET /api/v1/dlq returns 200 OK with paginated DLQ items")
    public void testListDlqSuccess() {
        UUID id = UUID.randomUUID();
        DlqNotificationSummaryResponse summary = new DlqNotificationSummaryResponse(
                id, "test@example.com", Channel.EMAIL, "Test Subject",
                Priority.HIGH, NotificationStatus.DEAD_LETTER, "EmailProvider",
                3, OffsetDateTime.now(), OffsetDateTime.now()
        );
        PageResponse<DlqNotificationSummaryResponse> pageResponse = new PageResponse<>(
                List.of(summary), 0, 20, 1L
        );

        when(mockDeadLetterService.getDlqNotifications(eq(Channel.EMAIL), eq("test@example.com"), eq(0), eq(20)))
                .thenReturn(pageResponse);

        Response response = dlqResource.listDlq("EMAIL", "test@example.com", 0, 20);

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        PageResponse<DlqNotificationSummaryResponse> entity = (PageResponse<DlqNotificationSummaryResponse>) response.getEntity();
        assertNotNull(entity);
        assertEquals(1, entity.getItems().size());
        assertEquals(id, entity.getItems().get(0).getId());
        assertEquals("test@example.com", entity.getItems().get(0).getRecipient());
    }

    @Test
    @DisplayName("GET /api/v1/dlq with invalid channel throws ValidationException")
    public void testListDlqInvalidChannel() {
        ValidationException ex = assertThrows(ValidationException.class, () ->
                dlqResource.listDlq("INVALID_CHANNEL", null, 0, 20)
        );
        assertTrue(ex.getMessage().contains("Invalid channel: INVALID_CHANNEL"));
    }

    @Test
    @DisplayName("GET /api/v1/dlq/{id} returns 200 OK with detailed view and attempts")
    public void testGetDlqDetailSuccess() {
        UUID id = UUID.randomUUID();
        DlqAttemptDto attempt = new DlqAttemptDto(
                UUID.randomUUID(), 1, AttemptStatus.FAILED, "EmailProvider",
                "Connection timeout", OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().minusMinutes(4)
        );
        DlqNotificationDetailResponse detail = new DlqNotificationDetailResponse(
                id, "user@example.com", Channel.EMAIL, "Alert", "Alert content",
                Priority.NORMAL, NotificationStatus.DEAD_LETTER, "EmailProvider",
                3, OffsetDateTime.now().minusHours(1), OffsetDateTime.now(),
                List.of(attempt)
        );

        when(mockDeadLetterService.getDlqDetail(id)).thenReturn(detail);

        Response response = dlqResource.getDlqDetail(id);

        assertEquals(200, response.getStatus());
        DlqNotificationDetailResponse entity = (DlqNotificationDetailResponse) response.getEntity();
        assertNotNull(entity);
        assertEquals(id, entity.getId());
        assertEquals("Alert content", entity.getContent());
        assertEquals(1, entity.getAttempts().size());
        assertEquals("Connection timeout", entity.getAttempts().get(0).getErrorMessage());
    }

    @Test
    @DisplayName("GET /api/v1/dlq/{id} propagates NotFoundException when item not found")
    public void testGetDlqDetailNotFound() {
        UUID id = UUID.randomUUID();
        when(mockDeadLetterService.getDlqDetail(id)).thenThrow(new NotFoundException("Notification not found in DLQ with id: " + id));

        assertThrows(NotFoundException.class, () -> dlqResource.getDlqDetail(id));
    }

    @Test
    @DisplayName("POST /api/v1/dlq/{id}/retry returns 200 OK and QUEUED action response")
    public void testRetryDlqSuccess() {
        UUID id = UUID.randomUUID();
        DlqActionResponse actionResponse = new DlqActionResponse(id, NotificationStatus.QUEUED, "Notification successfully re-queued for processing");

        when(mockDeadLetterService.retryDlqNotification(id)).thenReturn(actionResponse);

        Response response = dlqResource.retryDlq(id);

        assertEquals(200, response.getStatus());
        DlqActionResponse entity = (DlqActionResponse) response.getEntity();
        assertNotNull(entity);
        assertEquals(id, entity.getId());
        assertEquals(NotificationStatus.QUEUED, entity.getStatus());
    }

    @Test
    @DisplayName("POST /api/v1/dlq/{id}/cancel returns 200 OK and CANCELLED action response")
    public void testCancelDlqSuccess() {
        UUID id = UUID.randomUUID();
        DlqActionResponse actionResponse = new DlqActionResponse(id, NotificationStatus.CANCELLED, "Notification successfully cancelled from DLQ");

        when(mockDeadLetterService.cancelDlqNotification(id)).thenReturn(actionResponse);

        Response response = dlqResource.cancelDlq(id);

        assertEquals(200, response.getStatus());
        DlqActionResponse entity = (DlqActionResponse) response.getEntity();
        assertNotNull(entity);
        assertEquals(id, entity.getId());
        assertEquals(NotificationStatus.CANCELLED, entity.getStatus());
    }

    @Test
    @DisplayName("Concurrent retry requests for the same notification execute safely")
    public void testConcurrentRetryRequests() throws Exception {
        int threads = 10;
        UUID id = UUID.randomUUID();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Simulate that only first call succeeds, subsequent calls throw IllegalStateException
        AtomicInteger callCount = new AtomicInteger(0);
        when(mockDeadLetterService.retryDlqNotification(id)).thenAnswer(invocation -> {
            if (callCount.incrementAndGet() == 1) {
                return new DlqActionResponse(id, NotificationStatus.QUEUED, "Success");
            }
            throw new IllegalStateException("Cannot retry notification [" + id + "] with status [QUEUED]");
        });

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Response resp = dlqResource.retryDlq(id);
                    if (resp.getStatus() == 200) {
                        successCount.incrementAndGet();
                    }
                } catch (IllegalStateException e) {
                    failCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished);
        assertEquals(1, successCount.get(), "Only 1 retry should succeed");
        assertEquals(threads - 1, failCount.get(), "Remaining concurrent retries should be rejected");
    }
}
