package com.notification.api;

import com.notification.api.dto.DeviceResponse;
import com.notification.api.dto.RegisterDeviceRequest;
import com.notification.application.DeviceService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

@Path("/api/v1/devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DeviceResource {

    @Inject
    DeviceService deviceService;

    @POST
    public Response registerDevice(@Valid @NotNull RegisterDeviceRequest request) {
        DeviceResponse response = deviceService.registerDevice(request);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    @GET
    @Path("/{userId}")
    public Response getDevices(@PathParam("userId") String userId) {
        List<DeviceResponse> devices = deviceService.getDevicesByUserId(userId);
        return Response.ok(devices).build();
    }
}
