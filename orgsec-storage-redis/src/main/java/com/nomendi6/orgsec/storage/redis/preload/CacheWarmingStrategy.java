package com.nomendi6.orgsec.storage.redis.preload;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Strategy interface for cache warming operations.
 * <p>
 * Different implementations provide different approaches to loading
 * data into the cache (eager, lazy, progressive).
 * </p>
 */
public interface CacheWarmingStrategy {

    /**
     * Executes the cache warming using the provided data loader and store.
     *
     * @param loader the data loader that retrieves data from source
     * @param store  the consumer that stores data in cache
     * @param <K>    the key type
     * @param <V>    the value type
     * @return the number of items warmed
     */
    <K, V> int warm(CacheWarmer.DataLoader<K, V> loader, Consumer<Map<K, V>> store);

    /**
     * Returns the name of this strategy.
     *
     * @return strategy name
     */
    String getName();
}
