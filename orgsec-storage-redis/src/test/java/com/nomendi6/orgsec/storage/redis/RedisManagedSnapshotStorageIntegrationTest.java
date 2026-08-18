package com.nomendi6.orgsec.storage.redis;

import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetFenceStore;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisBootstrapSession;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotLoader;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import com.nomendi6.orgsec.storage.redis.integration.AbstractRedisIntegrationTest;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.preload.CacheWarmer;
import com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves managed GET/LIST never fall through to the legacy L1/L2 data plane once a snapshot
 * coordinator is attached. Seed shape matches the generated orgsec-redis application's admin
 * person (id=2, login=admin).
 */
class RedisManagedSnapshotStorageIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String DATASET_ID = "orgsec-redis-storage-it";

    @BeforeEach
    void clearData() {
        clearRedis();
    }

    @Test
    void managedGetServesCoordinatorViewAndIgnoresLegacyCaches() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setSecurityDatasetId(DATASET_ID);
        properties.setEnabled(true);

        RedisSecurityDataStorage storage = RedisSecurityDataStorage.managedWaiting(
            properties,
            new L1Cache<>(8),
            new L1Cache<>(8),
            new L1Cache<>(8),
            new L1Cache<>(8),
            new L1Cache<>(8),
            mockL2(),
            mockL2(),
            mockL2(),
            mockL2(),
            new CacheKeyBuilder(false),
            mock(InvalidationEventPublisher.class),
            new CacheWarmer(properties.getPreload())
        );
        storage.initialize();
        assertThat(storage.isReady()).isFalse();
        assertThat(storage.getPerson(2L)).isNull();

        InMemoryFenceStore fenceStore = new InMemoryFenceStore(
            new SecurityDatasetIdentity(DATASET_ID, 1)
        );
        RedisSnapshotCoordinator coordinator = new RedisSnapshotCoordinator(
            properties,
            redisConnectionFactory,
            fenceStore,
            new AdminPersonLoader()
        );
        storage.attachSnapshotCoordinator(coordinator);
        coordinator.bootstrap();

        assertThat(storage.isReady()).isTrue();
        PersonDef person = storage.getPerson(2L);
        assertThat(person).isNotNull();
        assertThat(person.relatedUserLogin).isEqualTo("admin");
        assertThat(storage.getPrivilege("DOCUMENT_READ").operation)
            .isEqualTo(PrivilegeOperation.READ);
        assertThat(storage.getPersons(java.util.List.of(2L, 3L))).containsOnlyKeys(2L);

        person.personName = "poison";
        assertThat(storage.getPerson(2L).personName).isEqualTo("Admin");
    }

    @SuppressWarnings("unchecked")
    private static <T> L2RedisCache<T> mockL2() {
        return mock(L2RedisCache.class);
    }

    private static final class AdminPersonLoader implements RedisSnapshotLoader {

        @Override
        public void loadSnapshot(RedisBootstrapSession session) {
            PersonDef person = new PersonDef(2L, "Admin");
            person.relatedUserLogin = "admin";
            session.writePersons(Map.of(2L, person));
            session.completeFamily(RedisSnapshotFamily.PERSONS);

            OrganizationDef company = new OrganizationDef();
            company.organizationId = 1L;
            company.organizationName = "Owner";
            session.writeOrganizations(Map.of(1L, company));
            session.completeFamily(RedisSnapshotFamily.ORGANIZATIONS);

            session.writePartyRoles(Map.of(10L, new RoleDef(10L, "ADMIN")));
            session.completeFamily(RedisSnapshotFamily.PARTY_ROLES);
            session.writePositionRoles(Map.of());
            session.completeFamily(RedisSnapshotFamily.POSITION_ROLES);
            session.writeRoles(Map.of());
            session.completeFamily(RedisSnapshotFamily.ROLES);
            session.writePrivileges(Map.of(
                "DOCUMENT_READ",
                new PrivilegeDef("DOCUMENT_READ", "DOCUMENT")
                    .allowOperation(PrivilegeOperation.READ)
            ));
            session.completeFamily(RedisSnapshotFamily.PRIVILEGES);
        }
    }

    private static final class InMemoryFenceStore implements SecurityDatasetFenceStore {

        private final AtomicReference<SecurityDatasetFence> fence;

        private InMemoryFenceStore(SecurityDatasetIdentity identity) {
            this.fence = new AtomicReference<>(new SecurityDatasetFence(identity, 1));
        }

        @Override
        public <T> T withLockedFence(
            SecurityDatasetIdentity expectedIdentity,
            LockedFenceWork<T> work
        ) {
            SecurityDatasetFence current = fence.get();
            if (!current.getIdentity().equals(expectedIdentity)) {
                throw new IllegalStateException("fence identity mismatch");
            }
            return work.execute(new LockedFence() {
                @Override
                public SecurityDatasetFence current() {
                    return fence.get();
                }

                @Override
                public SecurityDatasetFence incrementContentVersion() {
                    SecurityDatasetFence next = fence.get().nextContentVersion();
                    fence.set(next);
                    return next;
                }
            });
        }
    }
}
