package com.notification.api;

import com.notification.api.dto.DashboardOverviewDto;
import com.notification.api.dto.DashboardProvidersDto;
import com.notification.api.dto.DashboardQueuesDto;
import com.notification.application.DashboardService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DashboardResource {

    @Inject
    DashboardService dashboardService;

    @GET
    @Path("/overview")
    public Response getOverview() {
        DashboardOverviewDto dto = dashboardService.getOverview();
        return Response.ok(dto).build();
    }

    @GET
    @Path("/queues")
    public Response getQueues() {
        DashboardQueuesDto dto = dashboardService.getQueues();
        return Response.ok(dto).build();
    }

    @GET
    @Path("/providers")
    public Response getProviders() {
        DashboardProvidersDto dto = dashboardService.getProviders();
        return Response.ok(dto).build();
    }

    @jakarta.ws.rs.POST
    @Path("/simulate-load")
    public Response simulateLoad(@jakarta.ws.rs.QueryParam("enabled") boolean enabled,
                                 @jakarta.ws.rs.QueryParam("cpu") @jakarta.ws.rs.DefaultValue("35.0") double cpu,
                                 @jakarta.ws.rs.QueryParam("ram") @jakarta.ws.rs.DefaultValue("40.0") double ram) {
        if (enabled && cpu < 70.0) {
            dashboardService.simulateHealthy();
        } else {
            dashboardService.setHardwareSimulation(enabled, cpu, ram);
        }
        return Response.ok(java.util.Map.of(
                "simulationActive", enabled,
                "simulatedCpu", enabled && cpu < 70.0 ? 35.0 : cpu,
                "simulatedRam", enabled && cpu < 70.0 ? 40.0 : ram,
                "message", enabled ? (cpu < 70.0 ? "Hardware simulation forced to NORMAL (OK)" : "Hardware overload simulation enabled") : "Hardware overload simulation disabled"
        )).build();
    }
}
