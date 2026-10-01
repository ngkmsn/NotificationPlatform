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

    public PanacheQuery<Notification> findQueued(Channel channel, com.notification.domain.Priority priority, String recipient) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", NotificationStatus.QUEUED);
        StringBuilder query = new StringBuilder("status = :status");

        if (channel != null) {
            query.append(" and channel = :channel");
            params.put("channel", channel);
        }

        if (priority != null) {
            query.append(" and priority = :priority");
            params.put("priority", priority);
        }

        if (recipient != null && !recipient.isBlank()) {
            query.append(" and lower(recipient) like lower(:recipient)");
            params.put("recipient", "%" + recipient.trim() + "%");
        }

        return find(query.toString(), Sort.descending("createdAt"), params);
    }

    public Notification findByIdForUpdate(UUID id) {
        return find("id = ?1", id).withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
    }

    public long countByStatus(NotificationStatus status) {
        return count("status", status);
    }

    public long countByPriority(com.notification.domain.Priority priority) {
        return count("priority", priority);
    }

    public long countByPriorityAndStatus(com.notification.domain.Priority priority, NotificationStatus status) {
        return count("priority = ?1 and status = ?2", priority, status);
    }

    public long countByChannel(Channel channel) {
        return count("channel", channel);
    }

    public long countByChannelAndStatus(Channel channel, NotificationStatus status) {
        return count("channel = ?1 and status = ?2", channel, status);
    }

    public java.util.List<Object[]> countGroupByPriorityAndStatus() {
        return getEntityManager()
                .createQuery("SELECT n.priority, n.status, COUNT(n) FROM Notification n GROUP BY n.priority, n.status", Object[].class)
                .getResultList();
    }

    public java.util.List<Object[]> countGroupByChannelAndStatus() {
        return getEntityManager()
                .createQuery("SELECT n.channel, n.status, COUNT(n) FROM Notification n GROUP BY n.channel, n.status", Object[].class)
                .getResultList();
    }
}
