package com.notification.ratelimit;

import java.time.Duration;
import java.util.Optional;

public interface RateLimitStateStore {

    /**
     * Checks if the shared state store is reachable and healthy.
     */
    boolean ping();

    /**
     * Retrieves the stored value for a given key.
     */
    Optional<String> get(String key);

    /**
     * Stores a key-value pair with a time-to-live (TTL).
     */
    void set(String key, String value, Duration ttl);

    /**
     * Sets a key-value pair with TTL only if the key does not already exist (SET NX).
     */
    boolean setIfAbsent(String key, String value, Duration ttl);

    /**
     * Atomically increments the numeric value of a key and sets a TTL if newly created.
     */
    long increment(String key, long amount, Duration ttl);

    /**
     * Deletes a key from the shared state store.
     */
    boolean delete(String key);
}
