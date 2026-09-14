package com.notification.repository;

import com.notification.domain.NotificationAttempt;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class NotificationAttemptRepository implements PanacheRepositoryBase<NotificationAttempt, UUID> {
}
