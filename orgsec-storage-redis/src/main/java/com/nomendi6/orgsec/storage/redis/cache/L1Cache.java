package com.nomendi6.orgsec.storage.redis.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L1 (in-memory) cache with LRU eviction policy.
 * <p>
 * This cache stores frequently accessed objects in memory for fast retrieval (< 0.1ms latency).
 * When the cache reaches its maximum size, the least recently used entry is automatically evicted.
 * </p>
 * <p>
 * Thread-safe implementation using synchronized LinkedHashMap with access-order.
 * </p>
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
public class L1Cache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(L1Cache.class);

    private final Map<K, V> cache;
    private final int maxSize;

    // Statistics
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);

    /**
     * Constructs a new L1 cache with the specified maximum size.
     *
     * @param maxSize the maximum number of entries to store
     */
    public L1Cache(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("Max size must be at least 1");
        }
        this.maxSize = maxSize;

        // LinkedHashMap with access-order (true) for LRU
        this.cache = Collections.synchronizedMap(
            new LinkedHashMap<K, V>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    boolean shouldRemove = size() > maxSize;
                    if (shouldRemove) {
                        evictionCount.incrementAndGet();
                        log.trace("LRU eviction: key={}", eldest.getKey());
                    }
                    return shouldRemove;
                }
            }
        );
    }

    /**
     * Retrieves a value from the cache.
     *
     * @param key the key
     * @return the cached value, or null if not found
     */
    public V get(K key) {
        if (key == null) {
            return null;
        }

        V value = cache.get(key);

        if (value != null) {
            hitCount.incrementAndGet();
            log.trace("L1 cache hit: key={}", key);
        } else {
            missCount.incrementAndGet();
            log.trace("L1 cache miss: key={}", key);
        }

        return value;
    }

    /**
     * Stores a value in the cache.
     *
     * @param key   the key
     * @param value the value
     */
    public void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }

        cache.put(key, value);
        log.trace("L1 cache put: key={}", key);
    }

    /**
     * Removes a value from the cache.
     *
     * @param key the key
     */
    public void evict(K key) {
        if (key == null) {
            return;
        }

        V removed = cache.remove(key);
        if (removed != null) {
            log.trace("L1 cache evict: key={}", key);
        }
    }

    /**
     * Alias for evict - removes a value from the cache.
     *
     * @param key the key
     */
    public void invalidate(K key) {
        evict(key);
    }

    /**
     * Clears all entries from the cache.
     */
    public void clear() {
        int size = cache.size();
        cache.clear();
        log.debug("L1 cache cleared: {} entries removed", size);
    }

    /**
     * Returns the current number of entries in the cache.
     *
     * @return the cache size
     */
    public int size() {
        return cache.size();
    }

    /**
     * Returns the maximum size of the cache.
     *
     * @return the maximum size
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Returns cache statistics.
     *
     * @return the cache stats
     */
    public CacheStats getStats() {
        return new CacheStats(
            size(),
            hitCount.get(),
            missCount.get(),
            evictionCount.get()
        );
    }

    /**
     * Cache statistics holder.
     */
    public static class CacheStats {
        private final int size;
        private final long hitCount;
        private final long missCount;
        private final long evictionCount;

        public CacheStats(int size, long hitCount, long missCount, long evictionCount) {
            this.size = size;
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.evictionCount = evictionCount;
        }

        public int getSize() {
            return size;
        }

        public long getHitCount() {
            return hitCount;
        }

        public long getMissCount() {
            return missCount;
        }

        public long getEvictionCount() {
            return evictionCount;
        }

        /**
         * Calculates the hit rate as a percentage.
         *
         * @return hit rate (0-100), or 0 if no requests
         */
        public double getHitRate() {
            long total = hitCount + missCount;
            if (total == 0) {
                return 0.0;
            }
            return (hitCount * 100.0) / total;
        }

        @Override
        public String toString() {
            return String.format("CacheStats{size=%d, hits=%d, misses=%d, evictions=%d, hitRate=%.2f%%}",
                size, hitCount, missCount, evictionCount, getHitRate());
        }
    }
}
