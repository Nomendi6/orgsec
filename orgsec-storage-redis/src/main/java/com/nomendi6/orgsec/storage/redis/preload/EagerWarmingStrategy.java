package com.nomendi6.orgsec.storage.redis.preload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Eager cache warming strategy.
 * <p>
 * Loads all data immediately in a single batch operation.
 * This is the simplest and fastest approach for small to medium datasets.
 * </p>
 */
public class EagerWarmingStrategy implements CacheWarmingStrategy {

    private static final Logger log = LoggerFactory.getLogger(EagerWarmingStrategy.class);

    @Override
    public <K, V> int warm(CacheWarmer.DataLoader<K, V> loader, Consumer<Map<K, V>> store) {
        if (loader == null || store == null) {
            log.debug("Loader or store not configured, skipping warmup");
            return 0;
        }

        try {
            Map<K, V> data = loader.loadAll();
            if (data == null || data.isEmpty()) {
                log.debug("No data to warm up");
                return 0;
            }

            store.accept(data);
            log.debug("Eager warmup completed: {} items loaded", data.size());
            return data.size();

        } catch (Exception e) {
            log.error("Eager warmup failed", e);
            return 0;
        }
    }

    @Override
    public String getName() {
        return "eager";
    }
}
