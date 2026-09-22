package com.notification.metrics;

import com.notification.domain.Channel;
import com.notification.domain.Priority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class NotificationMetrics {

    public static final String METRIC_NOTIFICATIONS_CREATED = "notifications_created_total";
    public static final String METRIC_NOTIFICATIONS_DELIVERED = "notifications_delivered_total";
    public static final String METRIC_NOTIFICATIONS_FAILED = "notifications_failed_total";
    public static final String METRIC_NOTIFICATIONS_DLQ = "notifications_dlq_total";
    public static final String METRIC_RATE_LIMIT_THROTTLED = "notifications_throttled_total";
    public static final String METRIC_PROVIDER_DURATION = "provider_delivery_duration_seconds";
    public static final String METRIC_CIRCUIT_BREAKER_STATE = "circuit_breaker_state";

    @Inject
    MeterRegistry registry;

    private final ConcurrentMap<String, AtomicInteger> circuitBreakerGauges = new ConcurrentHashMap<>();

    /**
     * Record a newly accepted notification into Transactional Outbox.
     */
    public void recordCreated(Channel channel, Priority priority) {
        String ch = (channel != null) ? channel.name() : "UNKNOWN";
        String pr = (priority != null) ? priority.name() : "NORMAL";
        registry.counter(METRIC_NOTIFICATIONS_CREATED, "channel", ch, "priority", pr).increment();
    }

    /**
     * Record a successfully delivered notification via a specific provider.
     */
    public void recordDelivered(Channel channel, String providerName) {
        String ch = (channel != null) ? channel.name() : "UNKNOWN";
        String pv = (providerName != null) ? providerName : "UNKNOWN";
        registry.counter(METRIC_NOTIFICATIONS_DELIVERED, "channel", ch, "provider", pv).increment();
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
}
