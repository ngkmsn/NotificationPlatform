package com.notification.circuitbreaker;

import com.notification.provider.ProviderSendResult;
import com.notification.retry.RetryClassifier;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;

@ApplicationScoped
public class RedisCircuitBreaker implements CircuitBreaker {

    private static final Logger LOG = Logger.getLogger(RedisCircuitBreaker.class);
    public static final String KEY_PREFIX = "circuitbreaker:";

    /**
     * Lua script to atomically check and acquire permission from Redis.
     *
     * KEYS[1]: Circuit breaker hash key
     * ARGV[1]: current timestamp in millis (now_ms)
     * ARGV[2]: cooldown duration in millis (cooldown_ms)
     * ARGV[3]: permitted probe calls in half-open state (half_open_permitted_probes)
     * ARGV[4]: key TTL in seconds (ttl_sec)
     *
     * Returns multi-bulk array:
     *   [1]: allowed (1 = true, 0 = false)
     *   [2]: state string ("CLOSED", "OPEN", "HALF_OPEN")
     *   [3]: retry_after_ms (long)
     *   [4]: is_probe (1 = true, 0 = false)
     */
    private static final String ACQUIRE_PERMISSION_LUA = """
            local key = KEYS[1]
            local now_ms = tonumber(ARGV[1])
            local cooldown_ms = tonumber(ARGV[2])
            local half_open_permitted_probes = tonumber(ARGV[3])
            local ttl_sec = tonumber(ARGV[4])

            local data = redis.call('HMGET', key, 'state', 'open_until_ms', 'half_open_probes_active')
            local state = data[1] or "CLOSED"
            local open_until_ms = tonumber(data[2] or "0")
            local half_open_probes_active = tonumber(data[3] or "0")

            if state == "OPEN" then
                if now_ms >= open_until_ms then
                    -- Cooldown expired: atomically transition to HALF_OPEN and grant 1st probe
                    state = "HALF_OPEN"
                    half_open_probes_active = 1
                    redis.call('HMSET', key,
                        'state', 'HALF_OPEN',
                        'half_open_probes_active', '1',
                        'half_open_successes', '0',
                        'half_open_failures', '0'
                    )
                    redis.call('EXPIRE', key, ttl_sec)
                    return { 1, "HALF_OPEN", 0, 1 }
                else
                    local retry_after = math.max(100, open_until_ms - now_ms)
                    return { 0, "OPEN", retry_after, 0 }
                end
            elseif state == "HALF_OPEN" then
                if half_open_probes_active < half_open_permitted_probes then
                    -- Atomically grant probe slot
                    half_open_probes_active = half_open_probes_active + 1
                    redis.call('HSET', key, 'half_open_probes_active', tostring(half_open_probes_active))
                    redis.call('EXPIRE', key, ttl_sec)
                    return { 1, "HALF_OPEN", 0, 1 }
                else
                    -- Probe quota exhausted, waiting for active probes to report back
                    local retry_after = math.max(1000, math.floor(cooldown_ms / 2))
                    return { 0, "HALF_OPEN", retry_after, 0 }
                end
            else
                -- CLOSED state
                return { 1, "CLOSED", 0, 0 }
            end
            """;

