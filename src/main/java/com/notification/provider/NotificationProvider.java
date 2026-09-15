package com.notification.provider;

import com.notification.domain.Channel;
import com.notification.domain.Notification;

public interface NotificationProvider {

    boolean supportsChannel(Channel channel);

    String getName();

    ProviderSendResult send(Notification notification);
}
