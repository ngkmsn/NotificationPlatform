package com.notification.repository;

import com.notification.domain.Channel;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class NotificationRepository implements PanacheRepositoryBase<Notification, UUID> {

    public PanacheQuery<Notification> findDlq(Channel channel, String recipient) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", NotificationStatus.DEAD_LETTER);
        StringBuilder query = new StringBuilder("status = :status");

        if (channel != null) {
            query.append(" and channel = :channel");
            params.put("channel", channel);
        }

        if (recipient != null && !recipient.isBlank()) {
            query.append(" and lower(recipient) like lower(:recipient)");
            params.put("recipient", "%" + recipient.trim() + "%");
        }

        return find(query.toString(), Sort.descending("updatedAt"), params);
    }

    public Notification findByIdForUpdate(UUID id) {
        return find("id = ?1", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
    }
}
