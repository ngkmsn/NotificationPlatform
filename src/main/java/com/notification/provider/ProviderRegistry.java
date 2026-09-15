package com.notification.provider;

import com.notification.domain.Channel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

@ApplicationScoped
public class ProviderRegistry {

    private static final Logger LOG = Logger.getLogger(ProviderRegistry.class);

    @Inject
    Instance<NotificationProvider> providers;

    public Optional<NotificationProvider> getProviderForChannel(Channel channel) {
        if (channel == null) {
            return Optional.empty();
        }

        for (NotificationProvider provider : providers) {
            if (provider.supportsChannel(channel)) {
                return Optional.of(provider);
            }
        }

        LOG.warnf("No active provider found supporting channel [%s]", channel);
        return Optional.empty();
    }

    public Optional<NotificationProvider> getProviderByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        for (NotificationProvider provider : providers) {
            if (name.equalsIgnoreCase(provider.getName())) {
                return Optional.of(provider);
            }
        }

        return Optional.empty();
    }
}