    /**
     * Lua script to atomically record attempt outcome and transition states.
     *
     * KEYS[1]: Circuit breaker hash key
     * ARGV[1]: is_success (1/0)
     * ARGV[2]: is_failure (1/0)
     * ARGV[3]: is_fatal (1/0)
     * ARGV[4]: now_ms (long)
     * ARGV[5]: cooldown_ms (long)
     * ARGV[6]: min_calls (int)
     * ARGV[7]: failure_rate_threshold (double, e.g. 50.0)
     * ARGV[8]: window_duration_ms (long)
     * ARGV[9]: half_open_success_threshold (int)
     * ARGV[10]: ttl_sec (long)
     */
    private static final String RECORD_RESULT_LUA = """
            local key = KEYS[1]
            local is_success = tonumber(ARGV[1])
            local is_failure = tonumber(ARGV[2])
            local is_fatal = tonumber(ARGV[3])
            local now_ms = tonumber(ARGV[4])
            local cooldown_ms = tonumber(ARGV[5])
            local min_calls = tonumber(ARGV[6])
            local failure_rate_threshold = tonumber(ARGV[7])
            local window_duration_ms = tonumber(ARGV[8])
            local half_open_success_threshold = tonumber(ARGV[9])
            local ttl_sec = tonumber(ARGV[10])

            local data = redis.call('HMGET', key,
                'state',
                'open_until_ms',
                'half_open_successes',
                'half_open_failures',
                'window_total',
                'window_failures',
                'window_start_ms'
            )

            local state = data[1] or "CLOSED"
            local open_until_ms = tonumber(data[2] or "0")
            local half_open_successes = tonumber(data[3] or "0")
            local half_open_failures = tonumber(data[4] or "0")
            local window_total = tonumber(data[5] or "0")
            local window_failures = tonumber(data[6] or "0")
            local window_start_ms = tonumber(data[7] or "0")

            if state == "HALF_OPEN" then
                if is_failure == 1 then
                    -- Probe failed: reopen circuit immediately
                    local new_open_until = now_ms + cooldown_ms
                    redis.call('HMSET', key,
                        'state', 'OPEN',
                        'open_until_ms', tostring(new_open_until),
                        'half_open_probes_active', '0',
                        'half_open_successes', '0',
                        'half_open_failures', '0',
                        'window_total', '0',
                        'window_failures', '0',
                        'window_start_ms', tostring(now_ms)
                    )
                    redis.call('EXPIRE', key, ttl_sec)
                    return "OPEN"
                else
                    -- Success or fatal (downstream reachable)
                    half_open_successes = half_open_successes + 1
                    if half_open_successes >= half_open_success_threshold then
                        -- Enough successful probes: recover to CLOSED
                        redis.call('HMSET', key,
                            'state', 'CLOSED',
                            'open_until_ms', '0',
                            'half_open_probes_active', '0',
                            'half_open_successes', '0',
                            'half_open_failures', '0',
                            'window_total', '0',
                            'window_failures', '0',
                            'window_start_ms', tostring(now_ms)
                        )
                        redis.call('EXPIRE', key, ttl_sec)
                        return "CLOSED"
                    else
                        redis.call('HSET', key, 'half_open_successes', tostring(half_open_successes))
                        redis.call('EXPIRE', key, ttl_sec)
                        return "HALF_OPEN"
                    end
                end
            elseif state == "CLOSED" then
                -- Fatal client errors (400, 404, invalid argument) do not count against provider health
                if is_fatal == 1 then
                    return "CLOSED"
                end

                -- Sliding window check
                if window_start_ms == 0 or (now_ms - window_start_ms > window_duration_ms) then
                    window_total = 0
                    window_failures = 0
                    window_start_ms = now_ms
                end

                window_total = window_total + 1
                if is_failure == 1 then
                    window_failures = window_failures + 1
                end

                if window_total >= min_calls then
                    local failure_rate = (window_failures * 100.0) / window_total
                    if failure_rate >= failure_rate_threshold then
                        -- Trip Circuit Breaker to OPEN
                        local new_open_until = now_ms + cooldown_ms
                        redis.call('HMSET', key,
                            'state', 'OPEN',
                            'open_until_ms', tostring(new_open_until),
                            'half_open_probes_active', '0',
                            'half_open_successes', '0',
                            'half_open_failures', '0',
                            'window_total', tostring(window_total),
                            'window_failures', tostring(window_failures),
                            'window_start_ms', tostring(window_start_ms)
                        )
                        redis.call('EXPIRE', key, ttl_sec)
                        return "OPEN"
                    end
                end

                redis.call('HMSET', key,
                    'state', 'CLOSED',
                    'window_total', tostring(window_total),
                    'window_failures', tostring(window_failures),
                    'window_start_ms', tostring(window_start_ms)
                )
                redis.call('EXPIRE', key, ttl_sec)
                return "CLOSED"
            else
                -- Already OPEN
                return "OPEN"
            end
            """;

    private final RedisDataSource redisDataSource;
    private final KeyCommands<String> keyCommands;
    private final RetryClassifier retryClassifier;

    private final CircuitBreakerConfig defaultConfig;

