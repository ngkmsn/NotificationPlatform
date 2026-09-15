package com.notification.repository;

import com.notification.domain.Provider;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;

@ApplicationScoped
public class ProviderRepository implements PanacheRepositoryBase<Provider, UUID> {
}
