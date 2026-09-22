package com.notification.repository;

import com.notification.domain.UserDevice;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UserDeviceRepository implements PanacheRepositoryBase<UserDevice, UUID> {

    public List<UserDevice> findActiveDevicesByUserId(String userId) {
        return list("userId = ?1 and isActive = true", userId);
    }

    public UserDevice findByUserIdAndToken(String userId, String deviceToken) {
        return find("userId = ?1 and deviceToken = ?2", userId, deviceToken).firstResult();
    }

    public List<UserDevice> findAllActiveDevices() {
        return list("isActive = true");
    }

    public UserDevice findByToken(String deviceToken) {
        return find("deviceToken = ?1", deviceToken).firstResult();
    }
}