    @Inject
    public RedisCircuitBreaker(
            RedisDataSource redisDataSource,
            RetryClassifier retryClassifier,
            @ConfigProperty(name = "circuitbreaker.failure-rate-threshold", defaultValue = "50.0") double failureRateThreshold,
            @ConfigProperty(name = "circuitbreaker.minimum-number-of-calls", defaultValue = "5") int minimumNumberOfCalls,
            @ConfigProperty(name = "circuitbreaker.sliding-window-duration-ms", defaultValue = "60000") long slidingWindowDurationMs,
            @ConfigProperty(name = "circuitbreaker.wait-duration-in-open-state-ms", defaultValue = "30000") long waitDurationInOpenStateMs,
            @ConfigProperty(name = "circuitbreaker.permitted-number-of-calls-in-half-open-state", defaultValue = "3") int permittedNumberOfCallsInHalfOpenState,
            @ConfigProperty(name = "circuitbreaker.half-open-success-threshold", defaultValue = "2") int halfOpenSuccessThreshold) {
        this.redisDataSource = redisDataSource;
        this.keyCommands = redisDataSource != null ? redisDataSource.key() : null;
        this.retryClassifier = retryClassifier != null ? retryClassifier : new RetryClassifier();
        this.defaultConfig = new CircuitBreakerConfig(
                failureRateThreshold,
                minimumNumberOfCalls,
                Duration.ofMillis(slidingWindowDurationMs),
                Duration.ofMillis(waitDurationInOpenStateMs),
                permittedNumberOfCallsInHalfOpenState,
                halfOpenSuccessThreshold,
                Duration.ofHours(24)
        );
    }

    public RedisCircuitBreaker(RedisDataSource redisDataSource, RetryClassifier retryClassifier, CircuitBreakerConfig defaultConfig) {
        this.redisDataSource = redisDataSource;
        this.keyCommands = redisDataSource != null ? redisDataSource.key() : null;
        this.retryClassifier = retryClassifier != null ? retryClassifier : new RetryClassifier();
        this.defaultConfig = defaultConfig != null ? defaultConfig : CircuitBreakerConfig.ofDefaults();
    }

    @Override
    public CircuitBreakerResult acquirePermission(String key) {
        return acquirePermission(key, defaultConfig);
    }

    @Override
    public CircuitBreakerResult acquirePermission(String key, CircuitBreakerConfig config) {
        if (key == null || key.isBlank()) {
            return CircuitBreakerResult.allowed(CircuitBreakerState.CLOSED, 0, false);
        }

        CircuitBreakerConfig effectiveConfig = config != null ? config : defaultConfig;
        String redisKey = KEY_PREFIX + key.trim();
        long nowMs = System.currentTimeMillis();
        long cooldownMs = effectiveConfig.getWaitDurationInOpenState().toMillis();
        int halfOpenProbes = effectiveConfig.getPermittedNumberOfCallsInHalfOpenState();
        long ttlSec = effectiveConfig.getTtl() != null ? effectiveConfig.getTtl().toSeconds() : 86400L;

        if (redisDataSource == null) {
            return CircuitBreakerResult.allowed(CircuitBreakerState.CLOSED, 0, false);
        }

        try {
            Response response = (Response) redisDataSource.execute(
                    "EVAL",
                    ACQUIRE_PERMISSION_LUA,
                    "1",
                    redisKey,
                    String.valueOf(nowMs),
                    String.valueOf(cooldownMs),
                    String.valueOf(halfOpenProbes),
                    String.valueOf(ttlSec)
            );

            return parseAcquireResponse(response);

        } catch (Exception e) {
            LOG.warnf("Redis error during Circuit Breaker acquirePermission for key [%s]: %s. Failing open (allowing request).",
                    key, e.getMessage());
            return CircuitBreakerResult.allowed(CircuitBreakerState.CLOSED, 0, false);
        }
    }

    @Override
    public void recordResult(String key, ProviderSendResult result) {
        recordResult(key, result, defaultConfig);
    }

    @Override
    public void recordResult(String key, ProviderSendResult result, CircuitBreakerConfig config) {
        if (key == null || key.isBlank() || result == null) {
            return;
        }

        boolean isSuccess = result.isSuccess();
        boolean isFailure = false;
        boolean isFatal = false;

        if (!isSuccess) {
            if (retryClassifier.isRetryable(result)) {
                isFailure = true;
            } else {
                isFatal = true;
            }
        }

        executeRecordResult(key, isSuccess, isFailure, isFatal, config != null ? config : defaultConfig);
    }

    @Override
    public void recordException(String key, Throwable throwable) {
        recordException(key, throwable, defaultConfig);
    }

