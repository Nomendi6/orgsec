package com.nomendi6.orgsec.storage.redis.integration;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventListener;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.preload.CacheWarmer;
import com.nomendi6.orgsec.storage.redis.serialization.JsonSerializer;
import com.nomendi6.orgsec.storage.redis.testutil.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * End-to-end integration tests for RedisSecurityDataStorage.
 */
class RedisSecurityDataStorageIntegrationTest extends AbstractRedisIntegrationTest {

    private RedisSecurityDataStorage storage;
    private CacheKeyBuilder keyBuilder;
    private L1Cache<Long, PersonDef> personL1Cache;
    private L2RedisCache<PersonDef> personL2Cache;
    private L1Cache<Long, RoleDef> roleL1Cache;
    private L1Cache<Long, RoleDef> positionRoleL1Cache;
    private L2RedisCache<RoleDef> roleL2Cache;

    @BeforeEach
    void setUp() {
        clearRedis();

        // Setup properties
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getTtl().setPerson(60);
        properties.getTtl().setOrganization(60);
        properties.getTtl().setRole(60);
        properties.getTtl().setPrivilege(60);
        properties.getPreload().setEnabled(false);

        // Setup components
        keyBuilder = new CacheKeyBuilder(false);

        // L1 caches
        personL1Cache = new L1Cache<>(100);
        L1Cache<Long, OrganizationDef> organizationL1Cache = new L1Cache<>(100);
        roleL1Cache = new L1Cache<>(100);
        positionRoleL1Cache = new L1Cache<>(100);
        L1Cache<String, PrivilegeDef> privilegeL1Cache = new L1Cache<>(100);

        // L2 caches
        personL2Cache = new L2RedisCache<>(redisTemplate, new JsonSerializer<>(PersonDef.class), keyBuilder);
        L2RedisCache<OrganizationDef> organizationL2Cache = new L2RedisCache<>(redisTemplate, new JsonSerializer<>(OrganizationDef.class), keyBuilder);
        roleL2Cache = new L2RedisCache<>(redisTemplate, new JsonSerializer<>(RoleDef.class), keyBuilder);
        L2RedisCache<PrivilegeDef> privilegeL2Cache = new L2RedisCache<>(redisTemplate, new JsonSerializer<>(PrivilegeDef.class), keyBuilder);

        // Invalidation publisher
        InvalidationEventPublisher publisher = new InvalidationEventPublisher(redisTemplate, "test:invalidation", true, "test-instance");

        // Cache warmer
        CacheWarmer warmer = new CacheWarmer(properties.getPreload());

        // Create storage
        storage = new RedisSecurityDataStorage(
            properties,
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            positionRoleL1Cache,
            privilegeL1Cache,
            personL2Cache,
            organizationL2Cache,
            roleL2Cache,
            privilegeL2Cache,
            keyBuilder,
            publisher,
            warmer
        );

        storage.initialize();
    }

    @Test
    void typedRoleWritesSurviveL1ClearWithSameIdAndRemainReadableBy104Nodes() {
        RoleDef partyRole = new RoleDef(7L, "Party role");
        RoleDef positionRole = new RoleDef(7L, "Position role");

        storage.updatePartyRole(7L, partyRole);
        storage.updatePositionRole(7L, positionRole);
        storage.clearLocalCaches();

        assertThat(storage.getPartyRole(7L).name).isEqualTo("Party role");
        assertThat(storage.getPositionRole(7L).name).isEqualTo("Position role");
        assertThat(roleL2Cache.get(keyBuilder.buildRoleKey(7L)).name)
            .as("the rolling-upgrade key remains available to a 1.0.4 node")
            .isEqualTo("Position role");
    }

    @Test
    void typedReadFallsBackToAndMigratesA104RoleKey() {
        RoleDef legacyRole = new RoleDef(8L, "Legacy role");
        roleL2Cache.set(keyBuilder.buildRoleKey(8L), legacyRole, 60);

        assertThat(storage.getPartyRole(8L).name).isEqualTo("Legacy role");
        assertThat(roleL2Cache.get(keyBuilder.buildPartyRoleKey(8L)).name).isEqualTo("Legacy role");
    }

    @Test
    void initialize_setsReadyFlag() {
        // When/Then
        assertThat(storage.isReady()).isTrue();
        assertThat(storage.getProviderType()).isEqualTo("redis");
    }

