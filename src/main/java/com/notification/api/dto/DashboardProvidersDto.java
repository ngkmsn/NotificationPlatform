package com.notification.api.dto;

import java.util.List;

public class DashboardProvidersDto {

    public static class ProviderHealthDto {
        private String name;
        private String channel;
        private String circuitBreakerState;
        private String simulationMode;
        private long rateLimitCapacity;
        private double rateLimitRefillRate;
        private double avgResponseDurationMs;

        public ProviderHealthDto() {}

        public ProviderHealthDto(String name, String channel, String circuitBreakerState, String simulationMode, long rateLimitCapacity, double rateLimitRefillRate, double avgResponseDurationMs) {
            this.name = name;
            this.channel = channel;
            this.circuitBreakerState = circuitBreakerState;
            this.simulationMode = simulationMode;
            this.rateLimitCapacity = rateLimitCapacity;
            this.rateLimitRefillRate = rateLimitRefillRate;
            this.avgResponseDurationMs = avgResponseDurationMs;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }

        public String getCircuitBreakerState() { return circuitBreakerState; }
        public void setCircuitBreakerState(String circuitBreakerState) { this.circuitBreakerState = circuitBreakerState; }

        public String getSimulationMode() { return simulationMode; }
        public void setSimulationMode(String simulationMode) { this.simulationMode = simulationMode; }

        public long getRateLimitCapacity() { return rateLimitCapacity; }
        public void setRateLimitCapacity(long rateLimitCapacity) { this.rateLimitCapacity = rateLimitCapacity; }

        public double getRateLimitRefillRate() { return rateLimitRefillRate; }
        public void setRateLimitRefillRate(double rateLimitRefillRate) { this.rateLimitRefillRate = rateLimitRefillRate; }

        public double getAvgResponseDurationMs() { return avgResponseDurationMs; }
        public void setAvgResponseDurationMs(double avgResponseDurationMs) { this.avgResponseDurationMs = avgResponseDurationMs; }
    }

    private List<ProviderHealthDto> providers;

    public DashboardProvidersDto() {}

    public DashboardProvidersDto(List<ProviderHealthDto> providers) {
        this.providers = providers;
    }

    public List<ProviderHealthDto> getProviders() { return providers; }
    public void setProviders(List<ProviderHealthDto> providers) { this.providers = providers; }
}
