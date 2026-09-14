package com.notification.provider;

import com.notification.domain.Channel;

public interface NotificationProvider {
    Channel supportsChannel();
}