    @Test
    void getPerson_cacheHit_returnsFromL1() {
        // Given - populate L1 cache
        PersonDef person = TestDataBuilder.buildPerson(1L, "John Doe");
        personL1Cache.put(1L, person);

        // When
        PersonDef retrieved = storage.getPerson(1L);

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.personName).isEqualTo("John Doe");

        // Verify it was L1 hit (not L2)
        L1Cache.CacheStats stats = storage.getPersonL1Stats();
        assertThat(stats.getHitCount()).isEqualTo(1);
        assertThat(stats.getMissCount()).isEqualTo(0);
    }

    @Test
    void getPerson_l1Miss_l2Hit_populatesL1() {
        // Given - populate only L2 cache
        PersonDef person = TestDataBuilder.buildPerson(1L, "John Doe");
        String key = keyBuilder.buildPersonKey(1L);
        personL2Cache.set(key, person, 60);

        // Verify L1 is empty
        assertThat(personL1Cache.get(1L)).isNull();

        // When
        PersonDef retrieved = storage.getPerson(1L);

        // Then - returned from L2
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.personName).isEqualTo("John Doe");

        // And - L1 cache is now populated
        assertThat(personL1Cache.get(1L)).isNotNull();
        assertThat(personL1Cache.get(1L).personName).isEqualTo("John Doe");
    }

    @Test
    void revokedPersonCannotBeRegrantedFromStaleL2OnSourceOrPeer() throws Exception {
        long personId = 41L;
        String personKey = keyBuilder.buildPersonKey(personId);
        PersonDef grantedPerson = TestDataBuilder.buildPersonWithOrganizations(personId);
        RoleDef grantedRole = new RoleDef(91L, "Order reader")
            .addSecurityPrivilege("orders:read");
        grantedPerson.organizationsMap.get(1L).addPositionRole(grantedRole);

        L1Cache<Long, PersonDef> peerPersonL1Cache = new L1Cache<>(100);
        L1Cache<Long, OrganizationDef> peerOrganizationL1Cache = new L1Cache<>(100);
        L1Cache<Long, RoleDef> peerRoleL1Cache = new L1Cache<>(100);
        L1Cache<String, PrivilegeDef> peerPrivilegeL1Cache = new L1Cache<>(100);

        RedisStorageProperties peerProperties = new RedisStorageProperties();
        peerProperties.getPreload().setEnabled(false);
        RedisSecurityDataStorage peerStorage = new RedisSecurityDataStorage(
            peerProperties,
            peerPersonL1Cache,
            peerOrganizationL1Cache,
            peerRoleL1Cache,
            peerPrivilegeL1Cache,
            personL2Cache,
            new L2RedisCache<>(redisTemplate, new JsonSerializer<>(OrganizationDef.class), keyBuilder),
            roleL2Cache,
            new L2RedisCache<>(redisTemplate, new JsonSerializer<>(PrivilegeDef.class), keyBuilder),
            keyBuilder,
            new InvalidationEventPublisher(redisTemplate, "test:invalidation", false, "peer-instance"),
            new CacheWarmer(peerProperties.getPreload())
        );
        peerStorage.initialize();

        InvalidationEventListener peerListener = new InvalidationEventListener(
            peerPersonL1Cache,
            peerOrganizationL1Cache,
            peerRoleL1Cache,
            "peer-instance"
        );
        RedisMessageListenerContainer peerListenerContainer = new RedisMessageListenerContainer();
        peerListenerContainer.setConnectionFactory(redisConnectionFactory);
        peerListenerContainer.addMessageListener(peerListener, new ChannelTopic("test:invalidation"));
        peerListenerContainer.afterPropertiesSet();
        peerListenerContainer.start();

        try {
            // Both instances have already authorized from the same shared L2 record.
            personL2Cache.set(personKey, grantedPerson, 60);
            assertThat(storage.getPerson(personId).organizationsMap.get(1L).positionRolesSet)
                .anySatisfy(role -> assertThat(role.securityPrivilegeSet).contains("orders:read"));
            assertThat(peerStorage.getPerson(personId).organizationsMap.get(1L).positionRolesSet)
                .anySatisfy(role -> assertThat(role.securityPrivilegeSet).contains("orders:read"));

            // The authoritative source deleted/revoked this person and emits the supported notification.
            storage.notifyPersonChanged(personId);

            assertThat(personL1Cache.get(personId))
                .as("the source instance L1 entry is invalidated synchronously")
                .isNull();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (peerPersonL1Cache.get(personId) != null && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(peerPersonL1Cache.get(personId))
                .as("the peer instance receives Pub/Sub and invalidates its L1 entry")
                .isNull();

            PersonDef sourceReadAfterRevoke = storage.getPerson(personId);
            PersonDef peerReadAfterRevoke = peerStorage.getPerson(personId);

            assertAll(
                () -> assertThat(sourceReadAfterRevoke)
                    .as("the source must not regrant a deleted person from stale L2")
                    .isNull(),
                () -> assertThat(peerReadAfterRevoke)
                    .as("the peer must not regrant a deleted person from stale L2")
                    .isNull()
            );
        } finally {
            peerListenerContainer.stop();
            peerListenerContainer.destroy();
        }
    }

    @Test
    void getPerson_cacheMiss_returnsNull() {
        // When
        PersonDef retrieved = storage.getPerson(999L);

        // Then
        assertThat(retrieved).isNull();
    }

    @Test
    void getPerson_nullId_returnsNull() {
        // When
        PersonDef retrieved = storage.getPerson(null);

        // Then
        assertThat(retrieved).isNull();
    }

    @Test
    void updatePerson_populatesBothCaches() {
        // Given
        PersonDef person = TestDataBuilder.buildPerson(1L, "Updated Person");

        // When
        storage.updatePerson(1L, person);

        // Then - L1 cache populated
        assertThat(personL1Cache.get(1L)).isNotNull();
        assertThat(personL1Cache.get(1L).personName).isEqualTo("Updated Person");

        // And - L2 cache populated
        String key = keyBuilder.buildPersonKey(1L);
        PersonDef fromL2 = personL2Cache.get(key);
        assertThat(fromL2).isNotNull();
        assertThat(fromL2.personName).isEqualTo("Updated Person");
    }

    @Test
    void updatePerson_nullValue_doesNothing() {
        // When/Then
        assertThatCode(() -> storage.updatePerson(1L, null))
            .doesNotThrowAnyException();

        // Verify nothing was cached
        assertThat(personL1Cache.get(1L)).isNull();
    }

    @Test
    void refresh_clearsL1Caches() {
        // Given - populate L1 caches
        personL1Cache.put(1L, TestDataBuilder.buildPerson(1L, "Person 1"));
        personL1Cache.put(2L, TestDataBuilder.buildPerson(2L, "Person 2"));

        assertThat(personL1Cache.size()).isEqualTo(2);

        // When
        storage.refresh();

        // Then - L1 caches cleared
        assertThat(personL1Cache.size()).isZero();
    }

    @Test
    void multiplePerson_independentOperations() {
        // Given
        PersonDef person1 = TestDataBuilder.buildPerson(1L, "Person 1");
        PersonDef person2 = TestDataBuilder.buildPerson(2L, "Person 2");

        // When
        storage.updatePerson(1L, person1);
        storage.updatePerson(2L, person2);

        // Then
        assertThat(storage.getPerson(1L).personName).isEqualTo("Person 1");
        assertThat(storage.getPerson(2L).personName).isEqualTo("Person 2");

        // Refresh - both should be cleared from L1
        storage.refresh();
        assertThat(personL1Cache.size()).isZero();
    }

    @Test
    void getPersonWithOrganizations_preservesNestedData() {
        // Given
        PersonDef person = TestDataBuilder.buildPersonWithOrganizations(1L);
        storage.updatePerson(1L, person);

        // When - clear L1 to force L2 read
        personL1Cache.clear();
        PersonDef retrieved = storage.getPerson(1L);

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.organizationsMap).hasSize(2);
        assertThat(retrieved.organizationsMap.get(1L).organizationName).isEqualTo("Organization 1");
    }

    @Test
    void cacheStats_trackHitsAndMisses() {
        // Given
        PersonDef person = TestDataBuilder.buildPerson(1L, "John");
        storage.updatePerson(1L, person);

        // When
        storage.getPerson(1L); // Hit
        storage.getPerson(1L); // Hit
        storage.getPerson(999L); // Miss

        // Then
        L1Cache.CacheStats stats = storage.getPersonL1Stats();
        assertThat(stats.getHitCount()).isEqualTo(2);
        assertThat(stats.getMissCount()).isEqualTo(1);
        assertThat(stats.getHitRate()).isCloseTo(66.67, within(0.1));
    }
}
