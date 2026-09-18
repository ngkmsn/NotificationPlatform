package com.notification.ratelimit;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.vertx.mutiny.redis.client.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

public class TokenBucketRateLimiterTest {

    private RedisDataSource mockRedisDataSource;
    private KeyCommands<String> mockKeyCommands;
    private RedisTokenBucketRateLimiter rateLimiter;

    @BeforeEach
    public void setup() {
        mockRedisDataSource = mock(RedisDataSource.class);
        mockKeyCommands = mock(KeyCommands.class);
        when(mockRedisDataSource.key()).thenReturn(mockKeyCommands);

        rateLimiter = new RedisTokenBucketRateLimiter(mockRedisDataSource);
    }

    @Test
    public void testTokenBucketConfigValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketConfig(0, 10.0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketConfig(10, 0.0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketConfig(10, 10.0, 0, null));

        TokenBucketConfig config = TokenBucketConfig.of(100, 20.0);
        assertEquals(100, config.getCapacity());
        assertEquals(20.0, config.getRefillRatePerSecond());
        assertEquals(1, config.getRequestedTokens());
        assertNotNull(config.getTtl());
    }

    @Test
    public void testTokenBucketResultProperties() {
        TokenBucketResult allowed = TokenBucketResult.allowed(9.0, 10, 2.0);
        assertTrue(allowed.isAllowed());
        assertEquals(9.0, allowed.getRemainingTokens());
        assertEquals(0L, allowed.getRetryAfterMs());
        assertEquals(Duration.ZERO, allowed.getWaitTime());

        TokenBucketResult denied = TokenBucketResult.denied(0.0, 500L, 10, 2.0);
        assertFalse(denied.isAllowed());
        assertEquals(0.0, denied.getRemainingTokens());
        assertEquals(500L, denied.getRetryAfterMs());
        assertEquals(Duration.ofMillis(500), denied.getWaitTime());
    }

    @Test
    public void testNormalConsumptionAllowed() {
        String key = "test:normal:" + UUID.randomUUID();
        TokenBucketConfig config = TokenBucketConfig.of(10, 2.0, 1);

        // Mock Lua script returning: [allowed=1, remaining=9.0, retryAfterMs=0]
        Response mockResponse = mock(Response.class);
        when(mockResponse.size()).thenReturn(3);

        Response elem0 = mock(Response.class);
        when(elem0.toLong()).thenReturn(1L);
        when(mockResponse.get(0)).thenReturn(elem0);

        Response elem1 = mock(Response.class);
        when(elem1.toString()).thenReturn("9.0");
        when(mockResponse.get(1)).thenReturn(elem1);

        Response elem2 = mock(Response.class);
        when(elem2.toLong()).thenReturn(0L);
        when(mockResponse.get(2)).thenReturn(elem2);

        when(mockRedisDataSource.execute(eq("EVAL"), any(), eq("1"), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockResponse);

        TokenBucketResult result = rateLimiter.tryConsume(key, config);

        assertTrue(result.isAllowed());
        assertEquals(9.0, result.getRemainingTokens());
        assertEquals(0L, result.getRetryAfterMs());
    }

    @Test
    public void testExhaustedBucketDeniedWithRetryAfter() {
        String key = "test:exhausted:" + UUID.randomUUID();
        TokenBucketConfig config = TokenBucketConfig.of(5, 1.0, 1);

        // Mock Lua script returning: [allowed=0, remaining=0.0, retryAfterMs=1000]
        Response mockResponse = mock(Response.class);
        when(mockResponse.size()).thenReturn(3);

        Response elem0 = mock(Response.class);
        when(elem0.toLong()).thenReturn(0L);
        when(mockResponse.get(0)).thenReturn(elem0);

        Response elem1 = mock(Response.class);
        when(elem1.toString()).thenReturn("0.0");
        when(mockResponse.get(1)).thenReturn(elem1);

        Response elem2 = mock(Response.class);
        when(elem2.toLong()).thenReturn(1000L);
        when(mockResponse.get(2)).thenReturn(elem2);

        when(mockRedisDataSource.execute(eq("EVAL"), any(), eq("1"), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockResponse);

        TokenBucketResult result = rateLimiter.tryConsume(key, config);

        assertFalse(result.isAllowed(), "Should be denied when bucket has 0 tokens");
        assertEquals(0.0, result.getRemainingTokens());
        assertEquals(1000L, result.getRetryAfterMs(), "Should return time required to refill 1 token");
        assertEquals(Duration.ofSeconds(1), result.getWaitTime());
    }

    @Test
    public void testSimulatedRefillOverTime() throws Exception {
        // Test token refill calculation logic matching the Lua script
        long capacity = 10;
        double refillRate = 5.0; // 5 tokens per second

        long now = System.currentTimeMillis();
        long lastRefill = now - 1000; // 1 second elapsed
        double elapsedSeconds = (now - lastRefill) / 1000.0;
        double tokensToAdd = elapsedSeconds * refillRate;
        double storedTokens = 0.0;
        double currentTokens = Math.min(capacity, storedTokens + tokensToAdd);

        assertEquals(5.0, currentTokens, 0.01, "After 1 second with rate 5/s, should have refilled 5 tokens");
    }

    @Test
    public void testSimulatedConcurrentConsumptionAtomicIntegrity() throws Exception {
        // Simulate atomic Redis token bucket with concurrent threads
        int threadCount = 20;
        int requestsPerThread = 10;
        int totalRequests = threadCount * requestsPerThread; // 200 requests

        long capacity = 50; // Only 50 tokens available, 0 refill rate for instant burst test
        double refillRate = 0.0;

        // In-memory atomic token bucket simulation demonstrating atomic Lua semantics
        AtomicReference<Double> tokensInBucket = new AtomicReference<>((double) capacity);
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger deniedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Atomic CAS loop simulating Redis single-threaded Lua script atomicity
                    while (true) {
                        Double current = tokensInBucket.get();
                        if (current >= 1.0) {
                            if (tokensInBucket.compareAndSet(current, current - 1.0)) {
                                allowedCount.incrementAndGet();
                                break;
                            }
                        } else {
                            deniedCount.incrementAndGet();
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

        startLatch.countDown(); // Release all threads at once
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All concurrent requests should finish within timeout");
        assertEquals(capacity, allowedCount.get(), "Exactly capacity (50) requests should be allowed");
        assertEquals(totalRequests - capacity, deniedCount.get(), "Remaining (150) requests must be denied");
        assertEquals(0.0, tokensInBucket.get(), "Tokens in bucket should reach exactly 0.0");
    }

    @Test
    public void testResetKey() {
        String key = "test:reset:123";
        when(mockKeyCommands.del("ratelimit:tokenbucket:" + key)).thenReturn(1);

        boolean reset = rateLimiter.reset(key);
        assertTrue(reset);
        verify(mockKeyCommands).del("ratelimit:tokenbucket:" + key);
    }
}
