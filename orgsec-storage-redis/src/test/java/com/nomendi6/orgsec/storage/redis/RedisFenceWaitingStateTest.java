package com.nomendi6.orgsec.storage.redis;

import com.nomendi6.orgsec.fence.SecurityDatasetFenceStore;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotLoader;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.preload.CacheWarmer;
import com.nomendi6.orgsec.storage.redis.resilience.RedisStorageMigrationRequiredException;
import com.nomendi6.orgsec.storage.redis.resilience.RedisStorageNotReadyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisFenceWaitingStateTest {

    private RedisStorageProperties properties;
    private L1Cache<Long, PersonDef> personL1;
    private L1Cache<Long, OrganizationDef> organizationL1;
    private L1Cache<Long, RoleDef> partyRoleL1;
    private L1Cache<Long, RoleDef> positionRoleL1;
    private L1Cache<String, PrivilegeDef> privilegeL1;
    private L2RedisCache<PersonDef> personL2;
    private L2RedisCache<OrganizationDef> organizationL2;
    private L2RedisCache<RoleDef> roleL2;
    private L2RedisCache<PrivilegeDef> privilegeL2;
    private InvalidationEventPublisher publisher;
    private CacheWarmer cacheWarmer;
    private RedisSecurityDataStorage storage;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new RedisStorageProperties();
        properties.getPreload().setEnabled(true);
        personL1 = new L1Cache<>(10);
        organizationL1 = new L1Cache<>(10);
        partyRoleL1 = new L1Cache<>(10);
        positionRoleL1 = new L1Cache<>(10);
        privilegeL1 = new L1Cache<>(10);
        personL2 = mock(L2RedisCache.class);
        organizationL2 = mock(L2RedisCache.class);
        roleL2 = mock(L2RedisCache.class);
        privilegeL2 = mock(L2RedisCache.class);
        publisher = mock(InvalidationEventPublisher.class);
        cacheWarmer = mock(CacheWarmer.class);
        storage = RedisSecurityDataStorage.managedWaiting(
            properties,
            personL1,
            organizationL1,
            partyRoleL1,
            positionRoleL1,
            privilegeL1,
            personL2,
            organizationL2,
            roleL2,
            privilegeL2,
            new CacheKeyBuilder(false),
            publisher,
            cacheWarmer
        );
        storage.initialize();
    }

    @Test
    void bothLegacyPublicConstructorsRejectDependencyOnlyMigration() {
        assertMigrationRequired(() -> new RedisSecurityDataStorage(
            properties,
            personL1,
            organizationL1,
            partyRoleL1,
            privilegeL1,
            personL2,
            organizationL2,
            roleL2,
            privilegeL2,
            new CacheKeyBuilder(false),
            publisher,
            cacheWarmer
        ));

        assertMigrationRequired(() -> new RedisSecurityDataStorage(
            properties,
            personL1,
            organizationL1,
            partyRoleL1,
            positionRoleL1,
            privilegeL1,
            personL2,
            organizationL2,
            roleL2,
            privilegeL2,
            new CacheKeyBuilder(false),
            publisher,
            cacheWarmer
        ));
    }

    @Test
    void managedDataPlaneHasNoPublicConstructionBypassOrBootstrapSpiOwnership() {
        assertThat(RedisSecurityDataStorage.class.getConstructors()).hasSize(2);
        assertThat(Stream.of(RedisSecurityDataStorage.class.getConstructors())
            .flatMap(constructor -> Stream.of(constructor.getParameterTypes())))
            .noneMatch(RedisFenceWaitingStateTest::isApplicationBootstrapSpi);
        assertThat(Stream.of(RedisSecurityDataStorage.class.getDeclaredFields())
            .map(field -> field.getType()))
            .noneMatch(RedisFenceWaitingStateTest::isApplicationBootstrapSpi);
    }

    @Test
    void initializeWaitsWithoutInvokingLegacyWarmupOrApplicationSpis() {
        assertThat(storage.isReady()).isFalse();

        verifyNoInteractions(cacheWarmer);
        verifyNoInteractions(personL2, organizationL2, roleL2, privilegeL2, publisher);
    }

    @Test
    void everyReadDeniesBeforeTouchingL1OrL2() {
        personL1.put(1L, new PersonDef(1L, "granted"));
        organizationL1.put(1L, new OrganizationDef().setOrganizationId(1L));
        partyRoleL1.put(1L, new RoleDef(1L, "party"));
        positionRoleL1.put(1L, new RoleDef(1L, "position"));
        privilegeL1.put("orders:read", new PrivilegeDef("orders:read", "orders"));

        assertThat(storage.getPerson(1L)).isNull();
        assertThat(storage.getOrganization(1L)).isNull();
        assertThat(storage.getPartyRole(1L)).isNull();
        assertThat(storage.getPositionRole(1L)).isNull();
        assertThat(storage.getPrivilege("orders:read")).isNull();
        assertThat(storage.getPersons(List.of(1L))).isEmpty();
        assertThat(storage.getOrganizations(List.of(1L))).isEmpty();
        assertThat(storage.getRoles(List.of(1L))).isEmpty();

        assertThat(personL1.getStats().getHitCount()).isZero();
        assertThat(personL1.getStats().getMissCount()).isZero();
        assertThat(organizationL1.getStats().getHitCount()).isZero();
        assertThat(partyRoleL1.getStats().getHitCount()).isZero();
        assertThat(positionRoleL1.getStats().getHitCount()).isZero();
        assertThat(privilegeL1.getStats().getHitCount()).isZero();
        verifyNoInteractions(personL2, organizationL2, roleL2, privilegeL2);
    }

    @Test
    void everyMutationAndInvalidationFailsClosedWithoutSideEffects() {
        PersonDef person = new PersonDef(1L, "person");
        OrganizationDef organization = new OrganizationDef().setOrganizationId(1L);
        RoleDef role = new RoleDef(1L, "role");
        PrivilegeDef privilege = new PrivilegeDef("orders:read", "orders");
        PersonDef existingPerson = new PersonDef(9L, "existing-person");
        OrganizationDef existingOrganization = new OrganizationDef().setOrganizationId(9L);
        RoleDef existingPartyRole = new RoleDef(9L, "existing-party-role");
        RoleDef existingPositionRole = new RoleDef(9L, "existing-position-role");
        PrivilegeDef existingPrivilege = new PrivilegeDef("existing:read", "existing");
        personL1.put(9L, existingPerson);
        organizationL1.put(9L, existingOrganization);
        partyRoleL1.put(9L, existingPartyRole);
        positionRoleL1.put(9L, existingPositionRole);
        privilegeL1.put("existing:read", existingPrivilege);

        assertNotReady(() -> storage.updatePerson(1L, person), "updatePerson");
        assertNotReady(() -> storage.updateOrganization(1L, organization), "updateOrganization");
        assertNotReady(() -> storage.updateRole(1L, role), "updateRole");
        assertNotReady(() -> storage.updatePartyRole(1L, role), "updatePartyRole");
        assertNotReady(() -> storage.updatePositionRole(1L, role), "updatePositionRole");
        assertNotReady(() -> storage.updatePrivilege("orders:read", privilege), "updatePrivilege");
        assertNotReady(() -> storage.updatePersons(Map.of(1L, person)), "updatePersons");
        assertNotReady(
            () -> storage.updateOrganizations(Map.of(1L, organization)),
            "updateOrganizations"
        );
        assertNotReady(() -> storage.updateRoles(Map.of(1L, role)), "updateRoles");
        assertNotReady(() -> storage.updatePartyRoles(Map.of(1L, role)), "updatePartyRoles");
        assertNotReady(() -> storage.updatePositionRoles(Map.of(1L, role)), "updatePositionRoles");
        assertNotReady(() -> storage.notifyPersonChanged(1L), "notifyPersonChanged");
        assertNotReady(() -> storage.notifyOrganizationChanged(1L), "notifyOrganizationChanged");
        assertNotReady(() -> storage.notifyPartyRoleChanged(1L), "notifyPartyRoleChanged");
        assertNotReady(() -> storage.notifyPositionRoleChanged(1L), "notifyPositionRoleChanged");
        assertNotReady(storage::refresh, "refresh");

        verifyNoInteractions(cacheWarmer);
        verifyNoInteractions(personL2, organizationL2, roleL2, privilegeL2, publisher);
        assertThat(personL1.get(9L)).isSameAs(existingPerson);
        assertThat(organizationL1.get(9L)).isSameAs(existingOrganization);
        assertThat(partyRoleL1.get(9L)).isSameAs(existingPartyRole);
        assertThat(positionRoleL1.get(9L)).isSameAs(existingPositionRole);
        assertThat(privilegeL1.get("existing:read")).isSameAs(existingPrivilege);
    }

    private static void assertNotReady(Runnable operation, String operationName) {
        assertThatThrownBy(operation::run)
            .isInstanceOf(RedisStorageNotReadyException.class)
            .hasMessageContaining(
                RedisStorageNotReadyException.DIAGNOSTIC_CODE,
                operationName
            );
    }

    private static boolean isApplicationBootstrapSpi(Class<?> type) {
        return type == RedisSnapshotLoader.class
            || type == SecurityDatasetFenceStore.class;
    }

    private static void assertMigrationRequired(Runnable construction) {
        assertThatThrownBy(construction::run)
            .isInstanceOf(RedisStorageMigrationRequiredException.class)
            .hasMessageContaining(
                RedisStorageMigrationRequiredException.DIAGNOSTIC_CODE,
                "legacy <= 1.0.5 constructor"
            );
    }
}
