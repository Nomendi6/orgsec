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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end cold-start against a real standalone Redis, using the same six-family snapshot
 * shape a generated OrgSec Redis application would load from its source database.
 *
 * <p>The generated {@code orgsec-redis} fixture still warms L1/L2 via {@code update*}. This
 * test exercises the 1.1 contract that replaces that path: fence + loader + READY + GET.</p>
 */
class RedisSnapshotCoordinatorIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String DATASET_ID = "orgsec-redis-it";
    private static final long ADMIN_PERSON_ID = 2L;
    private static final long COMPANY_ID = 1L;
    private static final long PARTY_ROLE_ID = 10L;
    private static final long POSITION_ROLE_ID = 20L;
    private static final String DOCUMENT_READ = "DOCUMENT_READ";

    @BeforeEach
    void clearData() {
        clearRedis();
    }

    @Test
    void coldStartPublishesReadySnapshotAndServesDefensiveCopies() {
        InMemoryFenceStore fenceStore = new InMemoryFenceStore(identity());
        MutableSource source = MutableSource.generatedAppSeed();
        RedisSnapshotCoordinator coordinator = coordinator(fenceStore, source);

        coordinator.bootstrap();

        assertThat(coordinator.isReady()).isTrue();
        PersonDef admin = coordinator.person(ADMIN_PERSON_ID);
        assertThat(admin).isNotNull();
        assertThat(admin.personName).isEqualTo("Admin");
        assertThat(admin.relatedUserLogin).isEqualTo("admin");
        assertThat(admin.organizationsMap).containsKey(COMPANY_ID);
        assertThat(coordinator.organization(COMPANY_ID).organizationName).isEqualTo("Owner");
        assertThat(coordinator.partyRole(PARTY_ROLE_ID).name).isEqualTo("ADMIN");
        assertThat(coordinator.positionRole(POSITION_ROLE_ID).name).isEqualTo("DIR");
        assertThat(coordinator.privilege(DOCUMENT_READ).operation)
            .isEqualTo(PrivilegeOperation.READ);
        assertThat(coordinator.persons(java.util.List.of(ADMIN_PERSON_ID, 99L)))
            .containsOnlyKeys(ADMIN_PERSON_ID);

        admin.personName = "mutated";
        PersonDef reread = coordinator.person(ADMIN_PERSON_ID);
        assertThat(reread.personName).isEqualTo("Admin");
        assertThat(reread).isNotSameAs(admin);
    }

    @Test
    void refreshAfterSourceRevokeStopsServingTheOldGrant() {
        InMemoryFenceStore fenceStore = new InMemoryFenceStore(identity());
        MutableSource source = MutableSource.generatedAppSeed();
        RedisSnapshotCoordinator coordinator = coordinator(fenceStore, source);

        coordinator.bootstrap();
        assertThat(coordinator.privilege(DOCUMENT_READ)).isNotNull();
        assertThat(coordinator.person(ADMIN_PERSON_ID)).isNotNull();

        source.revokeDocumentReadAndRenameAdmin();
        fenceStore.increment();
        coordinator.refresh();

        assertThat(coordinator.isReady()).isTrue();
        assertThat(coordinator.privilege(DOCUMENT_READ)).isNull();
        assertThat(coordinator.person(ADMIN_PERSON_ID).personName).isEqualTo("Admin revoked");
    }

    @Test
    void peerInstanceAdoptsTheReadySnapshotWithoutHoldingTheWriterLease() {
        InMemoryFenceStore fenceStore = new InMemoryFenceStore(identity());
        MutableSource source = MutableSource.generatedAppSeed();
        RedisSnapshotCoordinator writer = coordinator(fenceStore, source);
        writer.bootstrap();
        assertThat(writer.isReady()).isTrue();

        RedisSnapshotCoordinator peer = coordinator(fenceStore, source);
        peer.bootstrap();

        assertThat(peer.isReady()).isTrue();
        assertThat(peer.person(ADMIN_PERSON_ID).relatedUserLogin).isEqualTo("admin");
        assertThat(peer.privilege(DOCUMENT_READ)).isNotNull();
    }

    private RedisSnapshotCoordinator coordinator(
        SecurityDatasetFenceStore fenceStore,
        MutableSource source
    ) {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setSecurityDatasetId(DATASET_ID);
        properties.setEnabled(true);
        properties.getPreload().setBatchSize(8);
        return new RedisSnapshotCoordinator(
            properties,
            redisConnectionFactory,
            fenceStore,
            source
        );
    }

    private static SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity(DATASET_ID, 1);
    }

    /**
     * Source-shaped snapshot used by generated Redis apps: persons, orgs, party/position roles
     * and privileges are written as six explicit families, not a merged L2 dump.
     */
    private static final class MutableSource implements RedisSnapshotLoader {

        private PersonDef person;
        private OrganizationDef organization;
        private RoleDef partyRole;
        private RoleDef positionRole;
        private PrivilegeDef privilege;

        static MutableSource generatedAppSeed() {
            MutableSource source = new MutableSource();
            OrganizationDef company = new OrganizationDef();
            company.organizationId = COMPANY_ID;
            company.organizationName = "Owner";
            company.pathId = "1";
            company.parentPath = "|1|";
            company.companyId = COMPANY_ID;
            company.companyParentPath = "|1|";
            source.organization = company;

            PersonDef admin = new PersonDef(ADMIN_PERSON_ID, "Admin");
            admin.relatedUserId = "admin";
            admin.relatedUserLogin = "admin";
            admin.defaultCompanyId = COMPANY_ID;
            admin.defaultOrgunitId = COMPANY_ID;
            admin.organizationsMap.put(COMPANY_ID, company);
            source.person = admin;

            source.partyRole = new RoleDef(PARTY_ROLE_ID, "ADMIN");
            source.partyRole.securityPrivilegeSet.add(DOCUMENT_READ);
            source.positionRole = new RoleDef(POSITION_ROLE_ID, "DIR");
            source.privilege = new PrivilegeDef(DOCUMENT_READ, "DOCUMENT")
                .allowOperation(PrivilegeOperation.READ)
                .allowOrg(PrivilegeDirection.EXACT, PrivilegeDirection.EXACT, false);
            return source;
        }

        void revokeDocumentReadAndRenameAdmin() {
            person.personName = "Admin revoked";
            privilege = null;
            partyRole.securityPrivilegeSet.clear();
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

            if (privilege != null) {
                session.writePrivileges(Map.of(privilege.name, copyPrivilege(privilege)));
            }
            session.completeFamily(RedisSnapshotFamily.PRIVILEGES);
        }

        private static PersonDef copyPerson(PersonDef source) {
            PersonDef copy = new PersonDef(source.personId, source.personName);
            copy.relatedUserId = source.relatedUserId;
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

    private static final class InMemoryFenceStore implements SecurityDatasetFenceStore {

        private final AtomicReference<SecurityDatasetFence> fence;
        private final Thread owner = Thread.currentThread();

        private InMemoryFenceStore(SecurityDatasetIdentity identity) {
            this.fence = new AtomicReference<>(new SecurityDatasetFence(identity, 1));
        }

        @Override
        public <T> T withLockedFence(
            SecurityDatasetIdentity expectedIdentity,
            LockedFenceWork<T> work
        ) {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("fence is owner-thread confined");
            }
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
    }
}
