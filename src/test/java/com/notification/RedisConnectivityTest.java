package com.notification;

import com.notification.ratelimit.RedisRateLimitStateStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class RedisConnectivityTest {

    @Inject
    RedisRateLimitStateStore stateStore;

    private String testKey;

    @BeforeEach
    public void setup() {
        testKey = "test:ratelimit:" + UUID.randomUUID();
    }

    @AfterEach
    public void teardown() {
        if (testKey != null) {
            stateStore.delete(testKey);
        }
    }

    @Test
    public void testRedisPing() {
        assertTrue(stateStore.ping(), "Redis should be reachable and respond to ping");
    }

    @Test
    public void testSetAndGet() {
        String value = "100";
        stateStore.set(testKey, value, Duration.ofSeconds(30));

        Optional<String> retrieved = stateStore.get(testKey);
        assertTrue(retrieved.isPresent(), "Key should exist in Redis");
        assertEquals(value, retrieved.get());
    }

    @Test
    public void testSetIfAbsent() {
        String value1 = "initial";
        String value2 = "overwrite";

        // First set should succeed
        boolean set1 = stateStore.setIfAbsent(testKey, value1, Duration.ofSeconds(30));
        assertTrue(set1, "setIfAbsent on new key should return true");
        assertEquals(value1, stateStore.get(testKey).orElse(null));

        // Second set on existing key should not overwrite
        boolean set2 = stateStore.setIfAbsent(testKey, value2, Duration.ofSeconds(30));
        assertFalse(set2, "setIfAbsent on existing key should return false");
        assertEquals(value1, stateStore.get(testKey).orElse(null));
    }

    @Test
    public void testAtomicIncrement() {
        long c1 = stateStore.increment(testKey, 1, Duration.ofSeconds(30));
        assertEquals(1L, c1);

        long c2 = stateStore.increment(testKey, 5, Duration.ofSeconds(30));
        assertEquals(6L, c2);

        Optional<String> value = stateStore.get(testKey);
        assertTrue(value.isPresent());
        assertEquals("6", value.get());
    }

    @Test
    public void testDelete() {
        stateStore.set(testKey, "to-be-deleted", Duration.ofSeconds(30));
        assertTrue(stateStore.get(testKey).isPresent());

        boolean deleted = stateStore.delete(testKey);
        assertTrue(deleted, "Delete should return true when key exists");
        assertFalse(stateStore.get(testKey).isPresent(), "Key should not exist after deletion");
    }

    @Test
    public void testHealthEndpointIncludesRedis() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
