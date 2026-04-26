package com.nomendi6.orgsec.storage.redis;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.preload.CacheWarmer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisSecurityDataStorageTest {

    @Mock
    private RedisStorageProperties properties;

    @Mock
    private L1Cache<Long, PersonDef> personL1Cache;

    @Mock
    private L1Cache<Long, OrganizationDef> organizationL1Cache;

    @Mock
    private L1Cache<Long, RoleDef> roleL1Cache;

    @Mock
    private L1Cache<Long, com.nomendi6.orgsec.model.PrivilegeDef> privilegeL1Cache;

    @Mock
    private L2RedisCache<PersonDef> personL2Cache;

    @Mock
    private L2RedisCache<OrganizationDef> organizationL2Cache;

    @Mock
    private L2RedisCache<RoleDef> roleL2Cache;

    @Mock
    private L2RedisCache<com.nomendi6.orgsec.model.PrivilegeDef> privilegeL2Cache;

    @Mock
    private CacheKeyBuilder cacheKeyBuilder;

    @Mock
    private InvalidationEventPublisher invalidationPublisher;

    @Mock
    private CacheWarmer cacheWarmer;

    private RedisSecurityDataStorage storage;

    @BeforeEach
    void setUp() {
        // Setup TTL config
        RedisStorageProperties.TtlConfig ttlConfig = new RedisStorageProperties.TtlConfig();
        when(properties.getTtl()).thenReturn(ttlConfig);

        // Setup preload config
        RedisStorageProperties.PreloadConfig preloadConfig = new RedisStorageProperties.PreloadConfig();
        preloadConfig.setEnabled(false);
        when(properties.getPreload()).thenReturn(preloadConfig);

        // Setup key builder
        when(cacheKeyBuilder.buildPersonKey(anyLong())).thenAnswer(inv -> "orgsec:p:" + inv.getArgument(0));
        when(cacheKeyBuilder.buildOrganizationKey(anyLong())).thenAnswer(inv -> "orgsec:o:" + inv.getArgument(0));
        when(cacheKeyBuilder.buildRoleKey(anyLong())).thenAnswer(inv -> "orgsec:r:" + inv.getArgument(0));
        when(cacheKeyBuilder.buildPrivilegeKey(anyLong())).thenAnswer(inv -> "orgsec:priv:" + inv.getArgument(0));

        storage = new RedisSecurityDataStorage(
                properties,
                personL1Cache,
                organizationL1Cache,
                roleL1Cache,
                privilegeL1Cache,
                personL2Cache,
                organizationL2Cache,
                roleL2Cache,
                privilegeL2Cache,
                cacheKeyBuilder,
                invalidationPublisher,
                cacheWarmer
        );
    }

    @Nested
    class GetPersonTests {

        @Test
        void shouldReturnNullForNullId() {
            PersonDef result = storage.getPerson(null);
            assertThat(result).isNull();
        }

        @Test
        void shouldReturnFromL1CacheIfPresent() {
            PersonDef person = new PersonDef(1L, "Test Person");
            when(personL1Cache.get(1L)).thenReturn(person);

            PersonDef result = storage.getPerson(1L);

            assertThat(result).isEqualTo(person);
            verify(personL2Cache, never()).get(anyString());
        }

        @Test
        void shouldReturnFromL2CacheAndPopulateL1() {
            PersonDef person = new PersonDef(1L, "Test Person");
            when(personL1Cache.get(1L)).thenReturn(null);
            when(personL2Cache.get("orgsec:p:1")).thenReturn(person);

            PersonDef result = storage.getPerson(1L);

            assertThat(result).isEqualTo(person);
            verify(personL1Cache).put(1L, person);
        }

        @Test
        void shouldReturnNullOnCacheMiss() {
            when(personL1Cache.get(1L)).thenReturn(null);
            when(personL2Cache.get("orgsec:p:1")).thenReturn(null);

            PersonDef result = storage.getPerson(1L);

            assertThat(result).isNull();
        }
    }

    @Nested
    class GetOrganizationTests {

        @Test
        void shouldReturnNullForNullId() {
            OrganizationDef result = storage.getOrganization(null);
            assertThat(result).isNull();
        }

        @Test
        void shouldReturnFromL1CacheIfPresent() {
            OrganizationDef org = new OrganizationDef();
            org.organizationId = 1L;
            when(organizationL1Cache.get(1L)).thenReturn(org);

            OrganizationDef result = storage.getOrganization(1L);

            assertThat(result).isEqualTo(org);
            verify(organizationL2Cache, never()).get(anyString());
        }

        @Test
        void shouldReturnFromL2CacheAndPopulateL1() {
            OrganizationDef org = new OrganizationDef();
            org.organizationId = 1L;
            when(organizationL1Cache.get(1L)).thenReturn(null);
            when(organizationL2Cache.get("orgsec:o:1")).thenReturn(org);

            OrganizationDef result = storage.getOrganization(1L);

            assertThat(result).isEqualTo(org);
            verify(organizationL1Cache).put(1L, org);
        }

        @Test
        void shouldReturnNullOnCacheMiss() {
            when(organizationL1Cache.get(1L)).thenReturn(null);
            when(organizationL2Cache.get("orgsec:o:1")).thenReturn(null);

            OrganizationDef result = storage.getOrganization(1L);

            assertThat(result).isNull();
        }
    }

    @Nested
    class GetRoleTests {

        @Test
        void shouldReturnPartyRole() {
            RoleDef role = new RoleDef();
            role.roleId = 1L;
            when(roleL1Cache.get(1L)).thenReturn(role);

            RoleDef result = storage.getPartyRole(1L);

            assertThat(result).isEqualTo(role);
        }

        @Test
        void shouldReturnPositionRole() {
            RoleDef role = new RoleDef();
            role.roleId = 1L;
            when(roleL1Cache.get(1L)).thenReturn(role);

            RoleDef result = storage.getPositionRole(1L);

            assertThat(result).isEqualTo(role);
        }

        @Test
        void shouldReturnNullForNullRoleId() {
            assertThat(storage.getPartyRole(null)).isNull();
            assertThat(storage.getPositionRole(null)).isNull();
        }

        @Test
        void shouldReturnFromL2CacheAndPopulateL1() {
            RoleDef role = new RoleDef(1L, "Admin");
            when(roleL1Cache.get(1L)).thenReturn(null);
            when(roleL2Cache.get("orgsec:r:1")).thenReturn(role);

            RoleDef result = storage.getPartyRole(1L);

            assertThat(result).isEqualTo(role);
            verify(roleL1Cache).put(1L, role);
        }

        @Test
        void shouldReturnNullOnCacheMiss() {
            when(roleL1Cache.get(1L)).thenReturn(null);
            when(roleL2Cache.get("orgsec:r:1")).thenReturn(null);

            assertThat(storage.getPartyRole(1L)).isNull();
        }
    }

    @Nested
    class GetPrivilegeTests {

        @Test
        void shouldReturnNullForNullIdentifier() {
            assertThat(storage.getPrivilege(null)).isNull();
        }

        @Test
        void shouldReturnNullForBlankIdentifier() {
            assertThat(storage.getPrivilege("   ")).isNull();
        }

        @Test
        void shouldReturnFromL1CacheIfPresent() {
            PrivilegeDef privilege = new PrivilegeDef("READ", "document");
            Long hash = (long) "READ".hashCode();
            when(privilegeL1Cache.get(hash)).thenReturn(privilege);

            PrivilegeDef result = storage.getPrivilege("READ");

            assertThat(result).isEqualTo(privilege);
            verify(privilegeL2Cache, never()).get(anyString());
        }

        @Test
        void shouldReturnFromL2CacheAndPopulateL1() {
            PrivilegeDef privilege = new PrivilegeDef("READ", "document");
            Long hash = (long) "READ".hashCode();
            when(privilegeL1Cache.get(hash)).thenReturn(null);
            when(privilegeL2Cache.get("orgsec:priv:" + hash)).thenReturn(privilege);

            PrivilegeDef result = storage.getPrivilege("READ");

            assertThat(result).isEqualTo(privilege);
            verify(privilegeL1Cache).put(hash, privilege);
        }

        @Test
        void shouldReturnNullOnCacheMiss() {
            Long hash = (long) "READ".hashCode();
            when(privilegeL1Cache.get(hash)).thenReturn(null);
            when(privilegeL2Cache.get("orgsec:priv:" + hash)).thenReturn(null);

            assertThat(storage.getPrivilege("READ")).isNull();
        }
    }

    @Nested
    class UpdatePersonTests {

        @Test
        void shouldNotUpdateNullId() {
            storage.updatePerson(null, new PersonDef(1L, "Test"));
            verify(personL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldNotUpdateNullPerson() {
            storage.updatePerson(1L, null);
            verify(personL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldUpdateBothCachesAndPublish() {
            PersonDef person = new PersonDef(1L, "Test");

            storage.updatePerson(1L, person);

            verify(personL1Cache).put(1L, person);
            verify(personL2Cache).set(eq("orgsec:p:1"), eq(person), anyLong());
            verify(invalidationPublisher).publishPersonChanged(1L);
        }
    }

    @Nested
    class UpdateOrganizationTests {

        @Test
        void shouldNotUpdateNullId() {
            storage.updateOrganization(null, new OrganizationDef());
            verify(organizationL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldNotUpdateNullOrganization() {
            storage.updateOrganization(1L, null);
            verify(organizationL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldUpdateBothCachesAndPublish() {
            OrganizationDef org = new OrganizationDef();
            org.organizationId = 1L;

            storage.updateOrganization(1L, org);

            verify(organizationL1Cache).put(1L, org);
            verify(organizationL2Cache).set(eq("orgsec:o:1"), eq(org), anyLong());
            verify(invalidationPublisher).publishOrganizationChanged(1L);
        }
    }

    @Nested
    class UpdateRoleTests {

        @Test
        void shouldNotUpdateNullId() {
            storage.updateRole(null, new RoleDef());
            verify(roleL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldNotUpdateNullRole() {
            storage.updateRole(1L, null);
            verify(roleL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldUpdateBothCachesAndPublish() {
            RoleDef role = new RoleDef();
            role.roleId = 1L;

            storage.updateRole(1L, role);

            verify(roleL1Cache).put(1L, role);
            verify(roleL2Cache).set(eq("orgsec:r:1"), eq(role), anyLong());
            verify(invalidationPublisher).publishRoleChanged(1L);
        }
    }

    @Nested
    class BatchGetPersonsTests {

        @Test
        void shouldReturnEmptyMapForNullIds() {
            Map<Long, PersonDef> result = storage.getPersons(null);
            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnEmptyMapForEmptyIds() {
            Map<Long, PersonDef> result = storage.getPersons(List.of());
            assertThat(result).isEmpty();
        }

        @Test
        void shouldReturnFromL1CacheFirst() {
            PersonDef person1 = new PersonDef(1L, "Person 1");
            PersonDef person2 = new PersonDef(2L, "Person 2");

            when(personL1Cache.get(1L)).thenReturn(person1);
            when(personL1Cache.get(2L)).thenReturn(person2);

            Map<Long, PersonDef> result = storage.getPersons(List.of(1L, 2L));

            assertThat(result).hasSize(2);
            assertThat(result.get(1L)).isEqualTo(person1);
            assertThat(result.get(2L)).isEqualTo(person2);
            verify(personL2Cache, never()).multiGet(any());
        }

        @Test
        void shouldFetchMissingFromL2Cache() {
            PersonDef person1 = new PersonDef(1L, "Person 1");
            PersonDef person2 = new PersonDef(2L, "Person 2");

            when(personL1Cache.get(1L)).thenReturn(person1);
            when(personL1Cache.get(2L)).thenReturn(null);
            when(personL2Cache.multiGet(any())).thenReturn(Map.of("orgsec:p:2", person2));

            Map<Long, PersonDef> result = storage.getPersons(List.of(1L, 2L));

            assertThat(result).hasSize(2);
            verify(personL1Cache).put(2L, person2);
        }

        @Test
        void shouldSkipNullIds() {
            Collection<Long> ids = new java.util.ArrayList<>();
            ids.add(1L);
            ids.add(null);
            ids.add(2L);

            when(personL1Cache.get(1L)).thenReturn(new PersonDef(1L, "Person 1"));
            when(personL1Cache.get(2L)).thenReturn(new PersonDef(2L, "Person 2"));

            Map<Long, PersonDef> result = storage.getPersons(ids);

            assertThat(result).hasSize(2);
        }
    }

    @Nested
    class BatchUpdatePersonsTests {

        @Test
        void shouldNotUpdateNullMap() {
            storage.updatePersons(null);
            verify(personL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldNotUpdateEmptyMap() {
            storage.updatePersons(Map.of());
            verify(personL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldUpdateBothCaches() {
            PersonDef person1 = new PersonDef(1L, "Person 1");
            PersonDef person2 = new PersonDef(2L, "Person 2");
            Map<Long, PersonDef> persons = Map.of(1L, person1, 2L, person2);

            storage.updatePersons(persons);

            verify(personL1Cache).put(1L, person1);
            verify(personL1Cache).put(2L, person2);
            verify(personL2Cache).multiSet(any(), anyLong());
        }

        @Test
        void shouldSkipNullKeysAndValues() {
            PersonDef person = new PersonDef(1L, "Person 1");
            Map<Long, PersonDef> persons = new HashMap<>();
            persons.put(1L, person);
            persons.put(null, new PersonDef(2L, "Person 2"));
            persons.put(3L, null);

            storage.updatePersons(persons);

            verify(personL1Cache).put(1L, person);
            verify(personL1Cache, never()).put(eq(3L), any());
            verify(personL2Cache).multiSet(argThat(entries ->
                    entries.size() == 1 && entries.containsKey("orgsec:p:1")), anyLong());
        }
    }

    @Nested
    class BatchGetOrganizationsTests {

        @Test
        void shouldReturnEmptyMapForNullIds() {
            assertThat(storage.getOrganizations(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyMapForEmptyIds() {
            assertThat(storage.getOrganizations(List.of())).isEmpty();
        }

        @Test
        void shouldReturnFromL1AndFetchMissingFromL2() {
            OrganizationDef org1 = new OrganizationDef().setOrganizationId(1L);
            OrganizationDef org2 = new OrganizationDef().setOrganizationId(2L);
            when(organizationL1Cache.get(1L)).thenReturn(org1);
            when(organizationL1Cache.get(2L)).thenReturn(null);
            when(organizationL2Cache.multiGet(any())).thenReturn(Map.of("orgsec:o:2", org2));

            Map<Long, OrganizationDef> result = storage.getOrganizations(List.of(1L, 2L));

            assertThat(result).containsEntry(1L, org1).containsEntry(2L, org2);
            verify(organizationL1Cache).put(2L, org2);
        }

        @Test
        void shouldSkipNullIdsAndMisses() {
            Collection<Long> ids = new java.util.ArrayList<>();
            ids.add(1L);
            ids.add(null);
            ids.add(2L);
            OrganizationDef org1 = new OrganizationDef().setOrganizationId(1L);
            when(organizationL1Cache.get(1L)).thenReturn(org1);
            when(organizationL1Cache.get(2L)).thenReturn(null);
            when(organizationL2Cache.multiGet(any())).thenReturn(Map.of());

            Map<Long, OrganizationDef> result = storage.getOrganizations(ids);

            assertThat(result).containsOnly(Map.entry(1L, org1));
        }
    }

    @Nested
    class BatchUpdateOrganizationsTests {

        @Test
        void shouldNotUpdateNullMap() {
            storage.updateOrganizations(null);
            verify(organizationL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldNotUpdateEmptyMap() {
            storage.updateOrganizations(Map.of());
            verify(organizationL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldUpdateBothCachesAndSkipInvalidEntries() {
            OrganizationDef org1 = new OrganizationDef().setOrganizationId(1L);
            OrganizationDef org2 = new OrganizationDef().setOrganizationId(2L);
            Map<Long, OrganizationDef> organizations = new HashMap<>();
            organizations.put(1L, org1);
            organizations.put(2L, org2);
            organizations.put(null, new OrganizationDef());
            organizations.put(3L, null);

            storage.updateOrganizations(organizations);

            verify(organizationL1Cache).put(1L, org1);
            verify(organizationL1Cache).put(2L, org2);
            verify(organizationL2Cache).multiSet(argThat(entries ->
                    entries.size() == 2 && entries.containsKey("orgsec:o:1") && entries.containsKey("orgsec:o:2")), anyLong());
        }
    }

    @Nested
    class BatchGetRolesTests {

        @Test
        void shouldReturnEmptyMapForNullIds() {
            assertThat(storage.getRoles(null)).isEmpty();
        }

        @Test
        void shouldReturnEmptyMapForEmptyIds() {
            assertThat(storage.getRoles(List.of())).isEmpty();
        }

        @Test
        void shouldReturnFromL1AndFetchMissingFromL2() {
            RoleDef role1 = new RoleDef(1L, "Role 1");
            RoleDef role2 = new RoleDef(2L, "Role 2");
            when(roleL1Cache.get(1L)).thenReturn(role1);
            when(roleL1Cache.get(2L)).thenReturn(null);
            when(roleL2Cache.multiGet(any())).thenReturn(Map.of("orgsec:r:2", role2));

            Map<Long, RoleDef> result = storage.getRoles(List.of(1L, 2L));

            assertThat(result).containsEntry(1L, role1).containsEntry(2L, role2);
            verify(roleL1Cache).put(2L, role2);
        }

        @Test
        void shouldSkipNullIdsAndMisses() {
            Collection<Long> ids = new java.util.ArrayList<>();
            ids.add(1L);
            ids.add(null);
            ids.add(2L);
            RoleDef role1 = new RoleDef(1L, "Role 1");
            when(roleL1Cache.get(1L)).thenReturn(role1);
            when(roleL1Cache.get(2L)).thenReturn(null);
            when(roleL2Cache.multiGet(any())).thenReturn(Map.of());

            Map<Long, RoleDef> result = storage.getRoles(ids);

            assertThat(result).containsOnly(Map.entry(1L, role1));
        }
    }

    @Nested
    class BatchUpdateRolesTests {

        @Test
        void shouldNotUpdateNullMap() {
            storage.updateRoles(null);
            verify(roleL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldNotUpdateEmptyMap() {
            storage.updateRoles(Map.of());
            verify(roleL1Cache, never()).put(any(), any());
        }

        @Test
        void shouldUpdateBothCachesAndSkipInvalidEntries() {
            RoleDef role1 = new RoleDef(1L, "Role 1");
            RoleDef role2 = new RoleDef(2L, "Role 2");
            Map<Long, RoleDef> roles = new HashMap<>();
            roles.put(1L, role1);
            roles.put(2L, role2);
            roles.put(null, new RoleDef());
            roles.put(3L, null);

            storage.updateRoles(roles);

            verify(roleL1Cache).put(1L, role1);
            verify(roleL1Cache).put(2L, role2);
            verify(roleL2Cache).multiSet(argThat(entries ->
                    entries.size() == 2 && entries.containsKey("orgsec:r:1") && entries.containsKey("orgsec:r:2")), anyLong());
        }
    }

    @Nested
    class NotificationTests {

        @Test
        void shouldInvalidateL1CacheOnPersonChanged() {
            storage.notifyPersonChanged(1L);

            verify(personL1Cache).invalidate(1L);
            verify(invalidationPublisher).publishPersonChanged(1L);
        }

        @Test
        void shouldInvalidateL1CacheOnOrganizationChanged() {
            storage.notifyOrganizationChanged(1L);

            verify(organizationL1Cache).invalidate(1L);
            verify(invalidationPublisher).publishOrganizationChanged(1L);
        }

        @Test
        void shouldInvalidateL1CacheOnPartyRoleChanged() {
            storage.notifyPartyRoleChanged(1L);

            verify(roleL1Cache).invalidate(1L);
            verify(invalidationPublisher).publishRoleChanged(1L);
        }

        @Test
        void shouldInvalidateL1CacheOnPositionRoleChanged() {
            storage.notifyPositionRoleChanged(1L);

            verify(roleL1Cache).invalidate(1L);
            verify(invalidationPublisher).publishRoleChanged(1L);
        }
    }

    @Nested
    class LifecycleTests {

        @Test
        void shouldInitializeAndSetReady() {
            storage.initialize();

            assertThat(storage.isReady()).isTrue();
            verify(cacheWarmer).setPersonBatchStore(any());
            verify(cacheWarmer).setOrganizationBatchStore(any());
            verify(cacheWarmer).setRoleBatchStore(any());
        }

        @Test
        void shouldWarmupOnInitializeWhenPreloadEnabled() {
            RedisStorageProperties.PreloadConfig preloadConfig = new RedisStorageProperties.PreloadConfig();
            preloadConfig.setEnabled(true);
            when(properties.getPreload()).thenReturn(preloadConfig);

            storage.initialize();

            verify(cacheWarmer).warmup();
            assertThat(storage.isReady()).isTrue();
        }

        @Test
        void shouldRefreshCaches() {
            storage.initialize();
            storage.refresh();

            verify(personL1Cache).clear();
            verify(organizationL1Cache).clear();
            verify(roleL1Cache).clear();
            verify(privilegeL1Cache).clear();
            verify(invalidationPublisher).publishSecurityRefresh();
        }

        @Test
        void shouldReWarmupOnRefreshWhenPreloadEnabled() {
            RedisStorageProperties.PreloadConfig preloadConfig = new RedisStorageProperties.PreloadConfig();
            preloadConfig.setEnabled(true);
            when(properties.getPreload()).thenReturn(preloadConfig);

            storage.refresh();

            verify(cacheWarmer).warmup();
            verify(invalidationPublisher).publishSecurityRefresh();
        }

        @Test
        void shouldReturnProviderType() {
            assertThat(storage.getProviderType()).isEqualTo("redis");
        }

        @Test
        void shouldReturnCacheWarmer() {
            assertThat(storage.getCacheWarmer()).isEqualTo(cacheWarmer);
        }
    }

    @Nested
    class StatsTests {

        @Test
        void shouldReturnPersonL1Stats() {
            L1Cache.CacheStats stats = new L1Cache.CacheStats(100, 80L, 20L, 5L);
            when(personL1Cache.getStats()).thenReturn(stats);

            assertThat(storage.getPersonL1Stats()).isEqualTo(stats);
        }

        @Test
        void shouldReturnOrganizationL1Stats() {
            L1Cache.CacheStats stats = new L1Cache.CacheStats(50, 40L, 10L, 2L);
            when(organizationL1Cache.getStats()).thenReturn(stats);

            assertThat(storage.getOrganizationL1Stats()).isEqualTo(stats);
        }

        @Test
        void shouldReturnRoleL1Stats() {
            L1Cache.CacheStats stats = new L1Cache.CacheStats(30, 25L, 5L, 1L);
            when(roleL1Cache.getStats()).thenReturn(stats);

            assertThat(storage.getRoleL1Stats()).isEqualTo(stats);
        }

        @Test
        void shouldReturnPrivilegeL1Stats() {
            L1Cache.CacheStats stats = new L1Cache.CacheStats(20, 15L, 5L, 0L);
            when(privilegeL1Cache.getStats()).thenReturn(stats);

            assertThat(storage.getPrivilegeL1Stats()).isEqualTo(stats);
        }
    }
}
