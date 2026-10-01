package com.notification.metrics;

import com.notification.domain.Channel;
import com.notification.domain.Priority;
import com.notification.domain.NotificationStatus;
import com.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class NotificationMetrics {

    public static final String METRIC_TOTAL_COUNT = "notifications_total_count";
    public static final String METRIC_DELIVERED_COUNT = "notifications_delivered_count";
    public static final String METRIC_PENDING_COUNT = "notifications_pending_count";
    public static final String METRIC_NOTIFICATIONS_CREATED = "notifications_created_total";
    public static final String METRIC_NOTIFICATIONS_DELIVERED = "notifications_delivered_total";
    public static final String METRIC_NOTIFICATIONS_FAILED = "notifications_failed_total";
    public static final String METRIC_NOTIFICATIONS_DLQ = "notifications_dlq_total";
    public static final String METRIC_NOTIFICATIONS_DLQ_CURRENT = "notifications_dlq_current_total";
    public static final String METRIC_RATE_LIMIT_THROTTLED = "notifications_throttled_total";
    public static final String METRIC_PROVIDER_DURATION = "provider_delivery_duration_seconds";
    public static final String METRIC_E2E_LATENCY = "notifications_delivery_latency_seconds";
    public static final String METRIC_OUTBOX_PENDING = "notifications_outbox_pending_total";
    public static final String METRIC_CIRCUIT_BREAKER_STATE = "circuit_breaker_state";
    public static final String METRIC_NOTIFICATIONS_LOAD_SHED = "notifications_load_shed_total";
    public static final String METRIC_NOTIFICATIONS_LOAD_CUTOFF = "notifications_load_cutoff_total";
    public static final String METRIC_HARDWARE_THROTTLE_ACTIVE = "hardware_throttle_active";
    public static final String METRIC_HARDWARE_GUARD_STATE = "hardware_guard_state";

    @Inject
    MeterRegistry registry;

    @Inject
    jakarta.enterprise.inject.Instance<NotificationRepository> notificationRepositoryInstance;

    private final AtomicLong totalCreatedCount = new AtomicLong(0);
    private final AtomicLong totalDeliveredCount = new AtomicLong(0);
    private final AtomicLong totalPendingCount = new AtomicLong(0);
    private final AtomicLong dlqCurrentCount = new AtomicLong(0);
    private final AtomicLong outboxPendingCount = new AtomicLong(0);
    private final AtomicInteger hardwareThrottleActiveGauge = new AtomicInteger(0);
    private final AtomicInteger hardwareGuardStateGauge = new AtomicInteger(0);
    private final ConcurrentMap<String, AtomicInteger> circuitBreakerGauges = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (registry != null) {
            registry.gauge(METRIC_TOTAL_COUNT, totalCreatedCount);
            registry.gauge(METRIC_DELIVERED_COUNT, totalDeliveredCount);
            registry.gauge(METRIC_PENDING_COUNT, totalPendingCount);
            registry.gauge(METRIC_NOTIFICATIONS_DLQ_CURRENT, dlqCurrentCount);
            registry.gauge(METRIC_OUTBOX_PENDING, outboxPendingCount);
            registry.gauge(METRIC_HARDWARE_THROTTLE_ACTIVE, hardwareThrottleActiveGauge);
            registry.gauge(METRIC_HARDWARE_GUARD_STATE, hardwareGuardStateGauge);
        }
        syncFromDatabase();
    }

    public void syncFromDatabase() {
        try {
            if (notificationRepositoryInstance != null && notificationRepositoryInstance.isResolvable()) {
                NotificationRepository repo = notificationRepositoryInstance.get();
                long total = repo.count();
                long delivered = repo.countByStatus(NotificationStatus.DELIVERED);
                long dlq = repo.countByStatus(NotificationStatus.DEAD_LETTER);
                long pending = repo.countByStatus(NotificationStatus.CREATED)
                        + repo.countByStatus(NotificationStatus.QUEUED)
                        + repo.countByStatus(NotificationStatus.PROCESSING)
                        + repo.countByStatus(NotificationStatus.RETRYING);

                totalCreatedCount.set(total);
                totalDeliveredCount.set(delivered);
                dlqCurrentCount.set(dlq);
                totalPendingCount.set(pending);
                outboxPendingCount.set(pending);
            }
        } catch (Exception ignored) {
        }
    }

    public void setInitialDlqCount(long count) {
        dlqCurrentCount.set(count);
    }

    public void setOutboxPending(long count) {
        outboxPendingCount.set(count);
    }

    public MeterRegistry getRegistry() {
        return registry;
    }

    public void decrementDlq(long count) {
        dlqCurrentCount.updateAndGet(cur -> Math.max(0, cur - count));
    }

    /**
     * Record a newly accepted notification into Transactional Outbox.
     */
    public void recordCreated(Channel channel, Priority priority) {
        String ch = (channel != null) ? channel.name() : "UNKNOWN";
        String pr = (priority != null) ? priority.name() : "NORMAL";
        registry.counter(METRIC_NOTIFICATIONS_CREATED, "channel", ch, "priority", pr).increment();
        totalCreatedCount.incrementAndGet();
        totalPendingCount.incrementAndGet();
    }

    /**
     * Record a successfully delivered notification via a specific provider.
     */
    public void recordDelivered(Channel channel, String providerName) {
        String ch = (channel != null) ? channel.name() : "UNKNOWN";
        String pv = (providerName != null) ? providerName : "UNKNOWN";
        registry.counter(METRIC_NOTIFICATIONS_DELIVERED, "channel", ch, "provider", pv).increment();
        totalDeliveredCount.incrementAndGet();
        totalPendingCount.updateAndGet(cur -> Math.max(0, cur - 1));
    }

    /**
     * Record a failed delivery attempt.
     */
    public void recordFailed(Channel channel, String providerName, String reason) {
        String ch = (channel != null) ? channel.name() : "UNKNOWN";
        String pv = (providerName != null) ? providerName : "UNKNOWN";
        String r = (reason != null) ? reason : "UNKNOWN";
        registry.counter(METRIC_NOTIFICATIONS_FAILED, "channel", ch, "provider", pv, "reason", r).increment();
    }

    /**
     * Record a notification routed to Dead Letter Queue (DLQ).
     */
    public void recordDlq(Channel channel, String reason) {
        String ch = (channel != null) ? channel.name() : "UNKNOWN";
        String r = (reason != null) ? reason : "UNKNOWN";
        registry.counter(METRIC_NOTIFICATIONS_DLQ, "channel", ch, "reason", r).increment();
        dlqCurrentCount.incrementAndGet();
        totalPendingCount.updateAndGet(cur -> Math.max(0, cur - 1));
    }

    /**
     * Record when a notification is throttled by Token Bucket Rate Limiter.
     */
    public void recordThrottled(Channel channel) {
        String ch = (channel != null) ? channel.name() : "UNKNOWN";
        registry.counter(METRIC_RATE_LIMIT_THROTTLED, "channel", ch).increment();
    }

    /**
     * Start a timer sample to measure 3rd-party provider response latency.
     */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /**
     * Stop timer and record provider latency.
     */
    public void stopTimer(Timer.Sample sample, Channel channel, String providerName) {
        if (sample != null) {
            String ch = (channel != null) ? channel.name() : "UNKNOWN";
            String pv = (providerName != null) ? providerName : "UNKNOWN";
            Timer timer = registry.timer(METRIC_PROVIDER_DURATION, "channel", ch, "provider", pv);
            sample.stop(timer);
        }
    }

    /**
     * Record end-to-end delivery latency from creation to final delivery.
     */
    public void recordEndToEndLatency(Channel channel, Priority priority, java.time.Duration duration) {
        if (duration != null && !duration.isNegative()) {
            String ch = (channel != null) ? channel.name() : "UNKNOWN";
            String pr = (priority != null) ? priority.name() : "NORMAL";
            registry.timer(METRIC_E2E_LATENCY, "channel", ch, "priority", pr).record(duration);
        }
    }

    /**
     * Update live Circuit Breaker state gauge (0=CLOSED, 1=HALF_OPEN, 2=OPEN).
     */
    public void updateCircuitBreakerState(String providerKey, String state) {
        int stateValue = 0;
        if ("HALF_OPEN".equalsIgnoreCase(state)) {
            stateValue = 1;
        } else if ("OPEN".equalsIgnoreCase(state)) {
            stateValue = 2;
        }

        circuitBreakerGauges.computeIfAbsent(providerKey, k -> {
            AtomicInteger gaugeVal = new AtomicInteger(0);
            registry.gauge(METRIC_CIRCUIT_BREAKER_STATE, Tags.of("provider", k), gaugeVal);
            return gaugeVal;
        }).set(stateValue);
    }

    /**
     * Record rejected notifications due to hardware load shedding.
     */
    public void recordLoadShed(Priority priority) {
        if (registry != null) {
            String pr = (priority != null) ? priority.name() : "UNKNOWN";
            registry.counter(METRIC_NOTIFICATIONS_LOAD_SHED, "priority", pr).increment();
        }
    }

    /**
     * Record rejected notifications due to emergency hardware cutoff (HTTP 503).
     */
    public void recordLoadCutoff() {
        if (registry != null) {
            registry.counter(METRIC_NOTIFICATIONS_LOAD_CUTOFF).increment();
        }
    }

    /**
     * Update active hardware throttle state (0 = Normal, 1 = Throttled).
     */
    public void updateHardwareThrottleState(boolean active) {
        hardwareThrottleActiveGauge.set(active ? 1 : 0);
    }

    /**
     * Update active hardware guard state (0 = NORMAL, 1 = WARNING, 2 = THROTTLED, 3 = CRITICAL_CUTOFF).
     */
    public void updateHardwareGuardState(com.notification.guard.HardwareLoadState state) {
        int val = 0;
        if (state != null) {
            switch (state) {
                case NORMAL -> val = 0;
                case WARNING -> val = 1;
                case THROTTLED -> val = 2;
                case CRITICAL_CUTOFF -> val = 3;
            }
        }
        hardwareGuardStateGauge.set(val);
        hardwareThrottleActiveGauge.set(val >= 2 ? 1 : 0);
    }
}
