package com.notification.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class DashboardResourceTest {

    @Test
    @DisplayName("GET /api/v1/dashboard/overview returns 200 with telemetry metrics")
    void testGetDashboardOverview() {
        given()
            .when()
                .get("/api/v1/dashboard/overview")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("totalCreated", notNullValue())
                .body("totalDelivered", notNullValue())
                .body("totalFailed", notNullValue())
                .body("totalPending", notNullValue())
                .body("totalDlq", notNullValue())
                .body("successRatePercent", notNullValue());
    }

    @Test
    @DisplayName("GET /api/v1/dashboard/queues returns 200 with priority and channel stats")
    void testGetDashboardQueues() {
        given()
            .when()
                .get("/api/v1/dashboard/queues")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("byPriority", notNullValue())
                .body("byPriority.size()", equalTo(4))
                .body("byChannel", notNullValue())
                .body("byChannel.size()", equalTo(4));
    }

    @Test
    @DisplayName("GET /api/v1/dashboard/providers returns 200 with provider states")
    void testGetDashboardProviders() {
        given()
            .when()
                .get("/api/v1/dashboard/providers")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("providers", notNullValue())
                .body("providers.size()", greaterThanOrEqualTo(3));
    }
}