    @Override
    public void recordException(String key, Throwable throwable, CircuitBreakerConfig config) {
        if (key == null || key.isBlank() || throwable == null) {
            return;
        }

        boolean isFailure = retryClassifier.isRetryableException(throwable);
        boolean isFatal = !isFailure;

        executeRecordResult(key, false, isFailure, isFatal, config != null ? config : defaultConfig);
    }

    private void executeRecordResult(String key, boolean isSuccess, boolean isFailure, boolean isFatal, CircuitBreakerConfig config) {
        if (redisDataSource == null) {
            return;
        }

        String redisKey = KEY_PREFIX + key.trim();
        long nowMs = System.currentTimeMillis();
        long cooldownMs = config.getWaitDurationInOpenState().toMillis();
        int minCalls = config.getMinimumNumberOfCalls();
        double failureRateThreshold = config.getFailureRateThreshold();
        long windowDurationMs = config.getSlidingWindowDuration().toMillis();
        int halfOpenSuccessThreshold = config.getHalfOpenSuccessThreshold();
        long ttlSec = config.getTtl() != null ? config.getTtl().toSeconds() : 86400L;

        try {
            redisDataSource.execute(
                    "EVAL",
                    RECORD_RESULT_LUA,
                    "1",
                    redisKey,
                    isSuccess ? "1" : "0",
                    isFailure ? "1" : "0",
                    isFatal ? "1" : "0",
                    String.valueOf(nowMs),
                    String.valueOf(cooldownMs),
                    String.valueOf(minCalls),
                    String.valueOf(failureRateThreshold),
                    String.valueOf(windowDurationMs),
                    String.valueOf(halfOpenSuccessThreshold),
                    String.valueOf(ttlSec)
            );
        } catch (Exception e) {
            LOG.warnf("Redis error during Circuit Breaker recordResult for key [%s]: %s", key, e.getMessage());
        }
    }

    @Override
    public CircuitBreakerState getState(String key) {
        if (key == null || key.isBlank() || redisDataSource == null) {
            return CircuitBreakerState.CLOSED;
        }

        String redisKey = KEY_PREFIX + key.trim();
        try {
            Response response = (Response) redisDataSource.execute("HGET", redisKey, "state");
            if (response == null || response.toString() == null) {
                return CircuitBreakerState.CLOSED;
            }
            String stateStr = response.toString().replace("\"", "").trim();
            if (stateStr.isEmpty()) {
                return CircuitBreakerState.CLOSED;
            }
            return CircuitBreakerState.valueOf(stateStr.toUpperCase());
        } catch (Exception e) {
            LOG.warnf("Redis error checking Circuit Breaker state for key [%s]: %s", key, e.getMessage());
            return CircuitBreakerState.CLOSED;
        }
    }

    @Override
    public boolean reset(String key) {
        if (key == null || key.isBlank() || keyCommands == null) {
            return false;
        }
        String redisKey = KEY_PREFIX + key.trim();
        try {
            return keyCommands.del(redisKey) > 0;
        } catch (Exception e) {
            LOG.warnf("Failed to reset Circuit Breaker key [%s]: %s", key, e.getMessage());
            return false;
        }
    }

    private CircuitBreakerResult parseAcquireResponse(Response response) {
        if (response == null || response.size() < 4) {
            return CircuitBreakerResult.allowed(CircuitBreakerState.CLOSED, 0, false);
        }

        long allowedVal = response.get(0).toLong();
        boolean allowed = (allowedVal == 1L);

        String stateStr = response.get(1).toString().replace("\"", "").trim();
        CircuitBreakerState state;
        try {
            state = CircuitBreakerState.valueOf(stateStr);
        } catch (Exception e) {
            state = CircuitBreakerState.CLOSED;
        }

        long retryAfterMs = response.get(2).toLong();
        long probeVal = response.get(3).toLong();
        boolean probe = (probeVal == 1L);

        if (allowed) {
            return CircuitBreakerResult.allowed(state, retryAfterMs, probe);
        } else {
            return CircuitBreakerResult.denied(state, retryAfterMs);
        }
    }

    public CircuitBreakerConfig getDefaultConfig() {
        return defaultConfig;
    }

    public RetryClassifier getRetryClassifier() {
        return retryClassifier;
    }
}
