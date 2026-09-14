package com.notification.api;

import com.notification.api.dto.CreateNotificationRequest;
import com.notification.api.dto.CreateNotificationResponse;
import com.notification.application.NotificationService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationResource {

    @Inject
    NotificationService notificationService;

    @POST
    public Response createNotification(@Valid @NotNull CreateNotificationRequest request) {
        CreateNotificationResponse response = notificationService.createNotification(request);
        return Response.status(Response.Status.ACCEPTED).entity(response).build();
    }
}
