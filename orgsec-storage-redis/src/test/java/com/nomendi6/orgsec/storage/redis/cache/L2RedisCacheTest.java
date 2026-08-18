package com.nomendi6.orgsec.storage.redis.cache;

import com.nomendi6.orgsec.storage.redis.resilience.RedisCircuitBreakerService;
import com.nomendi6.orgsec.storage.redis.resilience.RedisConnectionException;
import com.nomendi6.orgsec.storage.redis.serialization.JsonSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class L2RedisCacheTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JsonSerializer<TestEntity> serializer;

    @Mock
    private CacheKeyBuilder keyBuilder;

    @Mock
    private RedisCircuitBreakerService circuitBreakerService;

    private L2RedisCache<TestEntity> cache;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(keyBuilder.allKeysPattern()).thenReturn("orgsec:*");
        when(keyBuilder.legacyDataKeyPatterns()).thenReturn(List.of("orgsec:p:*"));
        when(keyBuilder.isLegacyDataKey(anyString())).thenReturn(true);

        cache = new L2RedisCache<>(redisTemplate, serializer, keyBuilder, circuitBreakerService);
    }

    // Test entity
    static class TestEntity {
        Long id;
        String name;

        TestEntity() {}

        TestEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Nested
    class GetOperationTests {

        @Test
        void shouldReturnNullForNullKey() {
            assertThat(cache.get(null)).isNull();
            verify(valueOperations, never()).get(anyString());
        }

        @Test
        void shouldReturnNullWhenKeyNotFound() {
            when(circuitBreakerService.executeWithFallback(any(), any()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<?> supplier = inv.getArgument(0);
                        return supplier.get();
                    });
            when(valueOperations.get("test-key")).thenReturn(null);

            TestEntity result = cache.get("test-key");

            assertThat(result).isNull();
        }

        @Test
        void shouldReturnDeserializedValue() {
            String json = "{\"id\":1,\"name\":\"test\"}";
            TestEntity entity = new TestEntity(1L, "test");

            when(circuitBreakerService.executeWithFallback(any(), any()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<?> supplier = inv.getArgument(0);
                        return supplier.get();
                    });
            when(valueOperations.get("test-key")).thenReturn(json);
            when(serializer.deserialize(json)).thenReturn(entity);

            TestEntity result = cache.get("test-key");

            assertThat(result).isEqualTo(entity);
        }

        @Test
        void shouldReturnFallbackOnCircuitBreakerOpen() {
            when(circuitBreakerService.executeWithFallback(any(), any()))
                    .thenReturn(null);

            TestEntity result = cache.get("test-key");

            assertThat(result).isNull();
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldThrowRedisConnectionExceptionWhenGetFailsWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            when(valueOperations.get("test-key")).thenThrow(new io.lettuce.core.RedisException("down"));

            assertThatThrownBy(() -> cacheWithoutCB.get("test-key"))
                    .isInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("Failed to get from Redis");
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldReturnNullWhenUnexpectedGetErrorOccursWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            when(valueOperations.get("test-key")).thenThrow(new IllegalStateException("unexpected"));

            assertThat(cacheWithoutCB.get("test-key")).isNull();
        }
    }

    @Nested
    class SetOperationTests {

        @Test
        void shouldNotSetNullKey() {
            cache.set(null, new TestEntity(1L, "test"), 3600);
            verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
        }

        @Test
        void shouldNotSetNullValue() {
            cache.set("key", null, 3600);
            verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
        }

        @Test
        void shouldSerializeAndStore() {
            TestEntity entity = new TestEntity(1L, "test");
            String json = "{\"id\":1,\"name\":\"test\"}";

            when(serializer.serialize(entity)).thenReturn(json);
            doAnswer(inv -> {
                Runnable runnable = inv.getArgument(0);
                runnable.run();
                return null;
            }).when(circuitBreakerService).executeWithFallback(any(Runnable.class));

            cache.set("test-key", entity, 3600);

            verify(serializer).serialize(entity);
            verify(valueOperations).set(eq("test-key"), eq(json), eq(3600L), any());
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldThrowRedisConnectionExceptionWhenSetFailsWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            TestEntity entity = new TestEntity(1L, "test");
            when(serializer.serialize(entity)).thenReturn("{}");
            doThrow(new io.lettuce.core.RedisException("down"))
                    .when(valueOperations).set(eq("test-key"), eq("{}"), eq(3600L), any());

            assertThatThrownBy(() -> cacheWithoutCB.set("test-key", entity, 3600))
                    .isInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("Failed to set in Redis");
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldIgnoreUnexpectedSetErrorWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            TestEntity entity = new TestEntity(1L, "test");
            when(serializer.serialize(entity)).thenThrow(new IllegalStateException("bad serialization"));

            cacheWithoutCB.set("test-key", entity, 3600);

            verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
        }
    }

    @Nested
    class MultiGetOperationTests {

        @Test
        void shouldReturnEmptyMapForNullKeys() {
            Map<String, TestEntity> result = cache.multiGet(null);
            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnEmptyMapForEmptyKeys() {
            Map<String, TestEntity> result = cache.multiGet(List.of());
            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnMultipleValues() {
            List<String> keys = Arrays.asList("key1", "key2", "key3");
            List<String> jsonValues = Arrays.asList(
                    "{\"id\":1,\"name\":\"entity1\"}",
                    null,
                    "{\"id\":3,\"name\":\"entity3\"}"
            );

            TestEntity entity1 = new TestEntity(1L, "entity1");
            TestEntity entity3 = new TestEntity(3L, "entity3");

            when(circuitBreakerService.executeWithFallback(any(), any()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<?> supplier = inv.getArgument(0);
                        return supplier.get();
                    });
            when(valueOperations.multiGet(keys)).thenReturn(jsonValues);
            when(serializer.deserialize("{\"id\":1,\"name\":\"entity1\"}")).thenReturn(entity1);
            when(serializer.deserialize("{\"id\":3,\"name\":\"entity3\"}")).thenReturn(entity3);

            Map<String, TestEntity> result = cache.multiGet(keys);

            assertThat(result).hasSize(2);
            assertThat(result.get("key1")).isEqualTo(entity1);
            assertThat(result.get("key3")).isEqualTo(entity3);
            assertThat(result.containsKey("key2")).isFalse();
        }

        @Test
        void shouldReturnEmptyMapOnCircuitBreakerOpen() {
            when(circuitBreakerService.executeWithFallback(any(), any()))
                    .thenReturn(Map.of());

            Map<String, TestEntity> result = cache.multiGet(List.of("key1", "key2"));

            assertThat(result).isEmpty();
        }

        @Test
        void shouldHandleDeserializationErrors() {
            List<String> keys = Arrays.asList("key1", "key2");
            List<String> jsonValues = Arrays.asList(
                    "{\"id\":1,\"name\":\"entity1\"}",
                    "invalid-json"
            );

            TestEntity entity1 = new TestEntity(1L, "entity1");

            when(circuitBreakerService.executeWithFallback(any(), any()))
                    .thenAnswer(inv -> {
                        java.util.function.Supplier<?> supplier = inv.getArgument(0);
                        return supplier.get();
                    });
            when(valueOperations.multiGet(keys)).thenReturn(jsonValues);
            when(serializer.deserialize("{\"id\":1,\"name\":\"entity1\"}")).thenReturn(entity1);
            when(serializer.deserialize("invalid-json")).thenThrow(new RuntimeException("Parse error"));

            Map<String, TestEntity> result = cache.multiGet(keys);

            assertThat(result).hasSize(1);
            assertThat(result.get("key1")).isEqualTo(entity1);
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldReturnEmptyMapWhenRedisReturnsNullMultiGetWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            when(valueOperations.multiGet(List.of("key1"))).thenReturn(null);

            assertThat(cacheWithoutCB.multiGet(List.of("key1"))).isEmpty();
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldThrowRedisConnectionExceptionWhenMultiGetFailsWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            when(valueOperations.multiGet(List.of("key1"))).thenThrow(new io.lettuce.core.RedisException("down"));

            assertThatThrownBy(() -> cacheWithoutCB.multiGet(List.of("key1")))
                    .isInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("Failed to multiGet from Redis");
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldReturnEmptyMapWhenUnexpectedMultiGetErrorOccursWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            when(valueOperations.multiGet(List.of("key1"))).thenThrow(new IllegalStateException("unexpected"));

            assertThat(cacheWithoutCB.multiGet(List.of("key1"))).isEmpty();
        }
    }

    @Nested
    class MultiSetOperationTests {

        @Test
        void shouldNotSetNullEntries() {
            cache.multiSet(null, 3600);
            verify(redisTemplate, never()).executePipelined(any(org.springframework.data.redis.core.RedisCallback.class));
        }

        @Test
        void shouldNotSetEmptyEntries() {
            cache.multiSet(Map.of(), 3600);
            verify(redisTemplate, never()).executePipelined(any(org.springframework.data.redis.core.RedisCallback.class));
        }

        @Test
        void shouldSerializeAllEntries() {
            TestEntity entity1 = new TestEntity(1L, "entity1");
            TestEntity entity2 = new TestEntity(2L, "entity2");
            Map<String, TestEntity> entries = Map.of(
                    "key1", entity1,
                    "key2", entity2
            );

            when(serializer.serialize(entity1)).thenReturn("{\"id\":1}");
            when(serializer.serialize(entity2)).thenReturn("{\"id\":2}");

            doAnswer(inv -> {
                Runnable runnable = inv.getArgument(0);
                runnable.run();
                return null;
            }).when(circuitBreakerService).executeWithFallback(any(Runnable.class));

            cache.multiSet(entries, 3600);

            verify(serializer).serialize(entity1);
            verify(serializer).serialize(entity2);
        }

        @Test
        void shouldSkipNullKeysAndValues() {
            TestEntity entity1 = new TestEntity(1L, "entity1");
            Map<String, TestEntity> entries = new java.util.HashMap<>();
            entries.put("key1", entity1);
            entries.put(null, new TestEntity(2L, "entity2"));
            entries.put("key3", null);

            when(serializer.serialize(entity1)).thenReturn("{\"id\":1}");

            doAnswer(inv -> {
                Runnable runnable = inv.getArgument(0);
                runnable.run();
                return null;
            }).when(circuitBreakerService).executeWithFallback(any(Runnable.class));

            cache.multiSet(entries, 3600);

            verify(serializer, times(1)).serialize(any());
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldThrowRedisConnectionExceptionWhenMultiSetPipelineFailsWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            TestEntity entity = new TestEntity(1L, "entity1");
            when(serializer.serialize(entity)).thenReturn("{}");
            when(redisTemplate.executePipelined(any(org.springframework.data.redis.core.RedisCallback.class)))
                    .thenThrow(new io.lettuce.core.RedisException("down"));

            assertThatThrownBy(() -> cacheWithoutCB.multiSet(Map.of("key1", entity), 3600))
                    .isInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("Failed to multiSet in Redis");
        }

        @Test
        @SuppressWarnings("deprecation")
        void shouldIgnoreUnexpectedMultiSetErrorWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
            TestEntity entity = new TestEntity(1L, "entity1");
            when(serializer.serialize(entity)).thenThrow(new IllegalStateException("bad serialization"));

            cacheWithoutCB.multiSet(Map.of("key1", entity), 3600);

            verify(redisTemplate, never()).executePipelined(any(org.springframework.data.redis.core.RedisCallback.class));
        }
    }

    @Nested
    class MultiDeleteOperationTests {

        @Test
        void shouldReturnZeroForNullKeys() {
            long result = cache.multiDelete(null);
            assertThat(result).isZero();
        }

        @Test
        void shouldReturnZeroForEmptyKeys() {
            long result = cache.multiDelete(List.of());
            assertThat(result).isZero();
        }

        @Test
        void shouldDeleteMultipleKeys() {
            Collection<String> keys = List.of("key1", "key2", "key3");
            when(redisTemplate.delete(keys)).thenReturn(3L);

            long result = cache.multiDelete(keys);

            assertThat(result).isEqualTo(3);
            verify(redisTemplate).delete(keys);
        }

        @Test
        void shouldHandlePartialDelete() {
            Collection<String> keys = List.of("key1", "key2", "key3");
            when(redisTemplate.delete(keys)).thenReturn(2L);

            long result = cache.multiDelete(keys);

            assertThat(result).isEqualTo(2);
        }

        @Test
        void shouldReturnZeroWhenRedisReturnsNullForMultiDelete() {
            Collection<String> keys = List.of("key1", "key2");
            when(redisTemplate.delete(keys)).thenReturn(null);

            assertThat(cache.multiDelete(keys)).isZero();
        }

        @Test
        void shouldThrowRedisConnectionExceptionWhenMultiDeleteFails() {
            Collection<String> keys = List.of("key1", "key2");
            when(redisTemplate.delete(keys)).thenThrow(new io.lettuce.core.RedisException("down"));

            assertThatThrownBy(() -> cache.multiDelete(keys))
                    .isInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("Failed to multiDelete from Redis");
        }

        @Test
        void shouldReturnZeroWhenUnexpectedMultiDeleteErrorOccurs() {
            Collection<String> keys = List.of("key1", "key2");
            when(redisTemplate.delete(keys)).thenThrow(new IllegalStateException("unexpected"));

            assertThat(cache.multiDelete(keys)).isZero();
        }
    }

    @Nested
    class DeleteOperationTests {

        @Test
        void shouldReturnFalseForNullKey() {
            boolean result = cache.delete(null);
            assertThat(result).isFalse();
        }

        @Test
        void shouldReturnTrueWhenDeleted() {
            when(redisTemplate.delete("test-key")).thenReturn(true);

            boolean result = cache.delete("test-key");

            assertThat(result).isTrue();
        }

        @Test
        void shouldReturnFalseWhenNotDeleted() {
            when(redisTemplate.delete("test-key")).thenReturn(false);

            boolean result = cache.delete("test-key");

            assertThat(result).isFalse();
        }

        @Test
        void shouldReturnFalseWhenRedisReturnsNullForDelete() {
            when(redisTemplate.delete("test-key")).thenReturn((Boolean) null);

            assertThat(cache.delete("test-key")).isFalse();
        }

        @Test
        void shouldThrowRedisConnectionExceptionWhenDeleteFails() {
            when(redisTemplate.delete("test-key")).thenThrow(new io.lettuce.core.RedisException("down"));

            assertThatThrownBy(() -> cache.delete("test-key"))
                    .isInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("Failed to delete from Redis");
        }

        @Test
        void shouldReturnFalseWhenUnexpectedDeleteErrorOccurs() {
            when(redisTemplate.delete("test-key")).thenThrow(new IllegalStateException("unexpected"));

            assertThat(cache.delete("test-key")).isFalse();
        }
    }

    @Nested
    class ExistsOperationTests {

        @Test
        void shouldReturnFalseForNullKey() {
            boolean result = cache.exists(null);
            assertThat(result).isFalse();
        }

        @Test
        void shouldReturnTrueWhenExists() {
            when(redisTemplate.hasKey("test-key")).thenReturn(true);

            boolean result = cache.exists("test-key");

            assertThat(result).isTrue();
        }

        @Test
        void shouldReturnFalseWhenNotExists() {
            when(redisTemplate.hasKey("test-key")).thenReturn(false);

            boolean result = cache.exists("test-key");

            assertThat(result).isFalse();
        }

        @Test
        void shouldReturnFalseWhenRedisReturnsNullForExists() {
            when(redisTemplate.hasKey("test-key")).thenReturn(null);

            assertThat(cache.exists("test-key")).isFalse();
        }

        @Test
        void shouldThrowRedisConnectionExceptionWhenExistsFails() {
            when(redisTemplate.hasKey("test-key")).thenThrow(new io.lettuce.core.RedisException("down"));

            assertThatThrownBy(() -> cache.exists("test-key"))
                    .isInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("Failed to check existence in Redis");
        }

        @Test
        void shouldReturnFalseWhenUnexpectedExistsErrorOccurs() {
            when(redisTemplate.hasKey("test-key")).thenThrow(new IllegalStateException("unexpected"));

            assertThat(cache.exists("test-key")).isFalse();
        }
    }

    @Nested
    class KeysOperationTests {

        @Test
        void shouldReturnEmptySetForNullPattern() {
            Set<String> result = cache.keys(null);
            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnMatchingKeys() {
            Set<String> keys = Set.of("orgsec:p:1", "orgsec:p:2");
            when(redisTemplate.keys("orgsec:p:*")).thenReturn(keys);

            Set<String> result = cache.keys("orgsec:p:*");

            assertThat(result).containsExactlyInAnyOrderElementsOf(keys);
        }

        @Test
        void shouldReturnEmptySetWhenRedisReturnsNullForKeys() {
            when(redisTemplate.keys("orgsec:p:*")).thenReturn(null);

            assertThat(cache.keys("orgsec:p:*")).isEmpty();
        }

        @Test
        void shouldThrowRedisConnectionExceptionWhenKeysFails() {
            when(redisTemplate.keys("orgsec:p:*")).thenThrow(new io.lettuce.core.RedisException("down"));

            assertThatThrownBy(() -> cache.keys("orgsec:p:*"))
                    .isInstanceOf(RedisConnectionException.class)
                    .hasMessageContaining("Failed to get keys from Redis");
        }

        @Test
        void shouldReturnEmptySetWhenUnexpectedKeysErrorOccurs() {
            when(redisTemplate.keys("orgsec:p:*")).thenThrow(new IllegalStateException("unexpected"));

            assertThat(cache.keys("orgsec:p:*")).isEmpty();
        }
    }

    @Nested
    class KeyCountOperationTests {

        @Test
        void shouldReturnNumberOfMatchingKeys() {
            when(redisTemplate.keys("orgsec:*")).thenReturn(Set.of("k1", "k2"));

            assertThat(cache.keyCount("orgsec:*")).isEqualTo(2);
        }

        @Test
        void shouldReturnZeroWhenCountingFails() {
            when(redisTemplate.keys("orgsec:*")).thenThrow(new RuntimeException("unexpected"));

            assertThat(cache.keyCount("orgsec:*")).isZero();
        }
    }

    @Nested
    class ClearOperationTests {

        @Test
        void shouldDeleteAllMatchingKeysAndCloseCursor() {
            List<String> keys = List.of("orgsec:p:1", "orgsec:o:1");
            Cursor<String> cursor = cursor(keys);
            stubSinglePatternScan(cursor);
            when(redisTemplate.delete(keys)).thenReturn(2L);

            cache.clear();

            verify(redisTemplate).delete(keys);
            verify(cursor).close();
            verify(redisTemplate, never()).keys("orgsec:*");
        }

        @Test
        void shouldNotDeleteWhenNoMatchingKeysAndCloseCursor() {
            Cursor<String> cursor = cursor(Set.of());
            stubSinglePatternScan(cursor);

            cache.clear();

            verify(redisTemplate, never()).delete(anyCollection());
            verify(cursor).close();
        }

        @Test
        void shouldWrapDeleteExceptionAndCloseCursor() {
            List<String> keys = legacyPersonKeys(500);
            Cursor<String> cursor = cursor(keys);
            stubSinglePatternScan(cursor);
            when(redisTemplate.delete(keys)).thenThrow(new IllegalStateException("unexpected"));

            assertClearFailure("unexpected");

            verify(cursor).close();
        }

        @Test
        void shouldTreatNullDeleteResultAsFailureAndCloseCursor() {
            List<String> keys = legacyPersonKeys(500);
            Cursor<String> cursor = cursor(keys);
            stubSinglePatternScan(cursor);
            when(redisTemplate.delete(keys)).thenReturn(null);

            assertClearFailure("Redis delete returned no result for a legacy batch");

            verify(cursor).close();
        }

        @Test
        void shouldWrapScanException() {
            when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new IllegalStateException("scan failed"));

            assertClearFailure("scan failed");
        }

        @Test
        void shouldWrapHasNextExceptionAndCloseCursor() {
            Cursor<String> cursor = cursor(Set.of());
            when(cursor.hasNext()).thenThrow(new IllegalStateException("hasNext failed"));
            stubSinglePatternScan(cursor);

            assertClearFailure("hasNext failed");

            verify(cursor).close();
        }

        @Test
        void shouldWrapNextExceptionAndCloseCursor() {
            Cursor<String> cursor = cursor(List.of("orgsec:p:1"));
            when(cursor.hasNext()).thenReturn(true);
            when(cursor.next()).thenThrow(new IllegalStateException("next failed"));
            stubSinglePatternScan(cursor);

            assertClearFailure("next failed");

            verify(cursor).close();
        }

        @Test
        void shouldWrapCloseException() {
            Cursor<String> cursor = cursor(Set.of());
            doThrow(new IllegalStateException("close failed")).when(cursor).close();
            stubSinglePatternScan(cursor);

            assertClearFailure("close failed");

            verify(cursor).close();
        }

        @Test
        void shouldDeleteAcceptedKeysInBatchesOfAtMostFiveHundred() {
            CacheKeyBuilder realKeyBuilder = new CacheKeyBuilder(false);
            List<String> keys = new ArrayList<>();
            for (long id = 1; id <= 501; id++) {
                keys.add(realKeyBuilder.buildPersonKey(id));
            }
            List<String> scannedPatterns = new ArrayList<>();
            List<Cursor<String>> cursors = new ArrayList<>();
            List<Integer> batchSizes = new ArrayList<>();
            when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
                ScanOptions options = invocation.getArgument(0);
                assertAllowedScanOptions(options, realKeyBuilder.legacyDataKeyPatterns());
                scannedPatterns.add(options.getPattern());
                Cursor<String> cursor = cursor(
                    "orgsec:p:*".equals(options.getPattern()) ? keys : List.of()
                );
                cursors.add(cursor);
                return cursor;
            });
            when(redisTemplate.delete(anyCollection())).thenAnswer(invocation -> {
                Collection<String> batch = invocation.getArgument(0);
                batchSizes.add(batch.size());
                assertThat(batch).hasSizeLessThanOrEqualTo(500);
                return (long) batch.size();
            });
            L2RedisCache<TestEntity> realCache = new L2RedisCache<>(
                redisTemplate,
                serializer,
                realKeyBuilder,
                circuitBreakerService
            );

            realCache.clear();

            assertThat(batchSizes).containsExactly(500, 1);
            assertThat(scannedPatterns)
                .containsExactlyElementsOf(realKeyBuilder.legacyDataKeyPatterns());
            cursors.forEach(cursor -> verify(cursor).close());
        }

        @Test
        void plainClearDeletesLegacyFamiliesButPreservesEveryVersionedDurableSentinel() {
            assertLegacyClearPreservesDurableKeys(false);
        }

        @Test
        void obfuscatedClearDeletesLegacyHashesButPreservesEveryVersionedDurableSentinel() {
            assertLegacyClearPreservesDurableKeys(true);
        }

        private void assertLegacyClearPreservesDurableKeys(boolean obfuscated) {
            CacheKeyBuilder realKeyBuilder = new CacheKeyBuilder(obfuscated);
            Set<String> legacyKeys = Set.of(
                realKeyBuilder.buildPersonKey(1L),
                realKeyBuilder.buildOrganizationKey(2L),
                realKeyBuilder.buildRoleKey(3L),
                realKeyBuilder.buildPartyRoleKey(4L),
                realKeyBuilder.buildPositionRoleKey(5L),
                realKeyBuilder.buildPrivilegeKey("document_READ"),
                realKeyBuilder.buildPrivilegeKey("literal:*?[]:name")
            );
            Set<String> preservedKeys = Set.of(
                "orgsec:v1:{dataset}:control",
                "orgsec:v1:{dataset}:lease",
                "orgsec:v1:{dataset}:lease-counter",
                "orgsec:v999:{dataset}:control",
                "orgsec:p:01",
                "orgsec:r:party:01",
                "orgsec:r:future:1",
                "orgsec:" + "A".repeat(64),
                "orgsec:" + "g".repeat(64)
            );
            Set<String> redisKeys = new HashSet<>(legacyKeys);
            redisKeys.addAll(preservedKeys);
            List<String> scannedPatterns = new ArrayList<>();
            List<Cursor<String>> cursors = new ArrayList<>();

            when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
                ScanOptions options = invocation.getArgument(0);
                assertAllowedScanOptions(options, realKeyBuilder.legacyDataKeyPatterns());
                scannedPatterns.add(options.getPattern());
                List<String> matchingKeys = redisKeys.stream()
                    .filter(key -> matchesScanPattern(options.getPattern(), key))
                    .toList();
                Cursor<String> cursor = cursor(matchingKeys);
                cursors.add(cursor);
                return cursor;
            });
            when(redisTemplate.delete(anyCollection())).thenAnswer(invocation -> {
                Collection<String> requested = invocation.getArgument(0);
                assertThat(requested).hasSizeLessThanOrEqualTo(500);
                assertThat(requested).doesNotContainAnyElementsOf(preservedKeys);
                long before = redisKeys.size();
                redisKeys.removeAll(requested);
                return before - redisKeys.size();
            });
            L2RedisCache<TestEntity> realCache = new L2RedisCache<>(
                redisTemplate,
                serializer,
                realKeyBuilder,
                circuitBreakerService
            );

            realCache.clear();

            assertThat(redisKeys).containsExactlyInAnyOrderElementsOf(preservedKeys);
            assertThat(scannedPatterns)
                .containsExactlyElementsOf(realKeyBuilder.legacyDataKeyPatterns());
            cursors.forEach(cursor -> verify(cursor).close());
            verify(redisTemplate, never()).keys(anyString());
        }

        private void stubSinglePatternScan(Cursor<String> cursor) {
            when(redisTemplate.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
                assertAllowedScanOptions(invocation.getArgument(0), List.of("orgsec:p:*"));
                return cursor;
            });
        }

        private void assertAllowedScanOptions(
            ScanOptions options,
            Collection<String> allowedPatterns
        ) {
            assertThat(allowedPatterns).contains(options.getPattern());
            assertThat(options.getCount()).isEqualTo(500L);
        }

        private boolean matchesScanPattern(String pattern, String key) {
            if ("orgsec:p:*".equals(pattern)) {
                return key.startsWith("orgsec:p:");
            }
            if ("orgsec:o:*".equals(pattern)) {
                return key.startsWith("orgsec:o:");
            }
            if ("orgsec:r:*".equals(pattern)) {
                return key.startsWith("orgsec:r:");
            }
            if ("orgsec:priv:*".equals(pattern)) {
                return key.startsWith("orgsec:priv:");
            }
            if (("orgsec:" + "?".repeat(64)).equals(pattern)) {
                return key.startsWith("orgsec:") && key.length() == 71;
            }
            throw new AssertionError("unexpected legacy scan pattern: " + pattern);
        }

        private void assertClearFailure(String rootCauseMessage) {
            assertThatThrownBy(cache::clear)
                .isInstanceOf(RedisConnectionException.class)
                .hasMessageContaining("Failed to clear Redis cache")
                .hasRootCauseMessage(rootCauseMessage);
        }

        private List<String> legacyPersonKeys(int count) {
            List<String> keys = new ArrayList<>(count);
            for (int id = 1; id <= count; id++) {
                keys.add("orgsec:p:" + id);
            }
            return keys;
        }

        @SuppressWarnings("unchecked")
        private Cursor<String> cursor(Collection<String> keys) {
            Cursor<String> cursor = mock(Cursor.class);
            Iterator<String> iterator = List.copyOf(keys).iterator();
            when(cursor.hasNext()).thenAnswer(invocation -> iterator.hasNext());
            when(cursor.next()).thenAnswer(invocation -> iterator.next());
            return cursor;
        }
    }

    @Nested
    class AccessorTests {

        @Test
        void shouldReturnRedisTemplate() {
            assertThat(cache.getRedisTemplate()).isEqualTo(redisTemplate);
        }

        @Test
        void shouldReturnSerializer() {
            assertThat(cache.getSerializer()).isEqualTo(serializer);
        }

        @Test
        void shouldReturnKeyBuilder() {
            assertThat(cache.getKeyBuilder()).isEqualTo(keyBuilder);
        }
    }

    @Nested
    class DeprecatedConstructorTests {

        @Test
        @SuppressWarnings("deprecation")
        void shouldWorkWithoutCircuitBreaker() {
            L2RedisCache<TestEntity> cacheWithoutCB = new L2RedisCache<>(
                    redisTemplate, serializer, keyBuilder
            );

            // Should not throw
            assertThat(cacheWithoutCB.get(null)).isNull();
        }
    }
}
