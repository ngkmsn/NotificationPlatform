package com.notification.repository;

import com.notification.domain.OutboxEvent;
import com.notification.domain.OutboxStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class OutboxEventRepository implements PanacheRepositoryBase<OutboxEvent, UUID> {

    public List<OutboxEvent> findPendingEvents(int limit) {
        return find("status = ?1 ORDER BY createdAt ASC", OutboxStatus.PENDING)
                .page(Page.of(0, limit))
                .list();
    }
}
