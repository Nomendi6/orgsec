package com.nomendi6.orgsec.storage.redis.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for L1Cache.
 */
class L1CacheTest {

    private L1Cache<Long, String> cache;

    @BeforeEach
    void setUp() {
        cache = new L1Cache<>(3); // Max size 3 for easy testing
    }

    @Test
    void constructor_invalidMaxSize_throwsException() {
        // When/Then
        assertThatThrownBy(() -> new L1Cache<Long, String>(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Max size must be at least 1");

        assertThatThrownBy(() -> new L1Cache<Long, String>(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void put_andGet_returnsValue() {
        // Given
        Long key = 1L;
        String value = "test-value";

        // When
        cache.put(key, value);
        String retrieved = cache.get(key);

        // Then
        assertThat(retrieved).isEqualTo(value);
    }

    @Test
    void get_nonExistentKey_returnsNull() {
        // When
        String value = cache.get(999L);

        // Then
        assertThat(value).isNull();
    }

    @Test
    void get_nullKey_returnsNull() {
        // When
        String value = cache.get(null);

        // Then
        assertThat(value).isNull();
    }

    @Test
    void put_nullKey_doesNothing() {
        // Given
        int initialSize = cache.size();

        // When
        cache.put(null, "value");

        // Then
        assertThat(cache.size()).isEqualTo(initialSize);
    }

    @Test
    void put_nullValue_doesNothing() {
        // Given
        int initialSize = cache.size();

        // When
        cache.put(1L, null);

        // Then
        assertThat(cache.size()).isEqualTo(initialSize);
    }

    @Test
    void put_exceedsMaxSize_evictsEldest() {
        // Given - max size is 3
        cache.put(1L, "value1");
        cache.put(2L, "value2");
        cache.put(3L, "value3");

        // When - add 4th element, should evict 1st
        cache.put(4L, "value4");

        // Then
        assertThat(cache.size()).isEqualTo(3);
        assertThat(cache.get(1L)).isNull(); // Evicted
        assertThat(cache.get(2L)).isEqualTo("value2");
        assertThat(cache.get(3L)).isEqualTo("value3");
        assertThat(cache.get(4L)).isEqualTo("value4");
    }

    @Test
    void put_lruEviction_evictsLeastRecentlyUsed() {
        // Given - fill cache
        cache.put(1L, "value1");
        cache.put(2L, "value2");
        cache.put(3L, "value3");

        // When - access key 1 (make it recently used)
        cache.get(1L);

        // When - add 4th element, should evict key 2 (least recently used)
        cache.put(4L, "value4");

        // Then
        assertThat(cache.get(1L)).isEqualTo("value1"); // Still there
        assertThat(cache.get(2L)).isNull(); // Evicted
        assertThat(cache.get(3L)).isEqualTo("value3");
        assertThat(cache.get(4L)).isEqualTo("value4");
    }

    @Test
    void evict_existingKey_removesValue() {
        // Given
        cache.put(1L, "value1");

        // When
        cache.evict(1L);

        // Then
        assertThat(cache.get(1L)).isNull();
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void evict_nonExistentKey_doesNothing() {
        // Given
        cache.put(1L, "value1");
        int initialSize = cache.size();

        // When
        cache.evict(999L);

        // Then
        assertThat(cache.size()).isEqualTo(initialSize);
    }

    @Test
    void evict_nullKey_doesNothing() {
        // Given
        cache.put(1L, "value1");
        int initialSize = cache.size();

        // When
        cache.evict(null);

        // Then
        assertThat(cache.size()).isEqualTo(initialSize);
    }

    @Test
    void clear_removesAllEntries() {
        // Given
        cache.put(1L, "value1");
        cache.put(2L, "value2");
        cache.put(3L, "value3");

        // When
        cache.clear();

        // Then
        assertThat(cache.size()).isEqualTo(0);
        assertThat(cache.get(1L)).isNull();
        assertThat(cache.get(2L)).isNull();
        assertThat(cache.get(3L)).isNull();
    }

    @Test
    void size_returnsCorrectCount() {
        // Given
        assertThat(cache.size()).isEqualTo(0);

        // When
        cache.put(1L, "value1");
        assertThat(cache.size()).isEqualTo(1);

        cache.put(2L, "value2");
        assertThat(cache.size()).isEqualTo(2);

        cache.evict(1L);
        assertThat(cache.size()).isEqualTo(1);

        cache.clear();
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test
    void getMaxSize_returnsConfiguredMaxSize() {
        // When/Then
        assertThat(cache.getMaxSize()).isEqualTo(3);
    }

    @Test
    void getStats_cacheHit_incrementsHitCount() {
        // Given
        cache.put(1L, "value1");

        // When
        cache.get(1L); // Hit
        cache.get(1L); // Hit

        // Then
        L1Cache.CacheStats stats = cache.getStats();
        assertThat(stats.getHitCount()).isEqualTo(2);
        assertThat(stats.getMissCount()).isEqualTo(0);
    }

    @Test
    void getStats_cacheMiss_incrementsMissCount() {
        // When
        cache.get(999L); // Miss
        cache.get(888L); // Miss

        // Then
        L1Cache.CacheStats stats = cache.getStats();
        assertThat(stats.getHitCount()).isEqualTo(0);
        assertThat(stats.getMissCount()).isEqualTo(2);
    }

    @Test
    void getStats_eviction_incrementsEvictionCount() {
        // Given - fill cache to max
        cache.put(1L, "value1");
        cache.put(2L, "value2");
        cache.put(3L, "value3");

        // When - trigger eviction
        cache.put(4L, "value4");
        cache.put(5L, "value5");

        // Then
        L1Cache.CacheStats stats = cache.getStats();
        assertThat(stats.getEvictionCount()).isEqualTo(2);
    }

    @Test
    void cacheStats_hitRate_calculatedCorrectly() {
        // Given
        cache.put(1L, "value1");

        // When
        cache.get(1L); // Hit
        cache.get(1L); // Hit
        cache.get(999L); // Miss

        // Then
        L1Cache.CacheStats stats = cache.getStats();
        assertThat(stats.getHitRate()).isCloseTo(66.67, within(0.01)); // 2/3 = 66.67%
    }

    @Test
    void cacheStats_noRequests_hitRateIsZero() {
        // When
        L1Cache.CacheStats stats = cache.getStats();

        // Then
        assertThat(stats.getHitRate()).isEqualTo(0.0);
    }

    @Test
    void cacheStats_toString_containsAllInfo() {
        // Given
        cache.put(1L, "value1");
        cache.get(1L); // Hit

        // When
        L1Cache.CacheStats stats = cache.getStats();
        String toString = stats.toString();

        // Then
        assertThat(toString).contains("size=1");
        assertThat(toString).contains("hits=1");
        assertThat(toString).contains("misses=0");
        assertThat(toString).contains("hitRate");
    }

    @Test
    void concurrentAccess_threadSafe() throws InterruptedException {
        // Given
        L1Cache<Integer, String> concurrentCache = new L1Cache<>(100);
        int threadCount = 10;
        int operationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When - multiple threads put/get concurrently
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        int key = threadId * operationsPerThread + j;
                        concurrentCache.put(key, "value-" + key);
                        concurrentCache.get(key);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then - no exceptions, cache is consistent
        assertThat(concurrentCache.size()).isLessThanOrEqualTo(100);
        L1Cache.CacheStats stats = concurrentCache.getStats();
        assertThat(stats.getHitCount()).isGreaterThan(0);
    }
}
