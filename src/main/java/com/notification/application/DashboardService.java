package com.notification.application;

import com.notification.api.dto.DashboardOverviewDto;
import com.notification.api.dto.DashboardProvidersDto;
import com.notification.api.dto.DashboardQueuesDto;
import com.notification.circuitbreaker.CircuitBreaker;
import com.notification.circuitbreaker.CircuitBreakerState;
import com.notification.domain.Channel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.Priority;
import com.notification.metrics.NotificationMetrics;
import com.notification.provider.MockNotificationProvider;
import com.notification.repository.NotificationRepository;
import com.notification.repository.UserDeviceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DashboardService {

    @Inject
    NotificationRepository notificationRepository;

    @Inject
    UserDeviceRepository userDeviceRepository;

    @Inject
    NotificationMetrics notificationMetrics;

    @Inject
    CircuitBreaker circuitBreaker;

    @Inject
    MockNotificationProvider mockNotificationProvider;

    @Inject
    com.notification.guard.SystemLoadGuard systemLoadGuard;

    @ConfigProperty(name = "ratelimit.default.capacity", defaultValue = "100")
    long defaultCapacity;

    @ConfigProperty(name = "ratelimit.default.refill-rate", defaultValue = "20.0")
    double defaultRefillRate;

    public DashboardOverviewDto getOverview() {
        DashboardOverviewDto dto = new DashboardOverviewDto();

        long delivered = notificationRepository.countByStatus(NotificationStatus.DELIVERED);
        long failed = notificationRepository.countByStatus(NotificationStatus.FAILED);
        long pending = notificationRepository.countByStatus(NotificationStatus.CREATED)
                + notificationRepository.countByStatus(NotificationStatus.QUEUED)
                + notificationRepository.countByStatus(NotificationStatus.PROCESSING);
        long retrying = notificationRepository.countByStatus(NotificationStatus.RETRYING);
        long dlq = notificationRepository.countByStatus(NotificationStatus.DEAD_LETTER);
        long total = notificationRepository.count();

        dto.setTotalCreated(total);
        dto.setTotalDelivered(delivered);
        dto.setTotalFailed(failed);
        dto.setTotalPending(pending);
        dto.setTotalRetrying(retrying);
        dto.setTotalDlq(dlq);

        double successRate = total > 0 ? ((double) delivered / total) * 100.0 : 100.0;
        dto.setSuccessRatePercent(Math.round(successRate * 10.0) / 10.0);

        MeterRegistry registry = notificationMetrics.getRegistry();
        double avgLat = 0.0;
        double p95Lat = 0.0;
        long throttled = 0;

        if (registry != null) {
            Timer timer = registry.find(NotificationMetrics.METRIC_E2E_LATENCY).timer();
            if (timer != null) {
                avgLat = timer.mean(TimeUnit.MILLISECONDS);
                if (Double.isNaN(avgLat) || Double.isInfinite(avgLat)) avgLat = 0.0;
            }

            Timer providerTimer = registry.find(NotificationMetrics.METRIC_PROVIDER_DURATION).timer();
            if (providerTimer != null && avgLat == 0.0) {
                avgLat = providerTimer.mean(TimeUnit.MILLISECONDS);
                if (Double.isNaN(avgLat) || Double.isInfinite(avgLat)) avgLat = 0.0;
            }

            Counter throttledCounter = registry.find(NotificationMetrics.METRIC_RATE_LIMIT_THROTTLED).counter();
            if (throttledCounter != null) {
                throttled = (long) throttledCounter.count();
            }
        }

        dto.setAvgLatencyMs(Math.round(avgLat * 10.0) / 10.0);
        dto.setP95LatencyMs(Math.round(p95Lat * 10.0) / 10.0);
        dto.setThrottledCount(throttled);

        long activeDevices = userDeviceRepository.count("isActive", true);
        dto.setActiveDevicesCount(activeDevices);

        if (systemLoadGuard != null) {
            dto.setHardwareState(systemLoadGuard.getCurrentState().name());
            dto.setCpuUsagePercent(systemLoadGuard.getCurrentCpuUsage());
            dto.setRamUsagePercent(systemLoadGuard.getCurrentRamUsage());
            dto.setHardwareThrottleActive(systemLoadGuard.isThrottled());
            dto.setSimulationActive(systemLoadGuard.isSimulationActive());
        } else {
            dto.setHardwareState("NORMAL");
            dto.setCpuUsagePercent(0.0);
            dto.setRamUsagePercent(0.0);
            dto.setHardwareThrottleActive(false);
            dto.setSimulationActive(false);
        }

        return dto;
    }

    public void setHardwareSimulation(boolean enabled, double cpu, double ram) {
        if (systemLoadGuard != null) {
            if (enabled) {
                systemLoadGuard.enableSimulation(cpu, ram);
            } else {
                systemLoadGuard.disableSimulation();
            }
        }
    }

    public void simulateHealthy() {
        if (systemLoadGuard != null) {
            systemLoadGuard.simulateHealthy();
        }
    }

    public DashboardQueuesDto getQueues() {
        // Tối ưu hóa: Dùng 2 truy vấn tổng hợp GROUP BY thay vì 28 câu COUNT(*) đơn lẻ
        List<Object[]> priorityRows = notificationRepository.countGroupByPriorityAndStatus();
        java.util.Map<String, Long> priorityStatusMap = new java.util.HashMap<>();
        for (Object[] row : priorityRows) {
            Priority p = (Priority) row[0];
            NotificationStatus s = (NotificationStatus) row[1];
            Long cnt = ((Number) row[2]).longValue();
            if (p != null && s != null) {
                priorityStatusMap.put(p.name() + ":" + s.name(), cnt);
            }
        }

        List<DashboardQueuesDto.PriorityQueueStat> priorityStats = new ArrayList<>();
        for (Priority p : Priority.values()) {
            long pCreated = priorityStatusMap.getOrDefault(p.name() + ":" + NotificationStatus.CREATED.name(), 0L);
            long pQueued = priorityStatusMap.getOrDefault(p.name() + ":" + NotificationStatus.QUEUED.name(), 0L);
            long pProcessing = priorityStatusMap.getOrDefault(p.name() + ":" + NotificationStatus.PROCESSING.name(), 0L);
            long pRetrying = priorityStatusMap.getOrDefault(p.name() + ":" + NotificationStatus.RETRYING.name(), 0L);
            long pPending = pCreated + pQueued + pProcessing + pRetrying;
            long pDelivered = priorityStatusMap.getOrDefault(p.name() + ":" + NotificationStatus.DELIVERED.name(), 0L);
            long pFailed = priorityStatusMap.getOrDefault(p.name() + ":" + NotificationStatus.FAILED.name(), 0L);
            long pDlq = priorityStatusMap.getOrDefault(p.name() + ":" + NotificationStatus.DEAD_LETTER.name(), 0L);
            long pTotal = pPending + pDelivered + pFailed + pDlq;

            priorityStats.add(new DashboardQueuesDto.PriorityQueueStat(
                    p.name(), pTotal, pPending, pDelivered, pFailed, pDlq
            ));
        }

        List<Object[]> channelRows = notificationRepository.countGroupByChannelAndStatus();
        java.util.Map<String, Long> channelStatusMap = new java.util.HashMap<>();
        for (Object[] row : channelRows) {
            Channel ch = (Channel) row[0];
            NotificationStatus s = (NotificationStatus) row[1];
            Long cnt = ((Number) row[2]).longValue();
            if (ch != null && s != null) {
                channelStatusMap.put(ch.name() + ":" + s.name(), cnt);
            }
        }

        List<DashboardQueuesDto.ChannelQueueStat> channelStats = new ArrayList<>();
        for (Channel ch : Channel.values()) {
            long chDelivered = channelStatusMap.getOrDefault(ch.name() + ":" + NotificationStatus.DELIVERED.name(), 0L);
            long chFailed = channelStatusMap.getOrDefault(ch.name() + ":" + NotificationStatus.FAILED.name(), 0L);
            long chDlq = channelStatusMap.getOrDefault(ch.name() + ":" + NotificationStatus.DEAD_LETTER.name(), 0L);
            long chCreated = channelStatusMap.getOrDefault(ch.name() + ":" + NotificationStatus.CREATED.name(), 0L);
            long chQueued = channelStatusMap.getOrDefault(ch.name() + ":" + NotificationStatus.QUEUED.name(), 0L);
            long chProcessing = channelStatusMap.getOrDefault(ch.name() + ":" + NotificationStatus.PROCESSING.name(), 0L);
            long chRetrying = channelStatusMap.getOrDefault(ch.name() + ":" + NotificationStatus.RETRYING.name(), 0L);
            long chPending = chCreated + chQueued + chProcessing + chRetrying;
            long chTotal = chDelivered + chFailed + chDlq + chPending;

            channelStats.add(new DashboardQueuesDto.ChannelQueueStat(
                    ch.name(), chTotal, chDelivered, chFailed, chDlq
            ));
        }

        return new DashboardQueuesDto(priorityStats, channelStats);
    }

    public DashboardProvidersDto getProviders() {
        List<DashboardProvidersDto.ProviderHealthDto> providers = new ArrayList<>();

        // Firebase FCM Push
        String fcmCbKey = "cb:PUSH:FirebasePushProvider";
        CircuitBreakerState fcmCbState = (circuitBreaker != null) ? circuitBreaker.getState(fcmCbKey) : CircuitBreakerState.CLOSED;
        if (fcmCbState == null) fcmCbState = CircuitBreakerState.CLOSED;

        providers.add(new DashboardProvidersDto.ProviderHealthDto(
                "Google Firebase Cloud Messaging (FCM)",
                "PUSH",
                fcmCbState.name(),
                "LIVE_HTTP_V1",
                defaultCapacity,
                defaultRefillRate,
                0.0
        ));

        // Mock Email Provider
        String emailCbKey = "cb:EMAIL:MockNotificationProvider";
        CircuitBreakerState emailCbState = (circuitBreaker != null) ? circuitBreaker.getState(emailCbKey) : CircuitBreakerState.CLOSED;
        if (emailCbState == null) emailCbState = CircuitBreakerState.CLOSED;
        String emailSimMode = (mockNotificationProvider != null) ? mockNotificationProvider.getEffectiveMode().name() : "SUCCESS";

        providers.add(new DashboardProvidersDto.ProviderHealthDto(
                "Email Downstream Gateway",
                "EMAIL",
                emailCbState.name(),
                emailSimMode,
                defaultCapacity,
                defaultRefillRate,
                0.0
        ));

        // Mock SMS Provider
        String smsCbKey = "cb:SMS:MockNotificationProvider";
        CircuitBreakerState smsCbState = (circuitBreaker != null) ? circuitBreaker.getState(smsCbKey) : CircuitBreakerState.CLOSED;
        if (smsCbState == null) smsCbState = CircuitBreakerState.CLOSED;

        providers.add(new DashboardProvidersDto.ProviderHealthDto(
                "SMS Downstream Gateway (Telco Mock)",
                "SMS",
                smsCbState.name(),
                emailSimMode,
                defaultCapacity,
                defaultRefillRate,
                0.0
        ));

        return new DashboardProvidersDto(providers);
    }
}
