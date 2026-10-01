package com.notification.api;

import com.notification.api.dto.PageResponse;
import com.notification.api.dto.QueueActionResponse;
import com.notification.api.dto.QueueBulkActionResponse;
import com.notification.api.dto.QueuedNotificationSummaryResponse;
import com.notification.application.QueueManagementService;
import com.notification.domain.Channel;
import com.notification.domain.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

@Path("/api/v1/queue")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QueueResource {

    @Inject
    QueueManagementService queueManagementService;

    @GET
    public Response listQueued(
            @QueryParam("channel") String channelStr,
            @QueryParam("priority") String priorityStr,
            @QueryParam("recipient") String recipient,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        Channel channel = parseChannel(channelStr);
        Priority priority = parsePriority(priorityStr);
        PageResponse<QueuedNotificationSummaryResponse> response =
                queueManagementService.getQueuedNotifications(channel, priority, recipient, page, size);
        return Response.ok(response).build();
    }

    @POST
    @Path("/re-enqueue-all")
    public Response reEnqueueAll(
            @QueryParam("channel") String channelStr,
            @QueryParam("priority") String priorityStr) {

        Channel channel = parseChannel(channelStr);
        Priority priority = parsePriority(priorityStr);
        QueueBulkActionResponse response = queueManagementService.reEnqueueAll(channel, priority);
        return Response.ok(response).build();
    }

    @POST
    @Path("/cancel-all")
    public Response cancelAll(
            @QueryParam("channel") String channelStr,
            @QueryParam("priority") String priorityStr) {

        Channel channel = parseChannel(channelStr);
        Priority priority = parsePriority(priorityStr);
        QueueBulkActionResponse response = queueManagementService.cancelAll(channel, priority);
        return Response.ok(response).build();
    }

    @POST
    @Path("/{id}/re-enqueue")
    public Response reEnqueueSingle(@PathParam("id") UUID id) {
        QueueActionResponse response = queueManagementService.reEnqueueSingle(id);
        return Response.ok(response).build();
    }

    @POST
    @Path("/{id}/cancel")
    public Response cancelSingle(@PathParam("id") UUID id) {
        QueueActionResponse response = queueManagementService.cancelSingle(id);
        return Response.ok(response).build();
    }

    @POST
    @Path("/{id}/promote-critical")
    public Response promoteToCritical(@PathParam("id") UUID id) {
        QueueActionResponse response = queueManagementService.promoteToCritical(id);
        return Response.ok(response).build();
    }

    private Channel parseChannel(String channelStr) {
        if (channelStr == null || channelStr.isBlank()) {
            return null;
        }
        try {
            return Channel.valueOf(channelStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Priority parsePriority(String priorityStr) {
        if (priorityStr == null || priorityStr.isBlank()) {
            return null;
        }
        try {
            return Priority.valueOf(priorityStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
