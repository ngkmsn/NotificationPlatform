package com.notification.ratelimit;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.string.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class RedisRateLimitStateStore implements RateLimitStateStore {

    private static final Logger LOG = Logger.getLogger(RedisRateLimitStateStore.class);

    private final RedisDataSource redisDataSource;
    private final ValueCommands<String, String> valueCommands;
    private final KeyCommands<String> keyCommands;

    @Inject
    public RedisRateLimitStateStore(RedisDataSource redisDataSource) {
        this.redisDataSource = redisDataSource;
        this.valueCommands = redisDataSource.value(String.class);
        this.keyCommands = redisDataSource.key();
    }

    @Override
    public boolean ping() {
        try {
            redisDataSource.execute("PING");
            return true;
        } catch (Exception e) {
            LOG.warnf("Redis ping failed: %s", e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<String> get(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            String val = valueCommands.get(key);
            return Optional.ofNullable(val);
        } catch (Exception e) {
            LOG.warnf("Failed to get key [%s] from Redis: %s", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        try {
            if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
                valueCommands.set(key, value, new SetArgs().px(ttl.toMillis()));
            } else {
                valueCommands.set(key, value);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to set key [%s] in Redis: %s", key, e.getMessage());
        }
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        if (key == null || key.isBlank() || value == null) {
            return false;
        }
        try {
            if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
                valueCommands.set(key, value, new SetArgs().nx().px(ttl.toMillis()));
                // If key already existed, set with nx won't overwrite; check if value was set
                String current = valueCommands.get(key);
                return value.equals(current);
            } else {
                return valueCommands.setnx(key, value);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to setnx key [%s] in Redis: %s", key, e.getMessage());
            return false;
        }
    }

    @Override
    public long increment(String key, long amount, Duration ttl) {
        if (key == null || key.isBlank()) {
            return -1L;
        }
        try {
            long result = valueCommands.incrby(key, amount);
            if (ttl != null && !ttl.isNegative() && !ttl.isZero()) {
                keyCommands.pexpire(key, ttl.toMillis());
            }
            return result;
        } catch (Exception e) {
            LOG.warnf("Failed to increment key [%s] in Redis: %s", key, e.getMessage());
            return -1L;
        }
    }

    @Override
    public boolean delete(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            int deleted = keyCommands.del(key);
            return deleted > 0;
        } catch (Exception e) {
            LOG.warnf("Failed to delete key [%s] from Redis: %s", key, e.getMessage());
            return false;
        }
    }
}
