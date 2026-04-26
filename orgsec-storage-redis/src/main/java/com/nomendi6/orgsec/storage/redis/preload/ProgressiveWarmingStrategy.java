package com.nomendi6.orgsec.storage.redis.preload;

import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Progressive cache warming strategy.
 * <p>
 * Loads data in smaller batches with configurable delays between batches.
 * This approach is gentler on the system and reduces memory pressure during startup.
 * Suitable for large datasets where eager loading would be too aggressive.
 * </p>
 */
public class ProgressiveWarmingStrategy implements CacheWarmingStrategy {

    private static final Logger log = LoggerFactory.getLogger(ProgressiveWarmingStrategy.class);

    private final int batchSize;
    private final long batchDelayMs;
    private final int parallelism;
    private final boolean async;

    /**
     * Creates a progressive warming strategy with the given configuration.
     *
     * @param config the preload configuration
     */
    public ProgressiveWarmingStrategy(RedisStorageProperties.PreloadConfig config) {
        this.batchSize = config.getBatchSize();
        this.batchDelayMs = config.getBatchDelayMs();
        this.parallelism = config.getParallelism();
        this.async = config.isAsync();
    }

    @Override
    public <K, V> int warm(CacheWarmer.DataLoader<K, V> loader, Consumer<Map<K, V>> store) {
        if (loader == null || store == null) {
            log.debug("Loader or store not configured, skipping warmup");
            return 0;
        }

        try {
            Map<K, V> allData = loader.loadAll();
            if (allData == null || allData.isEmpty()) {
                log.debug("No data to warm up");
                return 0;
            }

            if (async) {
                return warmAsync(allData, store);
            } else {
                return warmSync(allData, store);
            }

        } catch (Exception e) {
            log.error("Progressive warmup failed", e);
            return 0;
        }
    }

    private <K, V> int warmSync(Map<K, V> allData, Consumer<Map<K, V>> store) {
        int totalItems = allData.size();
        int batchCount = 0;
        int itemsProcessed = 0;

        Iterator<Map.Entry<K, V>> iterator = allData.entrySet().iterator();

        while (iterator.hasNext()) {
            Map<K, V> batch = new HashMap<>();

            // Collect batch
            while (iterator.hasNext() && batch.size() < batchSize) {
                Map.Entry<K, V> entry = iterator.next();
                batch.put(entry.getKey(), entry.getValue());
            }

            // Store batch
            if (!batch.isEmpty()) {
                store.accept(batch);
                itemsProcessed += batch.size();
                batchCount++;

                log.trace("Progressive warmup batch {}: {} items (total: {}/{})",
                        batchCount, batch.size(), itemsProcessed, totalItems);

                // Delay between batches (except for last batch)
                if (iterator.hasNext() && batchDelayMs > 0) {
                    try {
                        Thread.sleep(batchDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Progressive warmup interrupted");
                        break;
                    }
                }
            }
        }

        log.debug("Progressive warmup completed: {} items in {} batches", itemsProcessed, batchCount);
        return itemsProcessed;
    }

    private <K, V> int warmAsync(Map<K, V> allData, Consumer<Map<K, V>> store) {
        int totalItems = allData.size();
        AtomicInteger itemsProcessed = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);

        try {
            // Split data into batches
            Iterator<Map.Entry<K, V>> iterator = allData.entrySet().iterator();
            int batchNum = 0;

            while (iterator.hasNext()) {
                Map<K, V> batch = new HashMap<>();

                while (iterator.hasNext() && batch.size() < batchSize) {
                    Map.Entry<K, V> entry = iterator.next();
                    batch.put(entry.getKey(), entry.getValue());
                }

                if (!batch.isEmpty()) {
                    final int currentBatch = ++batchNum;
                    final Map<K, V> batchToProcess = batch;

                    executor.submit(() -> {
                        try {
                            store.accept(batchToProcess);
                            int processed = itemsProcessed.addAndGet(batchToProcess.size());
                            log.trace("Async batch {} completed: {} items (total: {}/{})",
                                    currentBatch, batchToProcess.size(), processed, totalItems);

                            if (batchDelayMs > 0) {
                                Thread.sleep(batchDelayMs);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            log.error("Failed to process async batch {}", currentBatch, e);
                        }
                    });
                }
            }

            // Wait for all batches to complete
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                log.warn("Async warmup did not complete within timeout");
                executor.shutdownNow();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Async warmup interrupted");
            executor.shutdownNow();
        }

        log.debug("Async progressive warmup completed: {} items", itemsProcessed.get());
        return itemsProcessed.get();
    }

    @Override
    public String getName() {
        return "progressive";
    }
}
