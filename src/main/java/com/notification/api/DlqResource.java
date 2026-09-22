package com.notification.api;

import com.notification.api.dto.DlqActionResponse;
import com.notification.api.dto.DlqNotificationDetailResponse;
import com.notification.api.dto.DlqNotificationSummaryResponse;
import com.notification.api.dto.PageResponse;
import com.notification.application.ValidationException;
import com.notification.dlq.DeadLetterService;
import com.notification.domain.Channel;
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

import java.util.Arrays;
import java.util.UUID;

@Path("/api/v1/dlq")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DlqResource {

    @Inject
    DeadLetterService deadLetterService;

    @GET
    public Response listDlq(
            @QueryParam("channel") String channelStr,
            @QueryParam("recipient") String recipient,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {

        Channel channel = parseChannel(channelStr);
        PageResponse<DlqNotificationSummaryResponse> response = deadLetterService.getDlqNotifications(channel, recipient, page, size);
        return Response.ok(response).build();
    }

    @GET
    @Path("/{id}")
    public Response getDlqDetail(@PathParam("id") UUID id) {
        DlqNotificationDetailResponse response = deadLetterService.getDlqDetail(id);
        return Response.ok(response).build();
    }

    @POST
    @Path("/{id}/retry")
    public Response retryDlq(@PathParam("id") UUID id) {
        DlqActionResponse response = deadLetterService.retryDlqNotification(id);
        return Response.ok(response).build();
    }

    @POST
    @Path("/{id}/cancel")
    public Response cancelDlq(@PathParam("id") UUID id) {
        DlqActionResponse response = deadLetterService.cancelDlqNotification(id);
        return Response.ok(response).build();
    }

    @POST
    @Path("/retry-all")
    public Response retryAllDlq(@QueryParam("channel") String channelStr) {
        Channel channel = parseChannel(channelStr);
        com.notification.api.dto.DlqBulkActionResponse response = deadLetterService.retryAllDlq(channel);
        return Response.ok(response).build();
    }

    @POST
    @Path("/cancel-all")
    public Response cancelAllDlq(@QueryParam("channel") String channelStr) {
        Channel channel = parseChannel(channelStr);
        com.notification.api.dto.DlqBulkActionResponse response = deadLetterService.cancelAllDlq(channel);
        return Response.ok(response).build();
    }

    private Channel parseChannel(String channelStr) {
        if (channelStr == null || channelStr.isBlank()) {
            return null;
        }
        try {
            return Channel.valueOf(channelStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Invalid channel: " + channelStr + ". Supported channels: " + Arrays.toString(Channel.values()));
        }
    }
}
