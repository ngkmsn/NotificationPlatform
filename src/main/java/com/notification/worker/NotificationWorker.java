package com.notification.worker;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NotificationWorker {

    private static final Logger LOG = Logger.getLogger(NotificationWorker.class);

    @Inject
    NotificationProcessor notificationProcessor;

    @ConfigProperty(name = "notification.worker.enabled", defaultValue = "true")
    boolean enabled;

    @Incoming("notification-critical-in")
    @Blocking
    public void consumeCritical(String payload) {
        if (!enabled) {
            LOG.debug("NotificationWorker is disabled. Skipping critical message.");
            return;
        }
        LOG.debugf("Worker received CRITICAL message: %s", payload);
        notificationProcessor.processMessage(payload);
    }

    @Incoming("notification-high-in")
    @Blocking
    public void consumeHigh(String payload) {
        if (!enabled) {
            LOG.debug("NotificationWorker is disabled. Skipping high message.");
            return;
        }
        LOG.debugf("Worker received HIGH message: %s", payload);
        notificationProcessor.processMessage(payload);
    }

    @Incoming("notification-normal-in")
    @Blocking
    public void consumeNormal(String payload) {
        if (!enabled) {
            LOG.debug("NotificationWorker is disabled. Skipping normal message.");
            return;
        }
        LOG.debugf("Worker received NORMAL message: %s", payload);
        notificationProcessor.processMessage(payload);
    }

    @Incoming("notification-low-in")
    @Blocking
    public void consumeLow(String payload) {
        if (!enabled) {
            LOG.debug("NotificationWorker is disabled. Skipping low message.");
            return;
        }
        LOG.debugf("Worker received LOW message: %s", payload);
        notificationProcessor.processMessage(payload);
    }
}
