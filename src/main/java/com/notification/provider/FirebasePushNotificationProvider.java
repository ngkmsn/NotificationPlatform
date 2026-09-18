package com.notification.provider;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.notification.domain.Channel;
import com.notification.domain.Notification;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Optional;

@ApplicationScoped
public class FirebasePushNotificationProvider implements NotificationProvider {

    private static final Logger LOG = Logger.getLogger(FirebasePushNotificationProvider.class);
    public static final String PROVIDER_NAME = "FirebasePushNotificationProvider";

    @ConfigProperty(name = "firebase.push.enabled", defaultValue = "true")
    public boolean enabled = true;

    @ConfigProperty(name = "quarkus.google.cloud.project-id", defaultValue = "notification-a0c90")
    public String projectId = "notification-a0c90";

    @ConfigProperty(name = "quarkus.google.cloud.service-account-location", defaultValue = "firebase-service-account.json")
    public String serviceAccountLocation = "firebase-service-account.json";

    private FirebaseMessaging firebaseMessaging;
    private boolean initialized = false;

    @PostConstruct
    public void init() {
        if (!enabled) {
            LOG.infof("[%s] is disabled by configuration.", PROVIDER_NAME);
            return;
        }

        try {
            InputStream credentialsStream = resolveCredentialsStream(serviceAccountLocation);
            if (credentialsStream == null) {
                LOG.warnf("[%s] Could not locate Firebase credentials at: %s. Provider will remain uninitialized.",
                        PROVIDER_NAME, serviceAccountLocation);
                return;
            }

            GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(projectId)
                    .build();

            FirebaseApp app;
            if (FirebaseApp.getApps().isEmpty()) {
                app = FirebaseApp.initializeApp(options);
            } else {
                app = FirebaseApp.getInstance();
            }

            this.firebaseMessaging = FirebaseMessaging.getInstance(app);
            this.initialized = true;
            LOG.infof("[%s] Initialized successfully for Firebase project: %s", PROVIDER_NAME, projectId);

        } catch (Exception e) {
            LOG.errorf(e, "[%s] Failed to initialize Firebase Admin SDK", PROVIDER_NAME);
        }
    }

    private InputStream resolveCredentialsStream(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }

        // 1. Try resolving as classpath resource
        InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(location);
        if (is != null) {
            return is;
        }

        // 2. Try resolving as relative or absolute filesystem file
        File file = new File(location);
        if (file.exists() && file.isFile()) {
            try {
                return new FileInputStream(file);
            } catch (Exception e) {
                LOG.warnf("Failed to read file at path: %s", location);
            }
        }

        return null;
    }

    @Override
    public boolean supportsChannel(Channel channel) {
        return enabled && initialized && channel == Channel.PUSH;
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public ProviderSendResult send(Notification notification) {
        if (!initialized || firebaseMessaging == null) {
            return ProviderSendResult.serverError("Firebase Push provider is not initialized");
        }

        if (notification.getRecipient() == null || notification.getRecipient().isBlank()) {
            return ProviderSendResult.failure(400, "Recipient FCM token is missing or empty");
        }

        try {
            String title = notification.getSubject() != null ? notification.getSubject() : "Notification";
            String body = notification.getContent();

            Message.Builder messageBuilder = Message.builder()
                    .setToken(notification.getRecipient().trim())
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .setWebpushConfig(com.google.firebase.messaging.WebpushConfig.builder()
                            .setNotification(com.google.firebase.messaging.WebpushNotification.builder()
                                    .setTitle(title)
                                    .setBody(body)
                                    .setIcon("https://firebase.google.com/favicon.ico")
                                    .setRequireInteraction(true)
                                    .build())
                            .build());

            if (notification.getId() != null) {
                messageBuilder.putData("notificationId", notification.getId().toString());
            }
            if (notification.getPriority() != null) {
                messageBuilder.putData("priority", notification.getPriority().name());
            }

            Message message = messageBuilder.build();
            String fcmMessageId = firebaseMessaging.send(message);
            LOG.infof("[%s] Successfully sent FCM push notification [%s], FCM Message ID: %s",
                    PROVIDER_NAME, notification.getId(), fcmMessageId);

            return ProviderSendResult.success(fcmMessageId);

        } catch (FirebaseMessagingException e) {
            LOG.errorf(e, "[%s] FirebaseMessagingException when sending notification [%s]: code=%s",
                    PROVIDER_NAME, notification.getId(), e.getMessagingErrorCode());
            return mapFirebaseError(e);
        } catch (Exception e) {
            LOG.errorf(e, "[%s] Unexpected error when sending notification [%s]", PROVIDER_NAME, notification.getId());
            return ProviderSendResult.serverError("Unexpected error sending push notification: " + e.getMessage());
        }
    }

    ProviderSendResult mapFirebaseError(FirebaseMessagingException e) {
        MessagingErrorCode errorCode = e.getMessagingErrorCode();
        return mapFirebaseErrorCode(errorCode, e.getMessage());
    }

    ProviderSendResult mapFirebaseErrorCode(MessagingErrorCode errorCode, String message) {
        if (errorCode == null) {
            return ProviderSendResult.serverError("Firebase error: " + message);
        }

        return switch (errorCode) {
            case QUOTA_EXCEEDED -> ProviderSendResult.rateLimit("FCM Quota exceeded: " + message);
            case UNAVAILABLE -> ProviderSendResult.timeout("FCM Service Unavailable (503): " + message);
            case INTERNAL -> ProviderSendResult.serverError("FCM Internal server error (500): " + message);
            case INVALID_ARGUMENT, UNREGISTERED, SENDER_ID_MISMATCH ->
                    ProviderSendResult.failure(400, "FCM Client error (" + errorCode + "): " + message);
            default -> ProviderSendResult.failure(500, "FCM error (" + errorCode + "): " + message);
        };
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setFirebaseMessaging(FirebaseMessaging firebaseMessaging) {
        this.firebaseMessaging = firebaseMessaging;
        this.initialized = (firebaseMessaging != null);
    }
}
