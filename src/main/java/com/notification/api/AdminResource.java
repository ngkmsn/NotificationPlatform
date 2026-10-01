package com.notification.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;

@Path("/admin")
public class AdminResource {

    @GET
    public Response redirectToAdminPage() {
        return Response.seeOther(URI.create("/admin.html")).build();
    }
}
