package com.notification.api.dto;

public class DashboardOverviewDto {

    private long totalCreated;
    private long totalDelivered;
    private long totalFailed;
    private long totalPending;
    private long totalRetrying;
    private long totalDlq;
    private double successRatePercent;
    private double avgLatencyMs;
    private double p95LatencyMs;
    private long throttledCount;
    private long activeDevicesCount;
    private String hardwareState;
    private double cpuUsagePercent;
    private double ramUsagePercent;
    private boolean hardwareThrottleActive;
    private boolean simulationActive;

    public DashboardOverviewDto() {
    }

    public long getTotalCreated() {
        return totalCreated;
    }

    public void setTotalCreated(long totalCreated) {
        this.totalCreated = totalCreated;
    }

    public long getTotalDelivered() {
        return totalDelivered;
    }

    public void setTotalDelivered(long totalDelivered) {
        this.totalDelivered = totalDelivered;
    }

    public long getTotalFailed() {
        return totalFailed;
    }

    public void setTotalFailed(long totalFailed) {
        this.totalFailed = totalFailed;
    }

    public long getTotalPending() {
        return totalPending;
    }

    public void setTotalPending(long totalPending) {
        this.totalPending = totalPending;
    }

    public long getTotalRetrying() {
        return totalRetrying;
    }

    public void setTotalRetrying(long totalRetrying) {
        this.totalRetrying = totalRetrying;
    }

    public long getTotalDlq() {
        return totalDlq;
    }

    public void setTotalDlq(long totalDlq) {
        this.totalDlq = totalDlq;
    }

    public double getSuccessRatePercent() {
        return successRatePercent;
    }

    public void setSuccessRatePercent(double successRatePercent) {
        this.successRatePercent = successRatePercent;
    }

    public double getAvgLatencyMs() {
        return avgLatencyMs;
    }

    public void setAvgLatencyMs(double avgLatencyMs) {
        this.avgLatencyMs = avgLatencyMs;
    }

    public double getP95LatencyMs() {
        return p95LatencyMs;
    }

    public void setP95LatencyMs(double p95LatencyMs) {
        this.p95LatencyMs = p95LatencyMs;
    }

    public long getThrottledCount() {
        return throttledCount;
    }

    public void setThrottledCount(long throttledCount) {
        this.throttledCount = throttledCount;
    }

    public long getActiveDevicesCount() {
        return activeDevicesCount;
    }

    public void setActiveDevicesCount(long activeDevicesCount) {
        this.activeDevicesCount = activeDevicesCount;
    }

    public String getHardwareState() {
        return hardwareState;
    }

    public void setHardwareState(String hardwareState) {
        this.hardwareState = hardwareState;
    }

    public double getCpuUsagePercent() {
        return cpuUsagePercent;
    }

    public void setCpuUsagePercent(double cpuUsagePercent) {
        this.cpuUsagePercent = cpuUsagePercent;
    }

    public double getRamUsagePercent() {
        return ramUsagePercent;
    }

    public void setRamUsagePercent(double ramUsagePercent) {
        this.ramUsagePercent = ramUsagePercent;
    }

    public boolean isHardwareThrottleActive() {
        return hardwareThrottleActive;
    }

    public void setHardwareThrottleActive(boolean hardwareThrottleActive) {
        this.hardwareThrottleActive = hardwareThrottleActive;
    }

    public boolean isSimulationActive() {
        return simulationActive;
    }

    public void setSimulationActive(boolean simulationActive) {
        this.simulationActive = simulationActive;
    }
}
