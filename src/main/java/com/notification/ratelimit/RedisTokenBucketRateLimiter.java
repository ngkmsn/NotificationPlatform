package com.notification.ratelimit;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RedisTokenBucketRateLimiter implements TokenBucketRateLimiter {

    private static final Logger LOG = Logger.getLogger(RedisTokenBucketRateLimiter.class);
    public static final String KEY_PREFIX = "ratelimit:tokenbucket:";

    /**
     * Lua script to atomically perform token bucket refill and consumption in Redis.
     *
     * KEYS[1]: Redis hash key for the token bucket
     * ARGV[1]: capacity (double/long)
     * ARGV[2]: refill_rate_per_sec (double)
     * ARGV[3]: requested_tokens (double/long)
     * ARGV[4]: current_time_ms (long)
     * ARGV[5]: ttl_sec (long)
     *
     * Returns:
     *   [1]: allowed (1 = true, 0 = false)
     *   [2]: remaining_tokens (string)
     *   [3]: retry_after_ms (integer)
     */
    private static final String TOKEN_BUCKET_LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local requested = tonumber(ARGV[3])
            local now_ms = tonumber(ARGV[4])
            local ttl_sec = tonumber(ARGV[5])

            local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local current_tokens = capacity
            local last_refill_ms = now_ms

            if data[1] and data[2] then
                local stored_tokens = tonumber(data[1])
                local stored_last_ms = tonumber(data[2])
                local elapsed_ms = math.max(0, now_ms - stored_last_ms)
                local tokens_to_add = (elapsed_ms / 1000.0) * refill_rate
                current_tokens = math.min(capacity, stored_tokens + tokens_to_add)
            else
                current_tokens = capacity
            end

            local allowed = 0
            local remaining = current_tokens
            local retry_after_ms = 0

            if current_tokens >= requested then
                allowed = 1
                remaining = current_tokens - requested
                redis.call('HMSET', key, 'tokens', tostring(remaining), 'last_refill_ms', tostring(now_ms))
                redis.call('EXPIRE', key, ttl_sec)
            else
                allowed = 0
                remaining = current_tokens
                local missing = requested - current_tokens
                retry_after_ms = math.ceil((missing / refill_rate) * 1000.0)
                redis.call('HMSET', key, 'tokens', tostring(current_tokens), 'last_refill_ms', tostring(now_ms))
                redis.call('EXPIRE', key, ttl_sec)
            end

            return { allowed, tostring(remaining), retry_after_ms }
            """;

    private final RedisDataSource redisDataSource;
    private final KeyCommands<String> keyCommands;

    @Inject
    public RedisTokenBucketRateLimiter(RedisDataSource redisDataSource) {
        this.redisDataSource = redisDataSource;
        this.keyCommands = redisDataSource.key();
    }

    @Override
    public TokenBucketResult tryConsume(String key, TokenBucketConfig config) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Rate limit key must not be null or blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("TokenBucketConfig must not be null");
        }

        String redisKey = KEY_PREFIX + key.trim();
        long nowMs = System.currentTimeMillis();
        long ttlSec = config.getTtl() != null ? Math.max(1, config.getTtl().toSeconds()) : 3600L;

        try {
            Response response = (Response) redisDataSource.execute(
                    "EVAL",
                    TOKEN_BUCKET_LUA_SCRIPT,
                    "1",
                    redisKey,
                    String.valueOf(config.getCapacity()),
                    String.valueOf(config.getRefillRatePerSecond()),
                    String.valueOf(config.getRequestedTokens()),
                    String.valueOf(nowMs),
                    String.valueOf(ttlSec)
            );

            return parseLuaResult(response, config);

        } catch (Exception e) {
            LOG.errorf(e, "Error executing Token Bucket Lua script for key [%s]", key);
            return TokenBucketResult.denied(0, 1000L, config.getCapacity(), config.getRefillRatePerSecond());
        }
    }

    private TokenBucketResult parseLuaResult(Response response, TokenBucketConfig config) {
        if (response == null) {
            return TokenBucketResult.denied(0, 1000L, config.getCapacity(), config.getRefillRatePerSecond());
        }

        try {
            if (response.size() < 3) {
                LOG.warnf("Unexpected Lua result structure from Redis: %s", response);
                return TokenBucketResult.denied(0, 1000L, config.getCapacity(), config.getRefillRatePerSecond());
            }

            long allowedVal = response.get(0).toLong();
            boolean allowed = (allowedVal == 1L);

            double remaining = Double.parseDouble(response.get(1).toString());
            long retryAfterMs = response.get(2).toLong();

            if (allowed) {
                return TokenBucketResult.allowed(remaining, config.getCapacity(), config.getRefillRatePerSecond());
            } else {
                return TokenBucketResult.denied(remaining, retryAfterMs, config.getCapacity(), config.getRefillRatePerSecond());
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse Lua response from Redis: %s", response);
            return TokenBucketResult.denied(0, 1000L, config.getCapacity(), config.getRefillRatePerSecond());
        }
    }


    @Override
    public boolean reset(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String redisKey = KEY_PREFIX + key.trim();
        try {
            int del = keyCommands.del(redisKey);
            return del > 0;
        } catch (Exception e) {
            LOG.warnf("Failed to reset token bucket key [%s]: %s", key, e.getMessage());
            return false;
        }
    }
}
