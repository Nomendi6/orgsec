package com.nomendi6.orgsec.storage.redis;

import com.nomendi6.orgsec.common.service.SecurityEventPublisher;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * N1 GET/LIST proof on managed Redis: after-commit notify publishes a new snapshot; rollback
 * leaves the previous READY generation in place.
 */
class RedisSnapshotNotifyVisibilityIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String DATASET_ID = "orgsec-redis-n1-it";
    private static final long PERSON_ID = 2L;

    @BeforeEach
    void clearData() {
        clearRedis();
    }

    @Test
    void committedNotifyPublishesTheNewSnapshotOnGetAndList() {
        MutableSource source = MutableSource.admin("Admin");
        InMemoryFenceStore fenceStore = new InMemoryFenceStore(identity());
        RedisSecurityDataStorage storage = managedStorage(source, fenceStore);
        SecurityEventPublisher publisher = new SecurityEventPublisher(storage);
        AbstractPlatformTransactionManager transactions = new ResourcelessTransactionManager();

        assertThat(storage.getPerson(PERSON_ID).personName).isEqualTo("Admin");

        TransactionStatus status = transactions.getTransaction(TransactionDefinition.withDefaults());
        source.rename("Admin revoked");
        fenceStore.increment();
        publisher.partyRoleChanged(10L);

        assertThat(storage.getPerson(PERSON_ID).personName)
            .as("uncommitted notify must keep the READY generation")
            .isEqualTo("Admin");

        transactions.commit(status);

        assertThat(storage.getPerson(PERSON_ID).personName).isEqualTo("Admin revoked");
        assertThat(storage.getPersons(java.util.List.of(PERSON_ID, 99L)))
            .containsOnlyKeys(PERSON_ID)
            .extractingByKey(PERSON_ID)
            .extracting(person -> person.personName)
            .isEqualTo("Admin revoked");
    }

    @Test
    void rolledBackNotifyLeavesThePreviousSnapshot() {
        MutableSource source = MutableSource.admin("Admin");
        InMemoryFenceStore fenceStore = new InMemoryFenceStore(identity());
        RedisSecurityDataStorage storage = managedStorage(source, fenceStore);
        SecurityEventPublisher publisher = new SecurityEventPublisher(storage);
        AbstractPlatformTransactionManager transactions = new ResourcelessTransactionManager();

        TransactionStatus status = transactions.getTransaction(TransactionDefinition.withDefaults());
        String previous = source.personName();
        source.rename("should not leak");
        fenceStore.increment();
        publisher.partyRoleChanged(10L);
        source.rename(previous);
        fenceStore.decrement();
        transactions.rollback(status);

        assertThat(storage.getPerson(PERSON_ID).personName).isEqualTo("Admin");
        assertThat(storage.getPersons(java.util.List.of(PERSON_ID))).containsOnlyKeys(PERSON_ID);
    }

    @Test
    void noTransactionPublishesImmediately() {
        MutableSource source = MutableSource.admin("Admin");
        InMemoryFenceStore fenceStore = new InMemoryFenceStore(identity());
        RedisSecurityDataStorage storage = managedStorage(source, fenceStore);
        SecurityEventPublisher publisher = new SecurityEventPublisher(storage);

        source.rename("Admin now");
        fenceStore.increment();
        publisher.partyRoleChanged(10L);

        assertThat(storage.getPerson(PERSON_ID).personName).isEqualTo("Admin now");
    }

    private RedisSecurityDataStorage managedStorage(MutableSource source, InMemoryFenceStore fenceStore) {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setSecurityDatasetId(DATASET_ID);
        properties.setEnabled(true);
        properties.getPreload().setBatchSize(8);

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
        RedisSnapshotCoordinator coordinator = new RedisSnapshotCoordinator(
            properties,
            redisConnectionFactory,
            fenceStore,
            source
        );
        storage.attachSnapshotCoordinator(coordinator);
        coordinator.bootstrap();
        assertThat(storage.isReady()).isTrue();
        return storage;
    }

    private static SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity(DATASET_ID, 1);
    }

    @SuppressWarnings("unchecked")
    private static <T> L2RedisCache<T> mockL2() {
        return mock(L2RedisCache.class);
    }

    private static final class MutableSource implements RedisSnapshotLoader {

        private PersonDef person;

        static MutableSource admin(String name) {
            MutableSource source = new MutableSource();
            source.person = person(name);
            return source;
        }

        void rename(String name) {
            person.personName = name;
        }

        String personName() {
            return person.personName;
        }

        @Override
        public void loadSnapshot(RedisBootstrapSession session) {
            PersonDef copy = person(person.personName);
            session.writePersons(Map.of(PERSON_ID, copy));
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

        private static PersonDef person(String name) {
            PersonDef admin = new PersonDef(PERSON_ID, name);
            admin.relatedUserLogin = "admin";
            return admin;
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

        void increment() {
            withLockedFence(fence.get().getIdentity(), LockedFence::incrementContentVersion);
        }

        void decrement() {
            SecurityDatasetFence current = fence.get();
            fence.set(new SecurityDatasetFence(current.getIdentity(), current.getSecurityContentVersion() - 1));
        }
    }

    private static final class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
