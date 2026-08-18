package com.nomendi6.orgsec.storage.redis.bootstrap;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatingRedisBootstrapSessionTest {

    @Test
    void delegatesImmutableBoundedBatchesForEveryRequiredFamilyAndSeals() {
        RecordingBatchSink sink = new RecordingBatchSink();
        SecurityDatasetFence fence = datasetFence();
        ValidatingRedisBootstrapSession session = session(fence, 2, sink);

        Map<Long, PersonDef> persons = new HashMap<>();
        persons.put(1L, new PersonDef(1L, "one"));
        persons.put(2L, new PersonDef(2L, "two"));

        assertThat(session.getDatasetFence()).isSameAs(fence);
        assertThat(session.getPreferredBatchSize()).isEqualTo(2);

        session.writePersons(persons);
        session.writeOrganizations(Map.of(10L, new OrganizationDef().setOrganizationId(10L)));
        session.writePartyRoles(Map.of(20L, new RoleDef(20L, "party")));
        session.writePositionRoles(Map.of(30L, new RoleDef(30L, "position")));
        session.writeRoles(Map.of(40L, new RoleDef(40L, "legacy")));
        session.writePrivileges(Map.of("orders:read", new PrivilegeDef("orders:read", "orders")));

        persons.clear();
        assertThat(sink.personBatches).singleElement().satisfies(batch -> {
            assertThat(batch).containsOnlyKeys(1L, 2L);
            assertThatThrownBy(() -> batch.put(3L, new PersonDef(3L, "three")))
                .isInstanceOf(UnsupportedOperationException.class);
        });
        assertThat(sink.organizationBatches).hasSize(1);
        assertThat(sink.partyRoleBatches).hasSize(1);
        assertThat(sink.positionRoleBatches).hasSize(1);
        assertThat(sink.roleBatches).hasSize(1);
        assertThat(sink.privilegeBatches).hasSize(1);

        completeEveryFamily(session);
        session.seal();

        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.SEALED);
    }

    @Test
    void acceptsAnExplicitlyCompletedEmptySnapshot() {
        ValidatingRedisBootstrapSession session = session(datasetFence(), 10, new RecordingBatchSink());

        completeEveryFamily(session);
        session.seal();

        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.SEALED);
    }

    @Test
    void supportsLargeFixturesOnlyWhenLoaderSplitsThemIntoBoundedBatches() {
        RecordingBatchSink sink = new RecordingBatchSink();
        ValidatingRedisBootstrapSession session = session(datasetFence(), 2, sink);

        session.writePersons(Map.of(
            1L, new PersonDef(1L, "one"),
            2L, new PersonDef(2L, "two")
        ));
        session.writePersons(Map.of(3L, new PersonDef(3L, "three")));

        assertThat(sink.personBatches).extracting(Map::size).containsExactly(2, 1);
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.OPEN);
    }

    @Test
    void abortsWhenABatchExceedsTheAdvertisedLimit() {
        RecordingBatchSink sink = new RecordingBatchSink();
        ValidatingRedisBootstrapSession session = session(datasetFence(), 1, sink);

        assertThatThrownBy(() -> session.writePersons(Map.of(
            1L, new PersonDef(1L, "one"),
            2L, new PersonDef(2L, "two")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeding the limit of 1");

        assertThat(sink.personBatches).isEmpty();
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void rejectsKeysThatDoNotMatchEmbeddedDomainIdentities() {
        assertIdentityMismatch(
            session -> session.writePersons(Map.of(1L, new PersonDef(2L, "person"))),
            "personId"
        );
        assertIdentityMismatch(
            session -> session.writeOrganizations(
                Map.of(1L, new OrganizationDef().setOrganizationId(2L))
            ),
            "organizationId"
        );
        assertIdentityMismatch(
            session -> session.writePartyRoles(Map.of(1L, new RoleDef(2L, "party"))),
            "roleId"
        );
        assertIdentityMismatch(
            session -> session.writePositionRoles(Map.of(1L, new RoleDef(2L, "position"))),
            "roleId"
        );
        assertIdentityMismatch(
            session -> session.writeRoles(Map.of(1L, new RoleDef(2L, "legacy"))),
            "roleId"
        );
        assertIdentityMismatch(
            session -> session.writePrivileges(
                Map.of("orders:read", new PrivilegeDef("orders:write", "orders"))
            ),
            "name"
        );
    }

    @Test
    void rejectsDuplicateKeysAcrossBatchesOfTheSameFamily() {
        RecordingBatchSink sink = new RecordingBatchSink();
        ValidatingRedisBootstrapSession session = session(datasetFence(), 2, sink);
        session.writePersons(Map.of(1L, new PersonDef(1L, "first")));

        assertThatThrownBy(() -> session.writePersons(Map.of(1L, new PersonDef(1L, "second"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("written more than once");

        assertThat(sink.personBatches).hasSize(1);
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void rejectsBlankPrivilegeIdentityEvenWhenMapKeyMatches() {
        ValidatingRedisBootstrapSession session =
            session(datasetFence(), 2, new RecordingBatchSink());

        assertThatThrownBy(() -> session.writePrivileges(Map.of("", new PrivilegeDef("", "orders"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match", "name");
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void abortsWhenLoaderOmitsARequiredFamily() {
        ValidatingRedisBootstrapSession session = session(datasetFence(), 10, new RecordingBatchSink());
        EnumSet.complementOf(EnumSet.of(RedisSnapshotFamily.PRIVILEGES))
            .forEach(session::completeFamily);

        assertThatThrownBy(session::seal)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PRIVILEGES");

        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void abortsOnDuplicateCompletion() {
        ValidatingRedisBootstrapSession session = session(datasetFence(), 10, new RecordingBatchSink());
        session.completeFamily(RedisSnapshotFamily.PERSONS);

        assertThatThrownBy(() -> session.completeFamily(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already complete");

        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void abortsOnWriteAfterFamilyCompletion() {
        RecordingBatchSink sink = new RecordingBatchSink();
        ValidatingRedisBootstrapSession session = session(datasetFence(), 10, sink);
        session.completeFamily(RedisSnapshotFamily.ROLES);

        assertThatThrownBy(() -> session.writeRoles(Map.of(1L, new RoleDef(1L, "late"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already complete");

        assertThat(sink.roleBatches).isEmpty();
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void rejectsNullBatchAndNullEntriesAndAborts() {
        ValidatingRedisBootstrapSession nullBatchSession =
            session(datasetFence(), 10, new RecordingBatchSink());

        assertThatThrownBy(() -> nullBatchSession.writeOrganizations(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be null");
        assertThat(nullBatchSession.state())
            .isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);

        ValidatingRedisBootstrapSession nullEntrySession =
            session(datasetFence(), 10, new RecordingBatchSink());
        Map<String, PrivilegeDef> privileges = new HashMap<>();
        privileges.put("orders:read", null);

        assertThatThrownBy(() -> nullEntrySession.writePrivileges(privileges))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null keys or values");
        assertThat(nullEntrySession.state())
            .isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void abortsWhenTheBatchSinkFails() {
        RedisSnapshotBatchSink failingSink = new RecordingBatchSink() {
            @Override
            public void writePartyRoles(Map<Long, RoleDef> partyRoles) {
                throw new IllegalStateException("Redis write failed");
            }
        };
        ValidatingRedisBootstrapSession session = session(datasetFence(), 10, failingSink);

        assertThatThrownBy(() -> session.writePartyRoles(Map.of(1L, new RoleDef(1L, "party"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Redis write failed");
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
        assertThatThrownBy(session::getDatasetFence)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not open");
    }

    @Test
    void abortsWhenUsedFromAnotherThread() throws InterruptedException {
        ValidatingRedisBootstrapSession session = session(datasetFence(), 10, new RecordingBatchSink());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreignThread = new Thread(() -> {
            try {
                session.getPreferredBatchSize();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        foreignThread.start();
        foreignThread.join();

        assertThat(failure.get())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owner thread");
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void explicitAbortAlsoInvalidatesASealedSession() {
        ValidatingRedisBootstrapSession session = session(datasetFence(), 10, new RecordingBatchSink());
        completeEveryFamily(session);
        session.seal();

        session.abort();

        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    @Test
    void validatesConstructionAndCompletionArguments() {
        SecurityDatasetFence fence = datasetFence();
        RecordingBatchSink sink = new RecordingBatchSink();

        assertThatThrownBy(() -> new ValidatingRedisBootstrapSession(null, 1, sink))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("datasetFence");
        assertThatThrownBy(() -> new ValidatingRedisBootstrapSession(fence, 0, sink))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> new ValidatingRedisBootstrapSession(fence, 1, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("sink");

        SecurityDatasetIdentity futureIdentity = new SecurityDatasetIdentity("sensitive-dataset", 2);
        assertThat(futureIdentity.getProtocolVersion()).isEqualTo(2);
        assertThatThrownBy(() -> new ValidatingRedisBootstrapSession(
            new SecurityDatasetFence(futureIdentity, 4),
            1,
            sink
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Redis wire protocol requires protocol version 1")
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
        assertThat(sink.personBatches).isEmpty();
        assertThat(sink.organizationBatches).isEmpty();
        assertThat(sink.partyRoleBatches).isEmpty();
        assertThat(sink.positionRoleBatches).isEmpty();
        assertThat(sink.roleBatches).isEmpty();
        assertThat(sink.privilegeBatches).isEmpty();

        ValidatingRedisBootstrapSession session = session(fence, 1, sink);
        assertThatThrownBy(() -> session.completeFamily(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("family");
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    private static ValidatingRedisBootstrapSession session(
        SecurityDatasetFence fence,
        int batchSize,
        RedisSnapshotBatchSink sink
    ) {
        return new ValidatingRedisBootstrapSession(fence, batchSize, sink);
    }

    private static SecurityDatasetFence datasetFence() {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("orders-production", 1);
        return new SecurityDatasetFence(identity, 4);
    }

    private static void completeEveryFamily(RedisBootstrapSession session) {
        List.of(
            RedisSnapshotFamily.PERSONS,
            RedisSnapshotFamily.ORGANIZATIONS,
            RedisSnapshotFamily.PARTY_ROLES,
            RedisSnapshotFamily.POSITION_ROLES,
            RedisSnapshotFamily.ROLES,
            RedisSnapshotFamily.PRIVILEGES
        ).forEach(session::completeFamily);
    }

    private static void assertIdentityMismatch(
        java.util.function.Consumer<RedisBootstrapSession> invalidWrite,
        String identityField
    ) {
        ValidatingRedisBootstrapSession session =
            session(datasetFence(), 10, new RecordingBatchSink());

        assertThatThrownBy(() -> invalidWrite.accept(session))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match", identityField);
        assertThat(session.state()).isEqualTo(ValidatingRedisBootstrapSession.State.ABORTED);
    }

    private static class RecordingBatchSink implements RedisSnapshotBatchSink {

        private final List<Map<Long, PersonDef>> personBatches = new ArrayList<>();
        private final List<Map<Long, OrganizationDef>> organizationBatches = new ArrayList<>();
        private final List<Map<Long, RoleDef>> partyRoleBatches = new ArrayList<>();
        private final List<Map<Long, RoleDef>> positionRoleBatches = new ArrayList<>();
        private final List<Map<Long, RoleDef>> roleBatches = new ArrayList<>();
        private final List<Map<String, PrivilegeDef>> privilegeBatches = new ArrayList<>();

        @Override
        public void writePersons(Map<Long, PersonDef> persons) {
            personBatches.add(persons);
        }

        @Override
        public void writeOrganizations(Map<Long, OrganizationDef> organizations) {
            organizationBatches.add(organizations);
        }

        @Override
        public void writePartyRoles(Map<Long, RoleDef> partyRoles) {
            partyRoleBatches.add(partyRoles);
        }

        @Override
        public void writePositionRoles(Map<Long, RoleDef> positionRoles) {
            positionRoleBatches.add(positionRoles);
        }

        @Override
        public void writeRoles(Map<Long, RoleDef> roles) {
            roleBatches.add(roles);
        }

        @Override
        public void writePrivileges(Map<String, PrivilegeDef> privileges) {
            privilegeBatches.add(privileges);
        }
    }
}
