package com.nomendi6.orgsec.storage.redis.cache;

import com.nomendi6.orgsec.storage.redis.resilience.RedisCircuitBreakerService;
import com.nomendi6.orgsec.storage.redis.resilience.RedisConnectionException;
import com.nomendi6.orgsec.storage.redis.serialization.JsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * L2 (Redis) cache wrapper.
 * <p>
 * Provides a simplified API over RedisTemplate for caching operations.
 * Handles serialization/deserialization and TTL management.
 * </p>
 *
 * @param <T> the type of cached values
 */
public class L2RedisCache<T> {

    private static final Logger log = LoggerFactory.getLogger(L2RedisCache.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final JsonSerializer<T> serializer;
    private final CacheKeyBuilder keyBuilder;
    private final RedisCircuitBreakerService circuitBreakerService;

    /**
     * Constructs a new L2 Redis cache with circuit breaker support.
     *
     * @param redisTemplate         the Redis template
     * @param serializer            the JSON serializer
     * @param keyBuilder            the cache key builder
     * @param circuitBreakerService the circuit breaker service
     */
    public L2RedisCache(
        RedisTemplate<String, String> redisTemplate,
        JsonSerializer<T> serializer,
        CacheKeyBuilder keyBuilder,
        RedisCircuitBreakerService circuitBreakerService
    ) {
        this.redisTemplate = redisTemplate;
        this.serializer = serializer;
        this.keyBuilder = keyBuilder;
        this.circuitBreakerService = circuitBreakerService;
    }

    /**
     * Constructs a new L2 Redis cache without circuit breaker.
     *
     * @param redisTemplate the Redis template
     * @param serializer    the JSON serializer
     * @param keyBuilder    the cache key builder
     * @deprecated Use {@link #L2RedisCache(RedisTemplate, JsonSerializer, CacheKeyBuilder, RedisCircuitBreakerService)} instead
     */
    @Deprecated
    public L2RedisCache(
        RedisTemplate<String, String> redisTemplate,
        JsonSerializer<T> serializer,
        CacheKeyBuilder keyBuilder
    ) {
        this(redisTemplate, serializer, keyBuilder, null);
    }

    /**
     * Retrieves a value from Redis cache.
     *
     * @param key the cache key
     * @return the cached value, or null if not found
     * @throws RedisConnectionException if Redis connection fails
     */
    public T get(String key) {
        if (key == null) {
            return null;
        }

        if (circuitBreakerService != null) {
            return circuitBreakerService.executeWithFallback(() -> doGet(key), null);
        }
        return doGet(key);
    }

    private T doGet(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.trace("L2 cache miss: key={}", key);
                return null;
            }

            T value = serializer.deserialize(json);
            log.trace("L2 cache hit: key={}", key);
            return value;

        } catch (io.lettuce.core.RedisException e) {
            log.warn("Redis connection failed on get: key={}", key, e);
            throw new RedisConnectionException("Failed to get from Redis", e);
        } catch (Exception e) {
            log.error("Unexpected error on Redis get: key={}", key, e);
            return null;
        }
    }

    /**
     * Stores a value in Redis cache with TTL.
     *
     * @param key        the cache key
     * @param value      the value to cache
     * @param ttlSeconds the time-to-live in seconds
     * @throws RedisConnectionException if Redis connection fails
     */
    public void set(String key, T value, long ttlSeconds) {
        if (key == null || value == null) {
            return;
        }

        if (circuitBreakerService != null) {
            circuitBreakerService.executeWithFallback(() -> {
                doSet(key, value, ttlSeconds);
            });
        } else {
            doSet(key, value, ttlSeconds);
        }
    }

    private void doSet(String key, T value, long ttlSeconds) {
        try {
            String json = serializer.serialize(value);
            redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
            log.trace("L2 cache set: key={}, ttl={}s", key, ttlSeconds);

        } catch (io.lettuce.core.RedisException e) {
            log.warn("Redis connection failed on set: key={}", key, e);
            throw new RedisConnectionException("Failed to set in Redis", e);
        } catch (Exception e) {
            log.error("Unexpected error on Redis set: key={}", key, e);
            // Don't throw - caching is optional
        }
    }

    // ==================== BATCH OPERATIONS ====================

    /**
     * Retrieves multiple values from Redis cache in a single operation (MGET).
     * <p>
     * This is significantly faster than multiple individual GET calls
     * as it reduces network round-trips.
     * </p>
     *
     * @param keys the collection of cache keys
     * @return map of key to value (missing keys are not included)
     */
    public Map<String, T> multiGet(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }

        if (circuitBreakerService != null) {
            return circuitBreakerService.executeWithFallback(() -> doMultiGet(keys), Map.of());
        }
        return doMultiGet(keys);
    }

    private Map<String, T> doMultiGet(Collection<String> keys) {
        try {
            List<String> keyList = keys instanceof List ? (List<String>) keys : List.copyOf(keys);
            List<String> jsonValues = redisTemplate.opsForValue().multiGet(keyList);

            if (jsonValues == null) {
                return Map.of();
            }

            Map<String, T> result = new HashMap<>();
            int hits = 0;
            int misses = 0;

            for (int i = 0; i < keyList.size(); i++) {
                String json = jsonValues.get(i);
                if (json != null) {
                    try {
                        T value = serializer.deserialize(json);
                        result.put(keyList.get(i), value);
                        hits++;
                    } catch (Exception e) {
                        log.warn("Failed to deserialize value for key: {}", keyList.get(i), e);
                        misses++;
                    }
                } else {
                    misses++;
                }
            }

            log.debug("L2 cache multiGet: {} keys requested, {} hits, {} misses",
                    keyList.size(), hits, misses);
            return result;

        } catch (io.lettuce.core.RedisException e) {
            log.warn("Redis connection failed on multiGet: {} keys", keys.size(), e);
            throw new RedisConnectionException("Failed to multiGet from Redis", e);
        } catch (Exception e) {
            log.error("Unexpected error on Redis multiGet: {} keys", keys.size(), e);
            return Map.of();
        }
    }

    /**
     * Stores multiple values in Redis cache in a single operation (MSET).
     * <p>
     * This is significantly faster than multiple individual SET calls.
     * Note: MSET doesn't support per-key TTL, so all values get the same TTL.
     * </p>
     *
     * @param entries    map of key to value
     * @param ttlSeconds the time-to-live in seconds (applied to all keys)
     */
    public void multiSet(Map<String, T> entries, long ttlSeconds) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        if (circuitBreakerService != null) {
            circuitBreakerService.executeWithFallback(() -> {
                doMultiSet(entries, ttlSeconds);
            });
        } else {
            doMultiSet(entries, ttlSeconds);
        }
    }

    private void doMultiSet(Map<String, T> entries, long ttlSeconds) {
        try {
            // Serialize all values
            Map<String, String> serializedEntries = new HashMap<>();
            for (Map.Entry<String, T> entry : entries.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    String json = serializer.serialize(entry.getValue());
                    serializedEntries.put(entry.getKey(), json);
                }
            }

            if (serializedEntries.isEmpty()) {
                return;
            }

            // Use pipeline for MSET + EXPIRE
            redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                // Set all values
                for (Map.Entry<String, String> entry : serializedEntries.entrySet()) {
                    connection.stringCommands().set(
                            entry.getKey().getBytes(),
                            entry.getValue().getBytes()
                    );
                }
                // Set TTL for all keys
                for (String key : serializedEntries.keySet()) {
                    connection.keyCommands().expire(key.getBytes(), ttlSeconds);
                }
                return null;
            });

            log.debug("L2 cache multiSet: {} entries stored with TTL={}s",
                    serializedEntries.size(), ttlSeconds);

        } catch (io.lettuce.core.RedisException e) {
            log.warn("Redis connection failed on multiSet: {} entries", entries.size(), e);
            throw new RedisConnectionException("Failed to multiSet in Redis", e);
        } catch (Exception e) {
            log.error("Unexpected error on Redis multiSet: {} entries", entries.size(), e);
            // Don't throw - caching is optional
        }
    }

    /**
     * Deletes multiple keys from Redis cache in a single operation.
     *
     * @param keys the collection of keys to delete
     * @return the number of keys that were deleted
     */
    public long multiDelete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }

        try {
            Long deleted = redisTemplate.delete(keys);
            long result = deleted != null ? deleted : 0;
            log.debug("L2 cache multiDelete: {} keys deleted", result);
            return result;

        } catch (io.lettuce.core.RedisException e) {
            log.warn("Redis connection failed on multiDelete: {} keys", keys.size(), e);
            throw new RedisConnectionException("Failed to multiDelete from Redis", e);
        } catch (Exception e) {
            log.error("Unexpected error on Redis multiDelete: {} keys", keys.size(), e);
            return 0;
        }
    }

    /**
     * Deletes a value from Redis cache.
     *
     * @param key the cache key
     * @return true if key was deleted, false otherwise
     */
    public boolean delete(String key) {
        if (key == null) {
            return false;
        }

        try {
            Boolean deleted = redisTemplate.delete(key);
            boolean result = Boolean.TRUE.equals(deleted);
            if (result) {
                log.trace("L2 cache delete: key={}", key);
            }
            return result;

        } catch (io.lettuce.core.RedisException e) {
            log.warn("Redis connection failed on delete: key={}", key, e);
            throw new RedisConnectionException("Failed to delete from Redis", e);
        } catch (Exception e) {
            log.error("Unexpected error on Redis delete: key={}", key, e);
            return false;
        }
    }

    /**
     * Checks if a key exists in Redis cache.
     *
     * @param key the cache key
     * @return true if key exists, false otherwise
     */
    public boolean exists(String key) {
        if (key == null) {
            return false;
        }

        try {
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);

        } catch (io.lettuce.core.RedisException e) {
            log.warn("Redis connection failed on exists: key={}", key, e);
            throw new RedisConnectionException("Failed to check existence in Redis", e);
        } catch (Exception e) {
            log.error("Unexpected error on Redis exists: key={}", key, e);
            return false;
        }
    }

    /**
     * Retrieves all keys matching a pattern.
     * <p>
     * WARNING: This uses the KEYS command which can be slow on large datasets.
     * Use only for admin operations (warmup, debug), NOT in request path.
     * </p>
     *
     * @param pattern the key pattern (e.g., "orgsec:p:*")
     * @return set of matching keys
     */
    public Set<String> keys(String pattern) {
        if (pattern == null) {
            return Set.of();
        }

        try {
            Set<String> keys = redisTemplate.keys(pattern);
            return keys != null ? keys : Set.of();

        } catch (io.lettuce.core.RedisException e) {
            log.warn("Redis connection failed on keys: pattern={}", pattern, e);
            throw new RedisConnectionException("Failed to get keys from Redis", e);
        } catch (Exception e) {
            log.error("Unexpected error on Redis keys: pattern={}", pattern, e);
            return Set.of();
        }
    }

    /**
     * Returns the approximate number of keys matching a pattern.
     *
     * @param pattern the key pattern
     * @return the number of matching keys
     */
    public long keyCount(String pattern) {
        try {
            Set<String> matchingKeys = keys(pattern);
            return matchingKeys.size();
        } catch (Exception e) {
            log.error("Failed to count keys for pattern: {}", pattern, e);
            return 0;
        }
    }

    /**
     * Clears all keys matching the OrgSec pattern.
     * <p>
     * WARNING: This deletes all OrgSec cache entries. Use with caution.
     * </p>
     */
    public void clear() {
        try {
            String pattern = keyBuilder.allKeysPattern();
            Set<String> keysToDelete = keys(pattern);

            if (!keysToDelete.isEmpty()) {
                Long deleted = redisTemplate.delete(keysToDelete);
                log.info("L2 cache cleared: {} keys deleted", deleted);
            }

        } catch (Exception e) {
            log.error("Failed to clear L2 cache", e);
            throw new RedisConnectionException("Failed to clear Redis cache", e);
        }
    }

    /**
     * Returns the Redis template.
     *
     * @return the Redis template
     */
    public RedisTemplate<String, String> getRedisTemplate() {
        return redisTemplate;
    }

    /**
     * Returns the JSON serializer.
     *
     * @return the serializer
     */
    public JsonSerializer<T> getSerializer() {
        return serializer;
    }

    /**
     * Returns the cache key builder.
     *
     * @return the key builder
     */
    public CacheKeyBuilder getKeyBuilder() {
        return keyBuilder;
    }
}
