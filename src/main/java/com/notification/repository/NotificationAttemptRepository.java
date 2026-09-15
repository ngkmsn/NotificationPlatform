package com.notification.repository;

import com.notification.domain.NotificationAttempt;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class NotificationAttemptRepository implements PanacheRepositoryBase<NotificationAttempt, UUID> {

    public List<NotificationAttempt> findByNotificationId(UUID notificationId) {
        return list("notification.id = ?1 order by attemptNumber asc", notificationId);
    }
}

