package com.nomendi6.orgsec.storage.redis.preload;

import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WarmingStrategyTest {

    @Nested
    class EagerWarmingStrategyTests {

        private final EagerWarmingStrategy strategy = new EagerWarmingStrategy();

        @Test
        void shouldReturnEagerName() {
            assertThat(strategy.getName()).isEqualTo("eager");
        }

        @Test
        void shouldReturnZeroForNullLoader() {
            int count = strategy.warm(null, data -> {});
            assertThat(count).isZero();
        }

        @Test
        void shouldReturnZeroForNullStore() {
            int count = strategy.warm(() -> Map.of(1L, "value"), null);
            assertThat(count).isZero();
        }

        @Test
        void shouldLoadAllDataAtOnce() {
            Map<Long, String> testData = Map.of(
                    1L, "value1",
                    2L, "value2",
                    3L, "value3"
            );

            AtomicInteger storeCallCount = new AtomicInteger(0);
            AtomicInteger storedItems = new AtomicInteger(0);

            int count = strategy.warm(
                    () -> testData,
                    data -> {
                        storeCallCount.incrementAndGet();
                        storedItems.set(data.size());
                    }
            );

            assertThat(count).isEqualTo(3);
            assertThat(storeCallCount.get()).isEqualTo(1); // Single batch
            assertThat(storedItems.get()).isEqualTo(3);
        }

        @Test
        void shouldReturnZeroForEmptyData() {
            int count = strategy.warm(Map::of, data -> {});
            assertThat(count).isZero();
        }

        @Test
        void shouldReturnZeroForNullData() {
            int count = strategy.warm(() -> null, data -> {});
            assertThat(count).isZero();
        }

        @Test
        void shouldHandleLoaderException() {
            int count = strategy.warm(
                    () -> { throw new RuntimeException("Load error"); },
                    data -> {}
            );
            assertThat(count).isZero();
        }
    }

    @Nested
    class ProgressiveWarmingStrategyTests {

        @Test
        void shouldReturnProgressiveName() {
            RedisStorageProperties.PreloadConfig config = createProgressiveConfig();
            ProgressiveWarmingStrategy strategy = new ProgressiveWarmingStrategy(config);

            assertThat(strategy.getName()).isEqualTo("progressive");
        }

        @Test
        void shouldReturnZeroForNullLoader() {
            RedisStorageProperties.PreloadConfig config = createProgressiveConfig();
            ProgressiveWarmingStrategy strategy = new ProgressiveWarmingStrategy(config);

            int count = strategy.warm(null, data -> {});
            assertThat(count).isZero();
        }

        @Test
        void shouldReturnZeroForNullStore() {
            RedisStorageProperties.PreloadConfig config = createProgressiveConfig();
            ProgressiveWarmingStrategy strategy = new ProgressiveWarmingStrategy(config);

            int count = strategy.warm(() -> Map.of(1L, "value"), null);
            assertThat(count).isZero();
        }

        @Test
        void shouldLoadDataInBatches() {
            RedisStorageProperties.PreloadConfig config = createProgressiveConfig();
            config.setBatchSize(2);
            config.setBatchDelayMs(0); // No delay for test
            ProgressiveWarmingStrategy strategy = new ProgressiveWarmingStrategy(config);

            Map<Long, String> testData = new HashMap<>();
            for (long i = 1; i <= 5; i++) {
                testData.put(i, "value" + i);
            }

            List<Integer> batchSizes = new ArrayList<>();

            int count = strategy.warm(
                    () -> testData,
                    data -> batchSizes.add(data.size())
            );

            assertThat(count).isEqualTo(5);
            // Should have 3 batches: 2, 2, 1
            assertThat(batchSizes).hasSize(3);
            assertThat(batchSizes).containsExactly(2, 2, 1);
        }

        @Test
        void shouldHandleSingleBatch() {
            RedisStorageProperties.PreloadConfig config = createProgressiveConfig();
            config.setBatchSize(10);
            config.setBatchDelayMs(0);
            ProgressiveWarmingStrategy strategy = new ProgressiveWarmingStrategy(config);

            Map<Long, String> testData = Map.of(
                    1L, "value1",
                    2L, "value2"
            );

            AtomicInteger batchCount = new AtomicInteger(0);

            int count = strategy.warm(
                    () -> testData,
                    data -> batchCount.incrementAndGet()
            );

            assertThat(count).isEqualTo(2);
            assertThat(batchCount.get()).isEqualTo(1);
        }

        @Test
        void shouldReturnZeroForEmptyData() {
            RedisStorageProperties.PreloadConfig config = createProgressiveConfig();
            ProgressiveWarmingStrategy strategy = new ProgressiveWarmingStrategy(config);

            int count = strategy.warm(Map::of, data -> {});
            assertThat(count).isZero();
        }

        @Test
        void shouldHandleLoaderException() {
            RedisStorageProperties.PreloadConfig config = createProgressiveConfig();
            ProgressiveWarmingStrategy strategy = new ProgressiveWarmingStrategy(config);

            int count = strategy.warm(
                    () -> { throw new RuntimeException("Load error"); },
                    data -> {}
            );
            assertThat(count).isZero();
        }

        @Test
        void shouldHandleAsyncMode() throws InterruptedException {
            RedisStorageProperties.PreloadConfig config = createProgressiveConfig();
            config.setBatchSize(2);
            config.setBatchDelayMs(0);
            config.setAsync(true);
            config.setParallelism(2);
            ProgressiveWarmingStrategy strategy = new ProgressiveWarmingStrategy(config);

            Map<Long, String> testData = new HashMap<>();
            for (long i = 1; i <= 4; i++) {
                testData.put(i, "value" + i);
            }

            AtomicInteger storedCount = new AtomicInteger(0);

            int count = strategy.warm(
                    () -> testData,
                    data -> storedCount.addAndGet(data.size())
            );

            // Wait for async completion
            Thread.sleep(100);

            assertThat(count).isEqualTo(4);
            assertThat(storedCount.get()).isEqualTo(4);
        }

        private RedisStorageProperties.PreloadConfig createProgressiveConfig() {
            RedisStorageProperties.PreloadConfig config = new RedisStorageProperties.PreloadConfig();
            config.setMode("progressive");
            config.setBatchSize(100);
            config.setBatchDelayMs(0);
            config.setAsync(false);
            config.setParallelism(1);
            return config;
        }
    }
}
