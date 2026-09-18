package com.notification.ratelimit;

public interface TokenBucketRateLimiter {

    /**
     * Attempts to consume tokens from the bucket for the given key using custom configuration.
     *
     * @param key unique bucket identifier (e.g. provider name, user ID, channel)
     * @param config configuration containing capacity, refill rate, requested tokens, ttl
     * @return TokenBucketResult containing whether consumption was allowed and wait time if denied
     */
    TokenBucketResult tryConsume(String key, TokenBucketConfig config);

    /**
     * Attempts to consume requested tokens with specified capacity and refill rate.
     */
    default TokenBucketResult tryConsume(String key, long requestedTokens, long capacity, double refillRatePerSecond) {
        return tryConsume(key, TokenBucketConfig.of(capacity, refillRatePerSecond, requestedTokens));
    }

    /**
     * Attempts to consume 1 token with specified capacity and refill rate.
     */
    default TokenBucketResult tryConsume(String key, long capacity, double refillRatePerSecond) {
        return tryConsume(key, TokenBucketConfig.of(capacity, refillRatePerSecond, 1L));
    }

    /**
     * Resets or clears the bucket state for the given key.
     *
     * @param key unique bucket identifier
     * @return true if bucket was found and deleted, false otherwise
     */
    boolean reset(String key);
}
