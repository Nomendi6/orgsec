package com.nomendi6.orgsec.storage.redis.integration;

import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventListener;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.testutil.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for cache invalidation via Redis Pub/Sub.
 */
class InvalidationPubSubIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String CHANNEL = "test:invalidation";
    private static final String INSTANCE_1 = "instance-1";
    private static final String INSTANCE_2 = "instance-2";

    private InvalidationEventPublisher publisher;
    private InvalidationEventListener listener;
    private L1Cache<Long, PersonDef> personCache;
    private L1Cache<Long, PersonDef> organizationCache;
    private L1Cache<Long, PersonDef> roleCache;
    private RedisMessageListenerContainer listenerContainer;

    @BeforeEach
    void setUp() {
        clearRedis();

        // Create caches
        personCache = new L1Cache<>(100);
        organizationCache = new L1Cache<>(100);
        roleCache = new L1Cache<>(100);

        // Create publisher (instance 1)
        publisher = new InvalidationEventPublisher(redisTemplate, CHANNEL, true, INSTANCE_1);

        // Create listener (instance 2 - different instance ID)
        listener = new InvalidationEventListener(personCache, organizationCache, roleCache, INSTANCE_2);

        // Setup listener container
        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(redisConnectionFactory);
        listenerContainer.addMessageListener(listener, new ChannelTopic(CHANNEL));
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
    }

    @Test
    void publishPersonChanged_evictsFromRemoteCache() throws InterruptedException {
        // Given - populate cache
        PersonDef person = TestDataBuilder.buildPerson(123L, "John Doe");
        personCache.put(123L, person);
        assertThat(personCache.get(123L)).isNotNull();

        // Create latch to wait for async processing
        CountDownLatch latch = new CountDownLatch(1);

        // When - publish invalidation event from instance 1
        publisher.publishPersonChanged(123L);

        // Wait a bit for message to be processed
        latch.await(500, TimeUnit.MILLISECONDS);

        // Then - cache on instance 2 should be evicted
        assertThat(personCache.get(123L)).isNull();
    }

    @Test
    void publishOrganizationChanged_evictsFromRemoteCache() throws InterruptedException {
        // Given - populate cache (using organizationCache, but PersonDef for simplicity)
        PersonDef org = TestDataBuilder.buildPerson(456L, "Org 1");
        organizationCache.put(456L, org);
        assertThat(organizationCache.get(456L)).isNotNull();

        CountDownLatch latch = new CountDownLatch(1);

        // When
        publisher.publishOrganizationChanged(456L);
        latch.await(500, TimeUnit.MILLISECONDS);

        // Then
        assertThat(organizationCache.get(456L)).isNull();
    }

    @Test
    void publishRoleChanged_evictsFromRemoteCache() throws InterruptedException {
        // Given
        PersonDef role = TestDataBuilder.buildPerson(789L, "Role 1");
        roleCache.put(789L, role);
        assertThat(roleCache.get(789L)).isNotNull();

        CountDownLatch latch = new CountDownLatch(1);

        // When
        publisher.publishRoleChanged(789L);
        latch.await(500, TimeUnit.MILLISECONDS);

        // Then
        assertThat(roleCache.get(789L)).isNull();
    }

    @Test
    void publishSecurityRefresh_evictsAllCaches() throws InterruptedException {
        // Given - populate all caches
        personCache.put(1L, TestDataBuilder.buildPerson(1L, "Person"));
        organizationCache.put(2L, TestDataBuilder.buildPerson(2L, "Org"));
        roleCache.put(3L, TestDataBuilder.buildPerson(3L, "Role"));

        assertThat(personCache.size()).isEqualTo(1);
        assertThat(organizationCache.size()).isEqualTo(1);
        assertThat(roleCache.size()).isEqualTo(1);

        CountDownLatch latch = new CountDownLatch(1);

        // When
        publisher.publishSecurityRefresh();
        latch.await(500, TimeUnit.MILLISECONDS);

        // Then - all caches should be cleared
        assertThat(personCache.size()).isZero();
        assertThat(organizationCache.size()).isZero();
        assertThat(roleCache.size()).isZero();
    }

    @Test
    void sameInstance_ignoresOwnEvents() throws Exception {
        // Given - create SEPARATE caches for this test
        L1Cache<Long, PersonDef> separatePersonCache = new L1Cache<>(100);
        L1Cache<Long, PersonDef> separateOrgCache = new L1Cache<>(100);
        L1Cache<Long, PersonDef> separateRoleCache = new L1Cache<>(100);

        // Create listener with SAME instance ID as publisher
        InvalidationEventListener sameInstanceListener = new InvalidationEventListener(
            separatePersonCache, separateOrgCache, separateRoleCache, INSTANCE_1
        );

        RedisMessageListenerContainer sameInstanceContainer = new RedisMessageListenerContainer();
        sameInstanceContainer.setConnectionFactory(redisConnectionFactory);
        sameInstanceContainer.addMessageListener(sameInstanceListener, new ChannelTopic(CHANNEL));
        sameInstanceContainer.afterPropertiesSet();
        sameInstanceContainer.start();

        // Populate separate cache
        separatePersonCache.put(123L, TestDataBuilder.buildPerson(123L, "John"));
        assertThat(separatePersonCache.get(123L)).isNotNull();

        CountDownLatch latch = new CountDownLatch(1);

        // When - publish from same instance
        publisher.publishPersonChanged(123L);
        latch.await(500, TimeUnit.MILLISECONDS);

        // Then - cache should NOT be evicted (own event ignored)
        assertThat(separatePersonCache.get(123L)).isNotNull();

        sameInstanceContainer.stop();
        sameInstanceContainer.destroy();
    }

    @Test
    void multipleEvents_allProcessed() throws InterruptedException {
        // Given
        personCache.put(1L, TestDataBuilder.buildPerson(1L, "Person 1"));
        personCache.put(2L, TestDataBuilder.buildPerson(2L, "Person 2"));
        personCache.put(3L, TestDataBuilder.buildPerson(3L, "Person 3"));

        CountDownLatch latch = new CountDownLatch(1);

        // When - publish multiple events
        publisher.publishPersonChanged(1L);
        publisher.publishPersonChanged(2L);
        publisher.publishPersonChanged(3L);

        latch.await(1000, TimeUnit.MILLISECONDS);

        // Then - all should be evicted
        assertThat(personCache.get(1L)).isNull();
        assertThat(personCache.get(2L)).isNull();
        assertThat(personCache.get(3L)).isNull();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        if (listenerContainer != null) {
            listenerContainer.stop();
            listenerContainer.destroy();
        }
    }
}
