package com.notification.guard;

import com.notification.api.NotificationResource;
import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.application.NotificationService;
import com.notification.domain.NotificationStatus;
import com.notification.domain.Priority;
import com.notification.metrics.NotificationMetrics;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class SystemLoadGuardTest {

    private SystemLoadGuard systemLoadGuard;
    private NotificationService mockNotificationService;
    private NotificationMetrics mockNotificationMetrics;
    private RedisDataSource mockRedisDataSource;
    private ValueCommands<String, String> mockValueCommands;
    private KeyCommands<String> mockKeyCommands;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setup() throws Exception {
        systemLoadGuard = new SystemLoadGuard();

        mockNotificationService = mock(NotificationService.class);
        mockNotificationMetrics = mock(NotificationMetrics.class);
        mockRedisDataSource = mock(RedisDataSource.class);
        mockValueCommands = mock(ValueCommands.class);
        mockKeyCommands = mock(KeyCommands.class);

        when(mockRedisDataSource.value(String.class)).thenReturn(mockValueCommands);
        when(mockRedisDataSource.key()).thenReturn(mockKeyCommands);
        when(mockValueCommands.setnx(any(), any())).thenReturn(true);

        Instance<NotificationService> mockNotiInstance = mock(Instance.class);
        when(mockNotiInstance.isResolvable()).thenReturn(true);
        when(mockNotiInstance.get()).thenReturn(mockNotificationService);

        Instance<RedisDataSource> mockRedisInstance = mock(Instance.class);
        when(mockRedisInstance.isResolvable()).thenReturn(true);
        when(mockRedisInstance.get()).thenReturn(mockRedisDataSource);

        setField(systemLoadGuard, "enabled", true);
        setField(systemLoadGuard, "warningThreshold", 75.0);
        setField(systemLoadGuard, "throttleThreshold", 85.0);
        setField(systemLoadGuard, "cutoffThreshold", 95.0);
        setField(systemLoadGuard, "cpuThreshold", 85.0);
        setField(systemLoadGuard, "ramThreshold", 85.0);
        setField(systemLoadGuard, "ramCutoffThreshold", 95.0);
        setField(systemLoadGuard, "recoveryThreshold", 70.0);
        setField(systemLoadGuard, "debounceMinutes", 10);
        setField(systemLoadGuard, "adminRecipient", "admin");
        setField(systemLoadGuard, "simulationModeConfig", false);
        setField(systemLoadGuard, "notificationServiceInstance", mockNotiInstance);
        setField(systemLoadGuard, "notificationMetrics", mockNotificationMetrics);
        setField(systemLoadGuard, "redisDataSourceInstance", mockRedisInstance);

        systemLoadGuard.init();
        systemLoadGuard.resetForTest();
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @DisplayName("Multi-tier State Transitions: Chuyển đổi chính xác giữa NORMAL, WARNING, THROTTLED (85%) và CRITICAL_CUTOFF (95%)")
    public void testHardwareStateTransitions_AndPriorityThrottling() {
        assertEquals(HardwareLoadState.NORMAL, systemLoadGuard.getCurrentState());
        assertFalse(systemLoadGuard.isThrottled());
        assertFalse(systemLoadGuard.isCutoff());

        // 1. Tải 78% CPU (Vùng WARNING)
        systemLoadGuard.enableSimulation(78.0, 60.0);
        assertEquals(HardwareLoadState.WARNING, systemLoadGuard.getCurrentState());
        assertTrue(systemLoadGuard.isWarning());
        assertFalse(systemLoadGuard.isThrottled());
        assertFalse(systemLoadGuard.isCutoff());
        assertFalse(systemLoadGuard.shouldThrottlePriority(Priority.LOW));

        // 2. Tải 88% CPU (Vùng THROTTLED - Ngưỡng 85%)
        systemLoadGuard.enableSimulation(88.0, 70.0);
        assertEquals(HardwareLoadState.THROTTLED, systemLoadGuard.getCurrentState());
        assertTrue(systemLoadGuard.isThrottled());
        assertFalse(systemLoadGuard.isCutoff());

        // Phân luồng ưu tiên tại 85%: Cắt LOW, NORMAL; Giữ HIGH, CRITICAL
        assertTrue(systemLoadGuard.shouldThrottlePriority(Priority.LOW), "LOW priority phải bị throttled tại 85%");
        assertTrue(systemLoadGuard.shouldThrottlePriority(Priority.NORMAL), "NORMAL priority phải bị throttled tại 85%");
        assertFalse(systemLoadGuard.shouldThrottlePriority(Priority.HIGH), "HIGH priority KHÔNG được bị throttled tại 85%");
        assertFalse(systemLoadGuard.shouldThrottlePriority(Priority.CRITICAL), "CRITICAL priority KHÔNG được bị throttled tại 85%");

        // 3. Tải 97% CPU (Vùng CRITICAL_CUTOFF - Ngưỡng 95%)
        systemLoadGuard.enableSimulation(97.0, 75.0);
        assertEquals(HardwareLoadState.CRITICAL_CUTOFF, systemLoadGuard.getCurrentState());
        assertTrue(systemLoadGuard.isCutoff());
        assertTrue(systemLoadGuard.isThrottled());

        // Tại 95% Hard Cutoff: Toàn bộ mọi priority (kể cả CRITICAL) đều bị ngắt
        assertTrue(systemLoadGuard.shouldThrottlePriority(Priority.CRITICAL), "CRITICAL priority phải bị chặn khi kích hoạt Cutoff");
        assertTrue(systemLoadGuard.shouldThrottlePriority(Priority.HIGH), "HIGH priority phải bị chặn khi kích hoạt Cutoff");
    }

    @Test
    @DisplayName("Hysteresis Recovery: Hạ nhiệt từ CUTOFF (95%) -> THROTTLED (85%) -> Đủ 2 chu kỳ (<70%) mới về NORMAL")
    public void testHysteresisRecovery_RequiresTwoConsecutiveSafeChecks() {
        // Đưa hệ thống vào trạng thái Cutoff (97%)
        systemLoadGuard.enableSimulation(97.0, 80.0);
        assertEquals(HardwareLoadState.CRITICAL_CUTOFF, systemLoadGuard.getCurrentState());

        // Hạ nhiệt về 88% -> Xuống mức THROTTLED
        systemLoadGuard.enableSimulation(88.0, 70.0);
        assertEquals(HardwareLoadState.THROTTLED, systemLoadGuard.getCurrentState());

        // Chu kỳ an toàn lần 1 (CPU 50%, RAM 50%) -> Vẫn giữ THROTTLED do Hysteresis
        systemLoadGuard.enableSimulation(50.0, 50.0);
        assertEquals(HardwareLoadState.THROTTLED, systemLoadGuard.getCurrentState(),
                "Lần kiểm tra an toàn thứ 1 chưa được tắt throttle để chống chập chờn (Hysteresis)");

        // Chu kỳ an toàn lần 2 -> Đạt 2/2 điều kiện an toàn, phục hồi về NORMAL
        systemLoadGuard.evaluateHardwareHealth();
        assertEquals(HardwareLoadState.NORMAL, systemLoadGuard.getCurrentState(),
                "Lần kiểm tra an toàn thứ 2 phải phục hồi hệ thống về NORMAL");
        assertFalse(systemLoadGuard.isThrottled());
        assertFalse(systemLoadGuard.isCutoff());

        // Xác nhận đã bắn tin Push phục hồi tới Admin
        verify(mockNotificationService, atLeastOnce()).createNotification(argThat(req ->
                "admin".equals(req.getRecipient()) &&
                req.getSubject().contains("HỆ THỐNG PHỤC HỒI")
        ));
    }

    @Test
    @DisplayName("Debounce Lock: Không spam thông báo tới Admin nếu hệ thống tiếp tục quá tải trong thời gian cooldown")
    public void testDebounceSuppression_PreventsNotificationStorm() {
        // Lần 1: Quá tải -> Gửi push
        systemLoadGuard.enableSimulation(88.0, 70.0);
        assertEquals(HardwareLoadState.THROTTLED, systemLoadGuard.getCurrentState());
        verify(mockNotificationService, times(1)).createNotification(any());

        // Lần 2: Giả lập Redis lock từ chối cấp quyền (đang trong 10 phút cooldown)
        when(mockValueCommands.setnx(any(), any())).thenReturn(false);
        systemLoadGuard.evaluateHardwareHealth();
        systemLoadGuard.evaluateHardwareHealth();

        // Số lần gửi vẫn là 1 (không bị spam)
        verify(mockNotificationService, times(1)).createNotification(any());
    }

    @Test
    @DisplayName("API Load Shedding (85%) & Emergency Cutoff (95%): NotificationResource trả 429 và 503 chính xác")
    public void testNotificationResource_LoadSheddingAndCutoff() throws Exception {
        NotificationResource resource = new NotificationResource();
        setField(resource, "systemLoadGuard", systemLoadGuard);
        setField(resource, "notificationMetrics", mockNotificationMetrics);
        setField(resource, "notificationService", mockNotificationService);

        when(mockNotificationService.createNotification(any())).thenReturn(
                new CreateNotificationResponse(UUID.randomUUID(), NotificationStatus.CREATED)
        );

        // --- GIAI ĐOẠN 1: Tải 88% (THROTTLED) ---
        systemLoadGuard.enableSimulation(88.0, 75.0);
        assertTrue(systemLoadGuard.isThrottled());
        assertFalse(systemLoadGuard.isCutoff());

        // 1a. Gửi tin LOW priority -> Bị từ chối với HTTP 429 Too Many Requests
        CreateNotificationRequest lowReq = new CreateNotificationRequest();
        lowReq.setRecipient("user@example.com");
        lowReq.setChannel("EMAIL");
        lowReq.setSubject("Khuyến mãi tuần");
        lowReq.setContent("Giảm giá 50%");
        lowReq.setPriority("LOW");

        Response lowResponse = resource.createNotification(lowReq);
        assertEquals(429, lowResponse.getStatus(), "Tin LOW phải bị từ chối với HTTP 429 tại ngưỡng 85%");
        assertEquals("30", lowResponse.getHeaderString("Retry-After"));
        Map<?, ?> lowBody = (Map<?, ?>) lowResponse.getEntity();
        assertEquals(429, lowBody.get("status"));
        assertTrue(lowBody.get("message").toString().contains("System is experiencing high traffic"));

        // 1b. Gửi tin CRITICAL priority (OTP) -> Được tiếp nhận với HTTP 202 ACCEPTED
        CreateNotificationRequest criticalReq = new CreateNotificationRequest();
        criticalReq.setRecipient("user@example.com");
        criticalReq.setChannel("EMAIL");
        criticalReq.setSubject("Mã OTP Đăng nhập");
        criticalReq.setContent("OTP của bạn là 123456");
        criticalReq.setPriority("CRITICAL");

        Response criticalResponse = resource.createNotification(criticalReq);
        assertEquals(202, criticalResponse.getStatus(), "Tin CRITICAL vẫn được tiếp nhận khi ở mức 85%");

        // --- GIAI ĐOẠN 2: Tải 97% (CRITICAL_CUTOFF) ---
        systemLoadGuard.enableSimulation(97.0, 90.0);
        assertTrue(systemLoadGuard.isCutoff());

        Response cutoffResponse = resource.createNotification(criticalReq);
        assertEquals(503, cutoffResponse.getStatus(), "Tin CRITICAL cũng phải bị ngắt với HTTP 503 khi ở mức 95% Cutoff");
        assertEquals("60", cutoffResponse.getHeaderString("Retry-After"));
        Map<?, ?> cutoffBody = (Map<?, ?>) cutoffResponse.getEntity();
        assertEquals(503, cutoffBody.get("status"));
        assertTrue(cutoffBody.get("message").toString().contains("Service is temporarily unavailable due to high system load"));

        // Xác nhận đã ghi nhận metric load cutoff
        verify(mockNotificationMetrics, atLeastOnce()).recordLoadCutoff();
    }
}
