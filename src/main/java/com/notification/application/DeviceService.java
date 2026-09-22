package com.notification.application;

import com.notification.api.dto.DeviceResponse;
import com.notification.api.dto.RegisterDeviceRequest;
import com.notification.domain.Platform;
import com.notification.domain.UserDevice;
import com.notification.repository.UserDeviceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class DeviceService {

    @Inject
    UserDeviceRepository userDeviceRepository;

    @Transactional
    public DeviceResponse registerDevice(RegisterDeviceRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new ValidationException("userId must not be blank");
        }
        if (request.getDeviceToken() == null || request.getDeviceToken().isBlank()) {
            throw new ValidationException("deviceToken must not be blank");
        }

        String userId = request.getUserId().trim();
        String token = request.getDeviceToken().trim();
        Platform platform = parsePlatform(request.getPlatform());

        UserDevice existing = userDeviceRepository.findByUserIdAndToken(userId, token);
        if (existing != null) {
            existing.setIsActive(true);
            existing.setPlatform(platform);
            existing.setUpdatedAt(OffsetDateTime.now());
            userDeviceRepository.persist(existing);
            return new DeviceResponse(existing.getId(), existing.getUserId(), existing.getDeviceToken(), existing.getPlatform().name(), existing.getIsActive(), "Device updated successfully");
        }

        UserDevice newDevice = new UserDevice();
        newDevice.setId(UUID.randomUUID());
        newDevice.setUserId(userId);
        newDevice.setDeviceToken(token);
        newDevice.setPlatform(platform);
        newDevice.setIsActive(true);
        newDevice.setCreatedAt(OffsetDateTime.now());
        newDevice.setUpdatedAt(OffsetDateTime.now());

        userDeviceRepository.persist(newDevice);

        return new DeviceResponse(newDevice.getId(), newDevice.getUserId(), newDevice.getDeviceToken(), newDevice.getPlatform().name(), newDevice.getIsActive(), "Device registered successfully");
    }

    public List<DeviceResponse> getDevicesByUserId(String userId) {
        return userDeviceRepository.findActiveDevicesByUserId(userId)
                .stream()
                .map(d -> new DeviceResponse(d.getId(), d.getUserId(), d.getDeviceToken(), d.getPlatform().name(), d.getIsActive(), null))
                .collect(Collectors.toList());
    }

    private Platform parsePlatform(String platformStr) {
        if (platformStr == null || platformStr.isBlank()) {
            return Platform.WEB;
        }
        try {
            return Platform.valueOf(platformStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Platform.WEB;
        }
    }
}
