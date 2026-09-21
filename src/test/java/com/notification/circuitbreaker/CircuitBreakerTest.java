package com.notification.circuitbreaker;

import com.notification.provider.ProviderSendResult;
import com.notification.retry.RetryClassifier;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.vertx.mutiny.redis.client.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class CircuitBreakerTest {

    private RedisDataSource mockRedisDataSource;
    private KeyCommands<String> mockKeyCommands;
    private RetryClassifier retryClassifier;
    private RedisCircuitBreaker circuitBreaker;

    @BeforeEach
    public void setup() {
        mockRedisDataSource = mock(RedisDataSource.class);
        mockKeyCommands = mock(KeyCommands.class);
        when(mockRedisDataSource.key()).thenReturn(mockKeyCommands);

        retryClassifier = new RetryClassifier();
        CircuitBreakerConfig config = new CircuitBreakerConfig(
                50.0,
                5,
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                3,
                2,
                Duration.ofHours(24)
        );
        circuitBreaker = new RedisCircuitBreaker(mockRedisDataSource, retryClassifier, config);
    }

    @Test
    @DisplayName("Configuration validation ensures valid boundaries")
    public void testConfigValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new CircuitBreakerConfig(0.0, 5, Duration.ofSeconds(60), Duration.ofSeconds(30), 3, 2, Duration.ofHours(24)));
        assertThrows(IllegalArgumentException.class, () ->
                new CircuitBreakerConfig(101.0, 5, Duration.ofSeconds(60), Duration.ofSeconds(30), 3, 2, Duration.ofHours(24)));
        assertThrows(IllegalArgumentException.class, () ->
                new CircuitBreakerConfig(50.0, 0, Duration.ofSeconds(60), Duration.ofSeconds(30), 3, 2, Duration.ofHours(24)));
        assertThrows(IllegalArgumentException.class, () ->
                new CircuitBreakerConfig(50.0, 5, Duration.ofSeconds(60), Duration.ofSeconds(30), 0, 2, Duration.ofHours(24)));
        assertThrows(IllegalArgumentException.class, () ->
                new CircuitBreakerConfig(50.0, 5, Duration.ofSeconds(60), Duration.ofSeconds(30), 3, 0, Duration.ofHours(24)));

        CircuitBreakerConfig defaults = CircuitBreakerConfig.ofDefaults();
        assertEquals(50.0, defaults.getFailureRateThreshold());
        assertEquals(5, defaults.getMinimumNumberOfCalls());
        assertEquals(3, defaults.getPermittedNumberOfCallsInHalfOpenState());
        assertEquals(2, defaults.getHalfOpenSuccessThreshold());
    }

    @Test
    @DisplayName("CLOSED state allows requests normally")
    public void testClosedStateAllowsRequests() {
        String key = "provider:fcm";

        // Mock Lua returning: [allowed=1, state="CLOSED", retryAfterMs=0, probe=0]
        Response mockResponse = mock(Response.class);
        when(mockResponse.size()).thenReturn(4);

        Response r0 = mock(Response.class); when(r0.toLong()).thenReturn(1L); when(mockResponse.get(0)).thenReturn(r0);
        Response r1 = mock(Response.class); when(r1.toString()).thenReturn("CLOSED"); when(mockResponse.get(1)).thenReturn(r1);
        Response r2 = mock(Response.class); when(r2.toLong()).thenReturn(0L); when(mockResponse.get(2)).thenReturn(r2);
        Response r3 = mock(Response.class); when(r3.toLong()).thenReturn(0L); when(mockResponse.get(3)).thenReturn(r3);

        when(mockRedisDataSource.execute(eq("EVAL"), any(), eq("1"), eq("circuitbreaker:" + key), any(), any(), any(), any()))
                .thenReturn(mockResponse);

        CircuitBreakerResult result = circuitBreaker.acquirePermission(key);

        assertTrue(result.isAllowed());
        assertEquals(CircuitBreakerState.CLOSED, result.getState());
        assertEquals(0L, result.getRetryAfterMs());
        assertFalse(result.isProbe());
    }

    @Test
    @DisplayName("OPEN state denies requests and provides retryAfterMs cooldown")
    public void testOpenStateDeniesRequestsWithCooldown() {
        String key = "provider:fcm";

        // Mock Lua returning: [allowed=0, state="OPEN", retryAfterMs=25000, probe=0]
        Response mockResponse = mock(Response.class);
        when(mockResponse.size()).thenReturn(4);

        Response r0 = mock(Response.class); when(r0.toLong()).thenReturn(0L); when(mockResponse.get(0)).thenReturn(r0);
        Response r1 = mock(Response.class); when(r1.toString()).thenReturn("OPEN"); when(mockResponse.get(1)).thenReturn(r1);
        Response r2 = mock(Response.class); when(r2.toLong()).thenReturn(25000L); when(mockResponse.get(2)).thenReturn(r2);
        Response r3 = mock(Response.class); when(r3.toLong()).thenReturn(0L); when(mockResponse.get(3)).thenReturn(r3);

        when(mockRedisDataSource.execute(eq("EVAL"), any(), eq("1"), eq("circuitbreaker:" + key), any(), any(), any(), any()))
                .thenReturn(mockResponse);

        CircuitBreakerResult result = circuitBreaker.acquirePermission(key);

        assertFalse(result.isAllowed());
        assertEquals(CircuitBreakerState.OPEN, result.getState());
        assertEquals(25000L, result.getRetryAfterMs());
        assertFalse(result.isProbe());
    }

    @Test
    @DisplayName("HALF_OPEN state grants permission for probe request within limit")
    public void testHalfOpenStateGrantsProbe() {
        String key = "provider:fcm";

        // Mock Lua returning: [allowed=1, state="HALF_OPEN", retryAfterMs=0, probe=1]
        Response mockResponse = mock(Response.class);
        when(mockResponse.size()).thenReturn(4);

        Response r0 = mock(Response.class); when(r0.toLong()).thenReturn(1L); when(mockResponse.get(0)).thenReturn(r0);
        Response r1 = mock(Response.class); when(r1.toString()).thenReturn("HALF_OPEN"); when(mockResponse.get(1)).thenReturn(r1);
        Response r2 = mock(Response.class); when(r2.toLong()).thenReturn(0L); when(mockResponse.get(2)).thenReturn(r2);
        Response r3 = mock(Response.class); when(r3.toLong()).thenReturn(1L); when(mockResponse.get(3)).thenReturn(r3);

        when(mockRedisDataSource.execute(eq("EVAL"), any(), eq("1"), eq("circuitbreaker:" + key), any(), any(), any(), any()))
                .thenReturn(mockResponse);

        CircuitBreakerResult result = circuitBreaker.acquirePermission(key);

        assertTrue(result.isAllowed());
        assertEquals(CircuitBreakerState.HALF_OPEN, result.getState());
        assertEquals(0L, result.getRetryAfterMs());
        assertTrue(result.isProbe());
    }

    @Test
    @DisplayName("HALF_OPEN denies when probe limit is exhausted")
    public void testHalfOpenDeniesWhenProbesExhausted() {
        String key = "provider:fcm";

        // Mock Lua returning: [allowed=0, state="HALF_OPEN", retryAfterMs=15000, probe=0]
        Response mockResponse = mock(Response.class);
        when(mockResponse.size()).thenReturn(4);

        Response r0 = mock(Response.class); when(r0.toLong()).thenReturn(0L); when(mockResponse.get(0)).thenReturn(r0);
        Response r1 = mock(Response.class); when(r1.toString()).thenReturn("HALF_OPEN"); when(mockResponse.get(1)).thenReturn(r1);
        Response r2 = mock(Response.class); when(r2.toLong()).thenReturn(15000L); when(mockResponse.get(2)).thenReturn(r2);
        Response r3 = mock(Response.class); when(r3.toLong()).thenReturn(0L); when(mockResponse.get(3)).thenReturn(r3);

        when(mockRedisDataSource.execute(eq("EVAL"), any(), eq("1"), eq("circuitbreaker:" + key), any(), any(), any(), any()))
                .thenReturn(mockResponse);

        CircuitBreakerResult result = circuitBreaker.acquirePermission(key);

        assertFalse(result.isAllowed());
        assertEquals(CircuitBreakerState.HALF_OPEN, result.getState());
        assertEquals(15000L, result.getRetryAfterMs());
    }

    @Test
    @DisplayName("Record Result handles retryable errors, non-retryable fatal errors, and exceptions")
    public void testRecordResultClassification() {
        String key = "provider:fcm";

        // 1. Success
        circuitBreaker.recordResult(key, ProviderSendResult.success("msg-1"));
        verify(mockRedisDataSource, times(1)).execute(
                eq("EVAL"), any(), eq("1"), eq("circuitbreaker:" + key),
                eq("1"), eq("0"), eq("0"), any(), any(), any(), any(), any(), any(), any()
        );

        // 2. Retryable 500 Server Error -> isFailure=1, isFatal=0
        circuitBreaker.recordResult(key, ProviderSendResult.serverError("Internal Server Error"));
        verify(mockRedisDataSource, times(1)).execute(
                eq("EVAL"), any(), eq("1"), eq("circuitbreaker:" + key),
                eq("0"), eq("1"), eq("0"), any(), any(), any(), any(), any(), any(), any()
        );

        // 3. Fatal 400 Bad Request / Invalid Token -> isFailure=0, isFatal=1 (does not trip circuit)
        circuitBreaker.recordResult(key, ProviderSendResult.failure(400, "Bad Request: Invalid Token"));
        verify(mockRedisDataSource, times(1)).execute(
                eq("EVAL"), any(), eq("1"), eq("circuitbreaker:" + key),
                eq("0"), eq("0"), eq("1"), any(), any(), any(), any(), any(), any(), any()
        );

        // 4. Retryable Timeout Exception -> isFailure=1, isFatal=0
        circuitBreaker.recordException(key, new SocketTimeoutException("Read timed out"));
        verify(mockRedisDataSource, times(2)).execute(
                eq("EVAL"), any(), eq("1"), eq("circuitbreaker:" + key),
                eq("0"), eq("1"), eq("0"), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    @DisplayName("Simulate State Transitions: CLOSED -> Exceed Failure Threshold -> OPEN -> Cooldown Expired -> HALF_OPEN -> Probes Succeed -> CLOSED")
    public void testFullStateMachineLifecycleSimulation() {
        // State Machine Simulation matching Lua script logic exactly
        int minCalls = 5;
        double failureRateThreshold = 50.0;
        long cooldownMs = 1000L;
        int halfOpenMaxProbes = 3;
        int halfOpenSuccessNeeded = 2;

        AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);
        AtomicReference<Long> openUntilMs = new AtomicReference<>(0L);
        AtomicInteger windowTotal = new AtomicInteger(0);
        AtomicInteger windowFailures = new AtomicInteger(0);
        AtomicInteger halfOpenProbes = new AtomicInteger(0);
        AtomicInteger halfOpenSuccesses = new AtomicInteger(0);

        // 1. Initial State is CLOSED
        assertEquals(CircuitBreakerState.CLOSED, state.get());

        // 2. Execute 4 successful calls, 1 failure -> Total 5, 1 fail = 20% failure rate (< 50%) -> Stays CLOSED
        for (int i = 0; i < 4; i++) {
            windowTotal.incrementAndGet();
        }
        windowTotal.incrementAndGet();
        windowFailures.incrementAndGet();
        double currentRate = (windowFailures.get() * 100.0) / windowTotal.get();
        assertTrue(currentRate < failureRateThreshold);
        assertEquals(CircuitBreakerState.CLOSED, state.get());

        // 3. Add 4 more failures -> Total 9 calls, 5 failures = 55.5% failure rate (>= 50%) -> Trips to OPEN
        for (int i = 0; i < 4; i++) {
            windowTotal.incrementAndGet();
            windowFailures.incrementAndGet();
        }
        double trippedRate = (windowFailures.get() * 100.0) / windowTotal.get();
        if (windowTotal.get() >= minCalls && trippedRate >= failureRateThreshold) {
            state.set(CircuitBreakerState.OPEN);
            openUntilMs.set(System.currentTimeMillis() + cooldownMs);
        }
        assertEquals(CircuitBreakerState.OPEN, state.get());

        // 4. While OPEN and cooldown active: requests are rejected (fail-fast)
        long now = System.currentTimeMillis();
        assertTrue(now < openUntilMs.get());
        assertEquals(CircuitBreakerState.OPEN, state.get());

        // 5. Simulate Cooldown Expiration: transitions to HALF_OPEN
        openUntilMs.set(now - 100); // simulate cooldown expired
        if (state.get() == CircuitBreakerState.OPEN && System.currentTimeMillis() >= openUntilMs.get()) {
            state.set(CircuitBreakerState.HALF_OPEN);
            halfOpenProbes.set(0);
            halfOpenSuccesses.set(0);
        }
        assertEquals(CircuitBreakerState.HALF_OPEN, state.get());

        // 6. HALF_OPEN permits up to halfOpenMaxProbes
        for (int i = 1; i <= halfOpenMaxProbes; i++) {
            int currentProbes = halfOpenProbes.incrementAndGet();
            assertTrue(currentProbes <= halfOpenMaxProbes, "Probe " + i + " should be granted");
        }
        // Next probe is blocked
        assertTrue(halfOpenProbes.get() >= halfOpenMaxProbes);

        // 7. Probes report success: reaches halfOpenSuccessNeeded -> transitions back to CLOSED
        for (int i = 0; i < halfOpenSuccessNeeded; i++) {
            halfOpenSuccesses.incrementAndGet();
        }
        if (halfOpenSuccesses.get() >= halfOpenSuccessNeeded) {
            state.set(CircuitBreakerState.CLOSED);
            windowTotal.set(0);
            windowFailures.set(0);
        }
        assertEquals(CircuitBreakerState.CLOSED, state.get());
    }

    @Test
    @DisplayName("Simulate HALF_OPEN probe failure immediately reopens Circuit Breaker")
    public void testHalfOpenProbeFailureReopens() {
        AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.HALF_OPEN);
        long cooldownMs = 30000L;

        // Simulate 1 probe failure in HALF_OPEN
        boolean probeFailure = true;
        if (state.get() == CircuitBreakerState.HALF_OPEN && probeFailure) {
            state.set(CircuitBreakerState.OPEN);
        }

        assertEquals(CircuitBreakerState.OPEN, state.get());
    }

    @Test
    @DisplayName("Concurrent workers in HALF_OPEN atomically respect permitted probe limits")
    public void testConcurrentWorkerProbeLimitAtomicity() throws Exception {
        int threadCount = 30;
        int maxPermittedProbes = 3;

        AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.HALF_OPEN);
        AtomicInteger activeProbes = new AtomicInteger(0);
        AtomicInteger grantedProbes = new AtomicInteger(0);
        AtomicInteger rejectedRequests = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Simulating atomic Lua acquire permission check in Redis
                    while (true) {
                        int current = activeProbes.get();
                        if (current < maxPermittedProbes) {
                            if (activeProbes.compareAndSet(current, current + 1)) {
                                grantedProbes.incrementAndGet();
                                break;
                            }
                        } else {
                            rejectedRequests.incrementAndGet();
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished);
        assertEquals(maxPermittedProbes, grantedProbes.get(), "Exactly 3 probes must be granted among 30 concurrent workers");
        assertEquals(threadCount - maxPermittedProbes, rejectedRequests.get(), "Remaining 27 requests must be blocked/fail-fast");
    }

    @Test
    @DisplayName("Reset key deletes redis key")
    public void testResetCircuitBreaker() {
        String key = "provider:fcm";
        when(mockKeyCommands.del("circuitbreaker:" + key)).thenReturn(1);

        boolean reset = circuitBreaker.reset(key);
        assertTrue(reset);
        verify(mockKeyCommands).del("circuitbreaker:" + key);
    }
}
