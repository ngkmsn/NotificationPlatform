package com.notification.api;

import com.notification.circuitbreaker.CircuitBreaker;
import com.notification.circuitbreaker.CircuitBreakerState;
import com.notification.provider.MockNotificationProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v1/circuit-breaker")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CircuitBreakerResource {

    @Inject
    CircuitBreaker circuitBreaker;

    @Inject
    MockNotificationProvider mockProvider;

    @GET
    public Response getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        
        CircuitBreakerState mockState = circuitBreaker.getState("provider:mocknotificationprovider");
        CircuitBreakerState firebaseState = circuitBreaker.getState("provider:firebasepushnotificationprovider");

        result.put("mockProviderState", mockState.name());
        result.put("firebaseProviderState", firebaseState.name());
        result.put("mockSimulationMode", mockProvider.getEffectiveMode().name());

        return Response.ok(result).build();
    }

    @POST
    @Path("/simulate-mode")
    public Response setSimulationMode(@QueryParam("mode") String modeStr) {
        if (modeStr == null || modeStr.isBlank()) {
            mockProvider.resetSimulationMode();
        } else {
            try {
                MockNotificationProvider.SimulationMode mode = MockNotificationProvider.SimulationMode.valueOf(modeStr.trim().toUpperCase());
                mockProvider.setSimulationMode(mode);
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid mode. Supported: SUCCESS, SERVER_ERROR_500, RATE_LIMIT_429, TIMEOUT"))
                        .build();
            }
        }

        return Response.ok(Map.of(
                "message", "Simulation mode updated successfully",
                "currentMode", mockProvider.getEffectiveMode().name()
        )).build();
    }

    @POST
    @Path("/reset")
    public Response reset() {
        circuitBreaker.reset("provider:mocknotificationprovider");
        circuitBreaker.reset("provider:firebasepushnotificationprovider");
        circuitBreaker.reset("MockNotificationProvider");
        circuitBreaker.reset("FirebasePushNotificationProvider");
        mockProvider.resetSimulationMode();

        return Response.ok(Map.of(
                "message", "Circuit breaker states and simulation modes reset successfully"
        )).build();
    }
}
