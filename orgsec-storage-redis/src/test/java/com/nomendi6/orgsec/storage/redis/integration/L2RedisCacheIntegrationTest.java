package com.nomendi6.orgsec.storage.redis.integration;

import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.serialization.JsonSerializer;
import com.nomendi6.orgsec.storage.redis.testutil.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for L2RedisCache using real Redis instance via Testcontainers.
 */
class L2RedisCacheIntegrationTest extends AbstractRedisIntegrationTest {

    private L2RedisCache<PersonDef> cache;
    private CacheKeyBuilder keyBuilder;
    private JsonSerializer<PersonDef> serializer;

    @BeforeEach
    void setUp() {
        clearRedis();

        keyBuilder = new CacheKeyBuilder(false);
        serializer = new JsonSerializer<>(PersonDef.class);
        cache = new L2RedisCache<>(redisTemplate, serializer, keyBuilder);
    }

    @Test
    void set_andGet_returnsValue() {
        // Given
        String key = keyBuilder.buildPersonKey(1L);
        PersonDef person = TestDataBuilder.buildPerson(1L, "John Doe");

        // When
        cache.set(key, person, 60);
        PersonDef retrieved = cache.get(key);

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.personId).isEqualTo(1L);
        assertThat(retrieved.personName).isEqualTo("John Doe");
        assertThat(retrieved.relatedUserId).isEqualTo("user-1");
    }

    @Test
    void get_nonExistentKey_returnsNull() {
        // Given
        String key = keyBuilder.buildPersonKey(999L);

        // When
        PersonDef person = cache.get(key);

        // Then
        assertThat(person).isNull();
    }

    @Test
    void delete_existingKey_removesValue() {
        // Given
        String key = keyBuilder.buildPersonKey(1L);
        PersonDef person = TestDataBuilder.buildPerson(1L, "John Doe");
        cache.set(key, person, 60);

        // When
        cache.delete(key);
        PersonDef retrieved = cache.get(key);

        // Then
        assertThat(retrieved).isNull();
    }

    @Test
    void delete_nonExistentKey_doesNothing() {
        // Given
        String key = keyBuilder.buildPersonKey(999L);

        // When/Then - should not throw exception
        assertThatCode(() -> cache.delete(key))
            .doesNotThrowAnyException();
    }

    @Test
    void keys_withPattern_returnsMatchingKeys() {
        // Given
        cache.set(keyBuilder.buildPersonKey(1L), TestDataBuilder.buildPerson(1L, "Person 1"), 60);
        cache.set(keyBuilder.buildPersonKey(2L), TestDataBuilder.buildPerson(2L, "Person 2"), 60);
        cache.set(keyBuilder.buildPersonKey(3L), TestDataBuilder.buildPerson(3L, "Person 3"), 60);

        // When
        Set<String> keys = cache.keys(keyBuilder.personKeysPattern());

        // Then
        assertThat(keys).hasSize(3);
        assertThat(keys).contains(
            keyBuilder.buildPersonKey(1L),
            keyBuilder.buildPersonKey(2L),
            keyBuilder.buildPersonKey(3L)
        );
    }

    @Test
    void keys_noMatches_returnsEmptySet() {
        // When
        Set<String> keys = cache.keys(keyBuilder.personKeysPattern());

        // Then
        assertThat(keys).isEmpty();
    }

    @Test
    void set_withTtl_expiresAfterTtl() throws InterruptedException {
        // Given
        String key = keyBuilder.buildPersonKey(1L);
        PersonDef person = TestDataBuilder.buildPerson(1L, "John Doe");

        // When - set with 1 second TTL
        cache.set(key, person, 1);

        // Then - immediately available
        assertThat(cache.get(key)).isNotNull();

        // Wait for expiration
        Thread.sleep(1500);

        // Then - should be expired
        assertThat(cache.get(key)).isNull();
    }

    @Test
    void set_overwritesExistingValue() {
        // Given
        String key = keyBuilder.buildPersonKey(1L);
        PersonDef person1 = TestDataBuilder.buildPerson(1L, "John Doe");
        PersonDef person2 = TestDataBuilder.buildPerson(1L, "Jane Smith");

        // When
        cache.set(key, person1, 60);
        cache.set(key, person2, 60);

        // Then
        PersonDef retrieved = cache.get(key);
        assertThat(retrieved.personName).isEqualTo("Jane Smith");
    }

    @Test
    void set_withComplexObject_preservesNestedData() {
        // Given
        String key = keyBuilder.buildPersonKey(1L);
        PersonDef person = TestDataBuilder.buildPersonWithOrganizations(1L);

        // When
        cache.set(key, person, 60);
        PersonDef retrieved = cache.get(key);

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.organizationsMap).hasSize(2);
        assertThat(retrieved.organizationsMap.get(1L).organizationName).isEqualTo("Organization 1");
        assertThat(retrieved.organizationsMap.get(2L).organizationName).isEqualTo("Organization 2");
    }

    @Test
    void multipleKeys_independentOperations() {
        // Given
        String key1 = keyBuilder.buildPersonKey(1L);
        String key2 = keyBuilder.buildPersonKey(2L);
        PersonDef person1 = TestDataBuilder.buildPerson(1L, "Person 1");
        PersonDef person2 = TestDataBuilder.buildPerson(2L, "Person 2");

        // When
        cache.set(key1, person1, 60);
        cache.set(key2, person2, 60);
        cache.delete(key1);

        // Then
        assertThat(cache.get(key1)).isNull();
        assertThat(cache.get(key2)).isNotNull();
        assertThat(cache.get(key2).personName).isEqualTo("Person 2");
    }
}
