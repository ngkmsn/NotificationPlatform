package com.notification.guard;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.application.NotificationService;
import com.notification.domain.Priority;
import com.notification.metrics.NotificationMetrics;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class SystemLoadGuard {

    private static final Logger LOG = Logger.getLogger(SystemLoadGuard.class);
    public static final String REDIS_ALERT_DEBOUNCE_KEY = "alert:cooldown:hardware_throttle";
    public static final String REDIS_CUTOFF_DEBOUNCE_KEY = "alert:cooldown:hardware_cutoff";

    @ConfigProperty(name = "hardware.guard.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "hardware.guard.warning-threshold-percent", defaultValue = "75.0")
    double warningThreshold;

    @ConfigProperty(name = "hardware.guard.throttle-threshold-percent", defaultValue = "85.0")
    double throttleThreshold;

    @ConfigProperty(name = "hardware.guard.cutoff-threshold-percent", defaultValue = "95.0")
    double cutoffThreshold;

    @ConfigProperty(name = "hardware.guard.cpu-threshold-percent", defaultValue = "85.0")
    double cpuThreshold;

    @ConfigProperty(name = "hardware.guard.ram-threshold-percent", defaultValue = "85.0")
    double ramThreshold;

    @ConfigProperty(name = "hardware.guard.ram-cutoff-threshold-percent", defaultValue = "95.0")
    double ramCutoffThreshold;

    @ConfigProperty(name = "hardware.guard.recovery-threshold-percent", defaultValue = "70.0")
    double recoveryThreshold;

    @ConfigProperty(name = "hardware.guard.debounce-minutes", defaultValue = "10")
    int debounceMinutes;

    @ConfigProperty(name = "hardware.guard.admin-recipient", defaultValue = "admin")
    String adminRecipient;

    @ConfigProperty(name = "hardware.guard.simulation-mode", defaultValue = "false")
    boolean simulationModeConfig;

    @Inject
    Instance<NotificationService> notificationServiceInstance;

    @Inject
    NotificationMetrics notificationMetrics;

    @Inject
    Instance<RedisDataSource> redisDataSourceInstance;

    private ValueCommands<String, String> valueCommands;
    private KeyCommands<String> keyCommands;

    private volatile HardwareLoadState currentState = HardwareLoadState.NORMAL;
    private volatile double currentCpuUsage = 0.0;
    private volatile double currentRamUsage = 0.0;
    private volatile int consecutiveRecoveryCount = 0;
    private volatile int consecutiveCutoffCount = 0;

    // Simulation controls for manual testing / operations demo
    private final AtomicBoolean simulationActive = new AtomicBoolean(false);
    private volatile double simulatedCpu = 0.0;
    private volatile double simulatedRam = 0.0;

    // In-memory fallback debounce in case Redis is offline
    private volatile long lastAlertTimestampMs = 0;
    private volatile long lastCutoffAlertTimestampMs = 0;

    @PostConstruct
    public void init() {
        if (redisDataSourceInstance != null && redisDataSourceInstance.isResolvable()) {
            try {
                RedisDataSource rds = redisDataSourceInstance.get();
                this.valueCommands = rds.value(String.class);
                this.keyCommands = rds.key();
            } catch (Exception e) {
                LOG.warnf("Could not initialize Redis client for SystemLoadGuard: %s. Using memory debounce fallback.", e.getMessage());
            }
        }
        if (simulationModeConfig) {
            enableSimulation(88.0, 75.0);
        }
    }

    @Scheduled(every = "${hardware.guard.check-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void evaluateHardwareHealth() {
        if (!enabled) {
            return;
        }

        readHardwareMetrics();

        double effectiveThrottleCpu = Math.min(throttleThreshold, cpuThreshold);
        double effectiveCutoffCpu = cutoffThreshold;
        double effectiveThrottleRam = ramThreshold;
        double effectiveCutoffRam = ramCutoffThreshold;

        boolean isCutoffLoad = (currentCpuUsage >= effectiveCutoffCpu) || (currentRamUsage >= effectiveCutoffRam);
        boolean isThrottleLoad = (currentCpuUsage >= effectiveThrottleCpu) || (currentRamUsage >= effectiveThrottleRam);
        boolean isWarningLoad = (currentCpuUsage >= warningThreshold) || (currentRamUsage >= warningThreshold);
        boolean isSafeLoad = (currentCpuUsage < recoveryThreshold) && (currentRamUsage < recoveryThreshold);

        HardwareLoadState previousState = currentState;

        if (isCutoffLoad) {
            consecutiveRecoveryCount = 0;
            if (!simulationActive.get()) {
                consecutiveCutoffCount++;
            }
            if (simulationActive.get() || consecutiveCutoffCount >= 2 || previousState == HardwareLoadState.CRITICAL_CUTOFF) {
                currentState = HardwareLoadState.CRITICAL_CUTOFF;
                if (notificationMetrics != null) {
                    notificationMetrics.updateHardwareGuardState(currentState);
                }

                if (previousState != HardwareLoadState.CRITICAL_CUTOFF || simulationActive.get()) {
                    LOG.errorf("🚨 [EMERGENCY HARD CUTOFF] System entered CRITICAL_CUTOFF state! CPU: %.1f%% (cutoff %.1f%%), RAM: %.1f%% (cutoff %.1f%%). 100%% traffic rejected & Outbox paused!",
                            currentCpuUsage, effectiveCutoffCpu, currentRamUsage, effectiveCutoffRam);
                    triggerEmergencyCutoffAlertIfAllowed();
                }
            } else {
                // If it is the first high spike, enter THROTTLED to shed low priority without hard-pausing everything
                currentState = HardwareLoadState.THROTTLED;
                if (notificationMetrics != null) {
                    notificationMetrics.updateHardwareGuardState(currentState);
                }
                if (previousState != HardwareLoadState.THROTTLED) {
                    LOG.warnf("🚨 [HARDWARE OVERLOAD] Load spike detected (CPU: %.1f%%, RAM: %.1f%%). Entering THROTTLED state.",
                            currentCpuUsage, currentRamUsage);
                }
            }
        } else {
            consecutiveCutoffCount = 0;
            if (isThrottleLoad) {
                consecutiveRecoveryCount = 0;
                currentState = HardwareLoadState.THROTTLED;
                if (notificationMetrics != null) {
                    notificationMetrics.updateHardwareGuardState(currentState);
                }

                if (previousState != HardwareLoadState.THROTTLED && previousState != HardwareLoadState.CRITICAL_CUTOFF) {
                    LOG.warnf("🚨 [HARDWARE OVERLOAD] System entered THROTTLED state! CPU: %.1f%% (threshold %.1f%%), RAM: %.1f%% (threshold %.1f%%). Shedding LOW/NORMAL priorities.",
                            currentCpuUsage, effectiveThrottleCpu, currentRamUsage, effectiveThrottleRam);
                    triggerAdminAlertIfAllowed();
                } else if (previousState == HardwareLoadState.CRITICAL_CUTOFF) {
                    LOG.infof("System stepped down from CRITICAL_CUTOFF to THROTTLED. CPU: %.1f%%, RAM: %.1f%%.", currentCpuUsage, currentRamUsage);
                }
            } else if (isSafeLoad) {
                if (previousState == HardwareLoadState.THROTTLED || previousState == HardwareLoadState.CRITICAL_CUTOFF) {
                    consecutiveRecoveryCount++;
                    LOG.infof("System load in safe zone (CPU: %.1f%%, RAM: %.1f%%). Recovery check: %d/2",
                            currentCpuUsage, currentRamUsage, consecutiveRecoveryCount);

                    if (consecutiveRecoveryCount >= 2) {
                        currentState = HardwareLoadState.NORMAL;
                        if (notificationMetrics != null) {
                            notificationMetrics.updateHardwareGuardState(currentState);
                        }
                        LOG.infof("✅ [HARDWARE RECOVERED] System transitioned back to NORMAL state! Resuming full traffic.");
                        triggerRecoveryAlert();
                        clearDebounceLock();
                    }
                } else {
                    currentState = HardwareLoadState.NORMAL;
                    if (notificationMetrics != null) {
                        notificationMetrics.updateHardwareGuardState(currentState);
                    }
                }
            } else {
                // Zone between recoveryThreshold (70%) and throttleThreshold (85%)
                consecutiveRecoveryCount = 0;
                if (previousState == HardwareLoadState.THROTTLED || previousState == HardwareLoadState.CRITICAL_CUTOFF) {
                    // Keep Throttled until it cools down below recoveryThreshold (70%) to prevent flapping
                    currentState = HardwareLoadState.THROTTLED;
                    if (notificationMetrics != null) {
                        notificationMetrics.updateHardwareGuardState(currentState);
                    }
                } else if (isWarningLoad) {
                    currentState = HardwareLoadState.WARNING;
                    if (notificationMetrics != null) {
                        notificationMetrics.updateHardwareGuardState(currentState);
                    }
                } else {
                    currentState = HardwareLoadState.NORMAL;
                    if (notificationMetrics != null) {
                        notificationMetrics.updateHardwareGuardState(currentState);
                    }
                }
            }
        }
    }

    /**
     * Determines whether an incoming notification should be throttled/rejected
     * based on current hardware load and priority.
     */
    public boolean shouldThrottlePriority(Priority priority) {
        if (!enabled) {
            return false;
        }
        if (currentState == HardwareLoadState.CRITICAL_CUTOFF) {
            // Hard cutoff rejects all priorities
            return true;
        }
        if (currentState == HardwareLoadState.THROTTLED) {
            // When throttled (85%): Shed LOW and NORMAL priorities, protect CRITICAL and HIGH
            return priority == Priority.LOW || priority == Priority.NORMAL;
        }
        return false;
    }

    public boolean isCutoff() {
        return enabled && currentState == HardwareLoadState.CRITICAL_CUTOFF;
    }

    public boolean isThrottled() {
        return enabled && (currentState == HardwareLoadState.THROTTLED || currentState == HardwareLoadState.CRITICAL_CUTOFF);
    }

    public boolean isWarning() {
        return currentState == HardwareLoadState.WARNING;
    }

    public boolean isNormal() {
        return currentState == HardwareLoadState.NORMAL;
    }

    private void readHardwareMetrics() {
        if (simulationActive.get()) {
            this.currentCpuUsage = simulatedCpu;
            this.currentRamUsage = simulatedRam;
            return;
        }

        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            double cpu = -1.0;

            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                // In containerized microservices, prioritize process CPU load of this specific JVM.
                // sunBean.getCpuLoad() measures the host VM (including Docker daemon and all other containers).
                double procCpu = sunBean.getProcessCpuLoad();
                if (procCpu >= 0.0) {
                    cpu = procCpu * 100.0;
                } else {
                    double sysCpu = sunBean.getCpuLoad();
                    if (sysCpu >= 0.0) {
                        cpu = sysCpu * 100.0;
                    }
                }
            }

            // Fallback if OS bean didn't provide CPU yet (e.g. first sampling window right after startup)
            if (cpu < 0.0) {
                cpu = 0.0;
            }
            this.currentCpuUsage = Math.round(cpu * 10.0) / 10.0;

            // Memory usage calculation: JVM Heap pressure
            Runtime rt = Runtime.getRuntime();
            long maxHeap = rt.maxMemory();
            long totalHeap = rt.totalMemory();
            long freeHeap = rt.freeMemory();
            long usedHeap = totalHeap - freeHeap;
            double jvmRamPercent = (maxHeap > 0) ? ((double) usedHeap / maxHeap) * 100.0 : 0.0;

            this.currentRamUsage = Math.round(jvmRamPercent * 10.0) / 10.0;
        } catch (Exception e) {
            LOG.warnf("Could not read hardware metrics: %s", e.getMessage());
        }
    }

    private void triggerEmergencyCutoffAlertIfAllowed() {
        boolean canSend = acquireCutoffDebounceLock();
        if (!canSend) {
            LOG.infof("Emergency cutoff alert suppressed by debounce cooldown (%d minutes)", debounceMinutes);
            return;
        }

        try {
            if (notificationServiceInstance != null && notificationServiceInstance.isResolvable()) {
                NotificationService notificationService = notificationServiceInstance.get();

                CreateNotificationRequest req = new CreateNotificationRequest();
                req.setRecipient(adminRecipient);
                req.setChannel("PUSH");
                req.setPriority("CRITICAL");
                req.setSubject(String.format("🚨 [NGẮT KHẨN CẤP] Máy chủ đạt %.0f%% CPU (Ngưỡng 95%%)", currentCpuUsage));
                req.setContent(String.format("Hệ thống đã tự động kích hoạt Emergency Hard Cutoff: CPU %.1f%%, RAM %.1f%%. Tạm dừng 100%% traffic và đóng băng Outbox Worker để ngăn ngừa OOM/Crash.",
                        currentCpuUsage, currentRamUsage));

                notificationService.createNotification(req);
                LOG.infof("🚀 Admin emergency cutoff notification dispatched (Recipient: %s)", adminRecipient);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send admin emergency cutoff notification: %s", e.getMessage());
        }
    }

    private void triggerAdminAlertIfAllowed() {
        boolean canSend = acquireThrottleDebounceLock();
        if (!canSend) {
            LOG.infof("Admin alert suppressed by debounce cooldown (%d minutes)", debounceMinutes);
            return;
        }

        try {
            if (notificationServiceInstance != null && notificationServiceInstance.isResolvable()) {
                NotificationService notificationService = notificationServiceInstance.get();

                CreateNotificationRequest req = new CreateNotificationRequest();
                req.setRecipient(adminRecipient);
                req.setChannel("PUSH");
                req.setPriority("CRITICAL");
                req.setSubject(String.format("🚨 [CẢNH BÁO QUÁ TẢI] Máy chủ đạt %.0f%% CPU (Ngưỡng 85%%)", currentCpuUsage));
                req.setContent(String.format("Hệ thống tự động kích hoạt Auto-Throttle: CPU %.1f%%, RAM %.1f%%. Các tin LOW/NORMAL tạm thời bị từ chối để hạ nhiệt máy chủ.",
                        currentCpuUsage, currentRamUsage));

                notificationService.createNotification(req);
                LOG.infof("🚀 Admin push notification dispatched for hardware throttle event (Recipient: %s)", adminRecipient);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send admin hardware alert notification: %s", e.getMessage());
        }
    }

    private void triggerRecoveryAlert() {
        try {
            if (notificationServiceInstance != null && notificationServiceInstance.isResolvable()) {
                NotificationService notificationService = notificationServiceInstance.get();

                CreateNotificationRequest req = new CreateNotificationRequest();
                req.setRecipient(adminRecipient);
                req.setChannel("PUSH");
                req.setPriority("CRITICAL");
                req.setSubject(String.format("✅ [HỆ THỐNG PHỤC HỒI] Máy chủ đã hạ nhiệt (%.0f%% CPU)", currentCpuUsage));
                req.setContent(String.format("Mức tải phần cứng đã trở về vùng an toàn: CPU %.1f%%, RAM %.1f%%. Đã khôi phục tiếp nhận 100%% lưu lượng bình thường.",
                        currentCpuUsage, currentRamUsage));

                notificationService.createNotification(req);
                LOG.infof("🚀 Admin recovery notification dispatched (Recipient: %s)", adminRecipient);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send admin recovery notification: %s", e.getMessage());
        }
    }

    private boolean acquireThrottleDebounceLock() {
        long now = System.currentTimeMillis();
        long debounceDurationMs = (long) debounceMinutes * 60 * 1000;

        if (valueCommands != null && keyCommands != null) {
            try {
                boolean acquired = valueCommands.setnx(REDIS_ALERT_DEBOUNCE_KEY, String.valueOf(now));
                if (acquired) {
                    keyCommands.expire(REDIS_ALERT_DEBOUNCE_KEY, (long) debounceMinutes * 60);
                    lastAlertTimestampMs = now;
                    return true;
                }
                return false;
            } catch (Exception e) {
                LOG.warnf("Redis debounce check failed: %s. Using memory fallback.", e.getMessage());
            }
        }

        // Memory debounce fallback
        if (now - lastAlertTimestampMs > debounceDurationMs) {
            lastAlertTimestampMs = now;
            return true;
        }
        return false;
    }

    private boolean acquireCutoffDebounceLock() {
        long now = System.currentTimeMillis();
        long debounceDurationMs = (long) debounceMinutes * 60 * 1000;

        if (valueCommands != null && keyCommands != null) {
            try {
                boolean acquired = valueCommands.setnx(REDIS_CUTOFF_DEBOUNCE_KEY, String.valueOf(now));
                if (acquired) {
                    keyCommands.expire(REDIS_CUTOFF_DEBOUNCE_KEY, (long) debounceMinutes * 60);
                    lastCutoffAlertTimestampMs = now;
                    return true;
                }
                return false;
            } catch (Exception e) {
                LOG.warnf("Redis cutoff debounce check failed: %s. Using memory fallback.", e.getMessage());
            }
        }

        // Memory debounce fallback
        if (now - lastCutoffAlertTimestampMs > debounceDurationMs) {
            lastCutoffAlertTimestampMs = now;
            return true;
        }
        return false;
    }

    private void clearDebounceLock() {
        lastAlertTimestampMs = 0;
        lastCutoffAlertTimestampMs = 0;
        if (keyCommands != null) {
            try {
                keyCommands.del(REDIS_ALERT_DEBOUNCE_KEY, REDIS_CUTOFF_DEBOUNCE_KEY);
            } catch (Exception e) {
                LOG.debugf("Failed to clear Redis debounce lock: %s", e.getMessage());
            }
        }
    }

    // Simulation & Management APIs
    public void simulateHealthy() {
        HardwareLoadState previousState = this.currentState;
        this.enabled = true;
        this.simulationActive.set(true);
        this.simulatedCpu = 35.0;
        this.simulatedRam = 40.0;
        this.currentCpuUsage = 35.0;
        this.currentRamUsage = 40.0;
        this.currentState = HardwareLoadState.NORMAL;
        this.consecutiveRecoveryCount = 0;
        clearDebounceLock();
        if (notificationMetrics != null) {
            notificationMetrics.updateHardwareGuardState(HardwareLoadState.NORMAL);
        }
        LOG.infof("Hardware simulation forced to HEALTHY/NORMAL: CPU=35.0%%, RAM=40.0%%");
        if (previousState == HardwareLoadState.CRITICAL_CUTOFF || previousState == HardwareLoadState.THROTTLED) {
            triggerRecoveryAlert();
        }
    }

    public void enableSimulation(double simCpu, double simRam) {
        this.enabled = true;
        this.simulationActive.set(true);
        this.simulatedCpu = simCpu;
        this.simulatedRam = simRam;
        this.currentState = HardwareLoadState.NORMAL;
        clearDebounceLock();
        evaluateHardwareHealth();
        LOG.infof("Hardware simulation enabled: CPU=%.1f%%, RAM=%.1f%%", simCpu, simRam);
    }

    public void disableSimulation() {
        this.simulationActive.set(false);
        this.consecutiveRecoveryCount = 0;
        clearDebounceLock();
        evaluateHardwareHealth();
        LOG.infof("Hardware simulation disabled. Returned to real hardware readings.");
    }

    public boolean isSimulationActive() {
        return simulationActive.get();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public HardwareLoadState getCurrentState() {
        return currentState;
    }

    public double getCurrentCpuUsage() {
        return currentCpuUsage;
    }

    public double getCurrentRamUsage() {
        return currentRamUsage;
    }

    public void resetForTest() {
        this.enabled = true;
        this.simulationActive.set(false);
        this.currentState = HardwareLoadState.NORMAL;
        this.consecutiveRecoveryCount = 0;
        this.consecutiveCutoffCount = 0;
        this.currentCpuUsage = 0.0;
        this.currentRamUsage = 0.0;
        this.lastAlertTimestampMs = 0;
        this.lastCutoffAlertTimestampMs = 0;
        if (notificationMetrics != null) {
            notificationMetrics.updateHardwareGuardState(HardwareLoadState.NORMAL);
        }
    }
}
