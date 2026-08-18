package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
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
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import com.nomendi6.orgsec.storage.redis.integration.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fault cases for the library-owned snapshot coordinator: one writer at a time, a lease that
 * expires mid-load fails closed, and a lost Redis connection denies GET/LIST.
 */
class RedisSnapshotCoordinatorFaultIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String DATASET_ID = "orgsec-redis-fault-it";
    private static final long ADMIN_PERSON_ID = 2L;
    private static final String DOCUMENT_READ = "DOCUMENT_READ";

    @BeforeEach
    void clearData() {
        clearRedis();
    }

    @Test
    void aContendingWriterStaysFailClosedUntilItCanAdoptTheReadySnapshot() throws Exception {
        SeedSource source = SeedSource.generatedAppSeed();
        RedisSnapshotCoordinator writer = coordinator(blockingUntilReleased(source));
        RedisSnapshotCoordinator contender = coordinator(source);

        CountDownLatch enteredLoad = blockingEntered();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> writing = pool.submit(() -> {
                writer.bootstrap();
                return null;
            });
            assertThat(enteredLoad.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Void> competing = pool.submit(() -> {
                contender.bootstrap();
                return null;
            });
            competing.get(10, TimeUnit.SECONDS);
            assertThat(contender.isReady()).isFalse();
            assertThat(contender.person(ADMIN_PERSON_ID)).isNull();
            assertThat(contender.persons(java.util.List.of(ADMIN_PERSON_ID))).isEmpty();

            releaseBlockedLoad();
            writing.get(10, TimeUnit.SECONDS);
            assertThat(writer.isReady()).isTrue();
            assertThat(writer.person(ADMIN_PERSON_ID).personName).isEqualTo("Admin");

            contender.bootstrap();
            assertThat(contender.isReady()).isTrue();
            assertThat(contender.person(ADMIN_PERSON_ID).relatedUserLogin).isEqualTo("admin");
            assertThat(contender.privilege(DOCUMENT_READ)).isNotNull();
        } finally {
            releaseBlockedLoad();
            pool.shutdownNow();
        }
    }

    @Test
    void expiredWriterLeaseFailsClosedAndDoesNotServeAPartialSnapshot() throws Exception {
        SeedSource source = SeedSource.generatedAppSeed();
        RedisSnapshotCoordinator coordinator = coordinator(
            sleepThenLoad(source, Duration.ofMillis(800)),
            Duration.ofMillis(250)
        );

        coordinator.bootstrap();

        assertThat(coordinator.isReady()).isFalse();
        assertThat(coordinator.person(ADMIN_PERSON_ID)).isNull();
        assertThat(coordinator.persons(java.util.List.of(ADMIN_PERSON_ID))).isEmpty();
    }

    @Test
    void lostRedisConnectionDeniesGetAndList() {
        SeedSource source = SeedSource.generatedAppSeed();
        LettuceConnectionFactory isolated = isolatedFactory();
        try {
            RedisSnapshotCoordinator coordinator = new RedisSnapshotCoordinator(
                properties(),
                isolated,
                new NonLockingFenceStore(identity()),
                source
            );
            coordinator.bootstrap();
            assertThat(coordinator.isReady()).isTrue();
            assertThat(coordinator.person(ADMIN_PERSON_ID)).isNotNull();

            isolated.destroy();

            assertThat(coordinator.isReady()).isFalse();
            assertThat(coordinator.person(ADMIN_PERSON_ID)).isNull();
            assertThat(coordinator.persons(java.util.List.of(ADMIN_PERSON_ID))).isEmpty();
        } finally {
            isolated.destroy();
        }
    }

    private RedisSnapshotCoordinator coordinator(RedisSnapshotLoader loader) {
        return coordinator(loader, Duration.ofSeconds(60));
    }

    private RedisSnapshotCoordinator coordinator(
        RedisSnapshotLoader loader,
        Duration lease
    ) {
        return new RedisSnapshotCoordinator(
            properties(),
            redisConnectionFactory,
            new NonLockingFenceStore(identity()),
            loader,
            lease
        );
    }

    private static RedisStorageProperties properties() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setSecurityDatasetId(DATASET_ID);
        properties.setEnabled(true);
        properties.getPreload().setBatchSize(8);
        return properties;
    }

    private static SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity(DATASET_ID, 1);
    }

    private static LettuceConnectionFactory isolatedFactory() {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
            getRedisHost(),
            getRedisPort()
        );
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server);
        factory.afterPropertiesSet();
        return factory;
    }

    private final CountDownLatch enteredLoad = new CountDownLatch(1);
    private final CountDownLatch releaseLoad = new CountDownLatch(1);

    private CountDownLatch blockingEntered() {
        return enteredLoad;
    }

    private void releaseBlockedLoad() {
        releaseLoad.countDown();
    }

    private RedisSnapshotLoader blockingUntilReleased(SeedSource source) {
        return session -> {
            enteredLoad.countDown();
            try {
                if (!releaseLoad.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("writer was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("writer interrupted", interrupted);
            }
            source.loadSnapshot(session);
        };
    }

    private static RedisSnapshotLoader sleepThenLoad(SeedSource source, Duration delay) {
        return session -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("lease-expiry loader interrupted", interrupted);
            }
            source.loadSnapshot(session);
        };
    }

    private static final class SeedSource implements RedisSnapshotLoader {

        private PersonDef person;
        private OrganizationDef organization;
        private RoleDef partyRole;
        private RoleDef positionRole;
        private PrivilegeDef privilege;

        static SeedSource generatedAppSeed() {
            SeedSource source = new SeedSource();
            OrganizationDef company = new OrganizationDef();
            company.organizationId = 1L;
            company.organizationName = "Owner";
            company.pathId = "1";
            company.parentPath = "|1|";
            company.companyId = 1L;
            company.companyParentPath = "|1|";
            source.organization = company;

            PersonDef admin = new PersonDef(ADMIN_PERSON_ID, "Admin");
            admin.relatedUserLogin = "admin";
            admin.defaultCompanyId = 1L;
            admin.defaultOrgunitId = 1L;
            admin.organizationsMap.put(1L, company);
            source.person = admin;

            source.partyRole = new RoleDef(10L, "ADMIN");
            source.partyRole.securityPrivilegeSet.add(DOCUMENT_READ);
            source.positionRole = new RoleDef(20L, "DIR");
            source.privilege = new PrivilegeDef(DOCUMENT_READ, "DOCUMENT")
                .allowOperation(PrivilegeOperation.READ)
                .allowOrg(PrivilegeDirection.EXACT, PrivilegeDirection.EXACT, false);
            return source;
        }

        @Override
        public void loadSnapshot(RedisBootstrapSession session) {
            session.writePersons(Map.of(person.personId, copyPerson(person)));
            session.completeFamily(RedisSnapshotFamily.PERSONS);
            session.writeOrganizations(Map.of(
                organization.organizationId,
                copyOrganization(organization)
            ));
            session.completeFamily(RedisSnapshotFamily.ORGANIZATIONS);
            session.writePartyRoles(Map.of(partyRole.roleId, copyRole(partyRole)));
            session.completeFamily(RedisSnapshotFamily.PARTY_ROLES);
            session.writePositionRoles(Map.of(positionRole.roleId, copyRole(positionRole)));
            session.completeFamily(RedisSnapshotFamily.POSITION_ROLES);
            session.writeRoles(Map.of(partyRole.roleId, copyRole(partyRole)));
            session.completeFamily(RedisSnapshotFamily.ROLES);
            session.writePrivileges(Map.of(privilege.name, copyPrivilege(privilege)));
            session.completeFamily(RedisSnapshotFamily.PRIVILEGES);
        }

        private static PersonDef copyPerson(PersonDef source) {
            PersonDef copy = new PersonDef(source.personId, source.personName);
            copy.relatedUserLogin = source.relatedUserLogin;
            copy.defaultCompanyId = source.defaultCompanyId;
            copy.defaultOrgunitId = source.defaultOrgunitId;
            copy.organizationsMap = new LinkedHashMap<>();
            source.organizationsMap.forEach((id, org) -> copy.organizationsMap.put(id, copyOrganization(org)));
            return copy;
        }

        private static OrganizationDef copyOrganization(OrganizationDef source) {
            OrganizationDef copy = new OrganizationDef();
            copy.organizationId = source.organizationId;
            copy.organizationName = source.organizationName;
            copy.pathId = source.pathId;
            copy.parentPath = source.parentPath;
            copy.companyId = source.companyId;
            copy.companyParentPath = source.companyParentPath;
            return copy;
        }

        private static RoleDef copyRole(RoleDef source) {
            RoleDef copy = new RoleDef(source.roleId, source.name);
            copy.securityPrivilegeSet.addAll(source.securityPrivilegeSet);
            return copy;
        }

        private static PrivilegeDef copyPrivilege(PrivilegeDef source) {
            return new PrivilegeDef(source.name, source.resourceName)
                .allowOperation(source.operation)
                .allowAll(source.all)
                .allowOrg(source.company, source.org, source.person);
        }
    }

    /**
     * Independent fence copies so two coordinators can both enter publish and contend on the
     * Redis writer lease. Production instances share one DB fence and do not do this.
     */
    private static final class NonLockingFenceStore implements SecurityDatasetFenceStore {

        private final AtomicReference<SecurityDatasetFence> fence;

        private NonLockingFenceStore(SecurityDatasetIdentity identity) {
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
