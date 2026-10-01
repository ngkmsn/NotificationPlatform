package com.notification.api;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.application.NotificationService;
import com.notification.domain.Priority;
import com.notification.guard.SystemLoadGuard;
import com.notification.metrics.NotificationMetrics;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v1/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationResource {

    @Inject
    NotificationService notificationService;

    @Inject
    SystemLoadGuard systemLoadGuard;

    @Inject
    NotificationMetrics notificationMetrics;

    @POST
    public Response createNotification(@Valid @NotNull CreateNotificationRequest request) {
        Priority priority = parsePriority(request.getPriority());

        // Hardware-Aware Emergency Cutoff (95%) & Adaptive Load Shedding (85%) (IMP-HardwareGuard)
        if (systemLoadGuard != null && systemLoadGuard.isCutoff()) {
            if (notificationMetrics != null) {
                notificationMetrics.recordLoadCutoff();
            }
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "60")
                    .entity(Map.of(
                            "error", "Service Unavailable",
                            "message", "Service is temporarily unavailable due to high system load. Please retry later.",
                            "status", 503,
                            "retryAfterSeconds", 60
                    ))
                    .build();
        }

        if (systemLoadGuard != null && systemLoadGuard.shouldThrottlePriority(priority)) {
            if (notificationMetrics != null) {
                notificationMetrics.recordLoadShed(priority);
            }
            return Response.status(429)
                    .header("Retry-After", "30")
                    .entity(Map.of(
                            "error", "Too Many Requests",
                            "message", String.format("System is experiencing high traffic. Priority [%s] is temporarily throttled. Please retry later.", priority),
                            "status", 429,
                            "retryAfterSeconds", 30
                    ))
                    .build();
        }

        CreateNotificationResponse response = notificationService.createNotification(request);
        return Response.status(Response.Status.ACCEPTED).entity(response).build();
    }

    @POST
    @Path("/bulk")
    public Response createNotificationsBulk(@Valid @NotNull com.notification.api.dto.CreateNotificationBulkRequest request) {
        // Hardware-Aware Emergency Cutoff (95%)
        if (systemLoadGuard != null && systemLoadGuard.isCutoff()) {
            if (notificationMetrics != null) {
                notificationMetrics.recordLoadCutoff();
            }
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "60")
                    .entity(Map.of(
                            "error", "Service Unavailable",
                            "message", "Service is temporarily unavailable due to high system load. Please retry later.",
                            "status", 503,
                            "retryAfterSeconds", 60
                    ))
                    .build();
        }

        com.notification.api.dto.CreateNotificationBulkResponse response = notificationService.createNotificationsBulk(request);
        return Response.status(Response.Status.ACCEPTED).entity(response).build();
    }

    private Priority parsePriority(String priorityStr) {
        if (priorityStr == null || priorityStr.isBlank()) {
            return Priority.NORMAL;
        }
        try {
            return Priority.valueOf(priorityStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Priority.NORMAL;
        }
    }
}
