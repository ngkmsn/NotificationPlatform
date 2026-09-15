package com.notification.provider;

import com.notification.domain.Channel;
import com.notification.domain.Notification;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class MockNotificationProvider implements NotificationProvider {

    private static final Logger LOG = Logger.getLogger(MockNotificationProvider.class);
    public static final String PROVIDER_NAME = "MockNotificationProvider";

    public enum SimulationMode {
        SUCCESS,
        RATE_LIMIT_429,
        SERVER_ERROR_500,
        TIMEOUT
    }

    @ConfigProperty(name = "mock.provider.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "mock.provider.mode", defaultValue = "SUCCESS")
    String configuredMode;

    private final AtomicReference<SimulationMode> runtimeModeOverride = new AtomicReference<>(null);

    @Override
    public boolean supportsChannel(Channel channel) {
        return enabled && channel != null;
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderSendResult send(Notification notification) {
        SimulationMode mode = getEffectiveMode();
        LOG.debugf("[%s] Sending notification [%s] via channel [%s] with mode [%s]",
                PROVIDER_NAME, notification.getId(), notification.getChannel(), mode);

        switch (mode) {
            case RATE_LIMIT_429:
                return ProviderSendResult.rateLimit("Mock provider rate limit exceeded (HTTP 429)");
            case SERVER_ERROR_500:
                return ProviderSendResult.serverError("Mock provider upstream internal error (HTTP 500)");
            case TIMEOUT:
                return ProviderSendResult.timeout("Mock provider network connection timed out (HTTP 504)");
            case SUCCESS:
            default:
                String mockMessageId = "mock-msg-" + UUID.randomUUID();
                return ProviderSendResult.success(mockMessageId);
        }
    }

    public SimulationMode getEffectiveMode() {
        SimulationMode override = runtimeModeOverride.get();
        if (override != null) {
            return override;
        }
        try {
            return SimulationMode.valueOf(configuredMode.trim().toUpperCase());
        } catch (Exception e) {
            return SimulationMode.SUCCESS;
        }
    }

    public void setSimulationMode(SimulationMode mode) {
        this.runtimeModeOverride.set(mode);
    }

    public void resetSimulationMode() {
        this.runtimeModeOverride.set(null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
