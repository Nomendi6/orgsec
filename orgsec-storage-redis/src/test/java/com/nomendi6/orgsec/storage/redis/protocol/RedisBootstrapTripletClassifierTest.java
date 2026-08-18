package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisBootstrapTripletClassifierTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final String OLD_RUN_ID = "fedcba9876543210fedcba9876543210fedcba98";
    private static final String OTHER_SHA = "0123456789abcdef".repeat(4);
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID OTHER_STORAGE_UUID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID SNAPSHOT_ID =
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @Test
    void exhaustivelyClassifiesAllEightPresenceMasks() {
        Fixture fixture = fixture("tenant-a", RUN_ID, STORAGE_UUID, 7, 4, 2, 2);

        for (int mask = 0; mask < 8; mask++) {
            RedisBootstrapTripletObservation observation = new RedisBootstrapTripletObservation(
                fixture.keyspace,
                primary(RUN_ID),
                (mask & 1) == 0 ? null : fixture.control,
                (mask & 2) == 0 ? null : fixture.lease,
                (mask & 4) == 0 ? null : Long.valueOf(fixture.counter)
            );

            RedisBootstrapTripletAssessment assessment =
                RedisBootstrapTripletClassifier.assess(observation);
            RedisBootstrapTripletAssessment.Kind expected = mask == 0
                ? RedisBootstrapTripletAssessment.Kind.TRIPLET_ABSENT
                : mask == 7
                    ? RedisBootstrapTripletAssessment.Kind.COHERENT_SAME_RUN_ID
                    : RedisBootstrapTripletAssessment.Kind.PARTIAL;
            assertThat(assessment.kind()).as("presence mask %s", mask).isEqualTo(expected);
            if (expected == RedisBootstrapTripletAssessment.Kind.PARTIAL) {
                assertThat(assessment.reason()).contains(
                    RedisBootstrapTripletAssessment.Reason.PARTIAL_TRIPLET
                );
            } else {
                assertThat(assessment.reason()).isEmpty();
            }
        }
    }

    @Test
    void distinguishesOnlyStructuralSameAndDifferentRunIdsAndAcceptsCounterGaps() {
        Fixture same = fixture("tenant-a", RUN_ID, STORAGE_UUID, 7, 4, 2, 5);
        Fixture different = fixture("tenant-a", OLD_RUN_ID, STORAGE_UUID, 7, 4, 2, 5);

        assertThat(assess(same, RUN_ID).kind()).isEqualTo(
            RedisBootstrapTripletAssessment.Kind.COHERENT_SAME_RUN_ID
        );
        assertThat(assess(different, RUN_ID).kind()).isEqualTo(
            RedisBootstrapTripletAssessment.Kind.COHERENT_DIFFERENT_RUN_ID
        );

        RedisCoordinatorLease.Unverified active = RedisCoordinatorLease.unverified(
            different.control.getIdentity(),
            different.keyspace.datasetHash(),
            different.control.getIncarnation(),
            different.control.getCounter(),
            RedisCoordinatorLease.State.ACTIVE,
            4,
            2,
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            100,
            200
        );
        RedisBootstrapTripletObservation activeObservation = new RedisBootstrapTripletObservation(
            different.keyspace,
            primary(RUN_ID),
            different.control,
            active,
            5L
        );
        assertThat(RedisBootstrapTripletClassifier.assess(activeObservation).kind())
            .isEqualTo(RedisBootstrapTripletAssessment.Kind.COHERENT_DIFFERENT_RUN_ID);
    }

    @Test
    void rejectsEveryCrossRecordInvariantMismatchWithAnAllowListedReason() {
        Fixture valid = fixture("tenant-a", RUN_ID, STORAGE_UUID, 7, 4, 2, 2);

        Fixture otherDataset = fixture("tenant-b", RUN_ID, STORAGE_UUID, 7, 4, 2, 2);
        assertReason(
            new RedisBootstrapTripletObservation(
                valid.keyspace,
                primary(RUN_ID),
                otherDataset.control,
                otherDataset.lease,
                otherDataset.counter
            ),
            RedisBootstrapTripletAssessment.Reason.CONTROL_DATASET_MISMATCH
        );
        assertReason(
            new RedisBootstrapTripletObservation(
                valid.keyspace,
                primary(RUN_ID),
                valid.control,
                otherDataset.lease,
                otherDataset.counter
            ),
            RedisBootstrapTripletAssessment.Reason.LEASE_DATASET_MISMATCH
        );

        RedisCoordinatorLease.Unverified wrongHash = freeLease(
            valid.control.getIdentity(),
            OTHER_SHA,
            valid.control.getIncarnation(),
            7,
            4,
            2
        );
        assertReason(
            observation(valid, wrongHash, 2),
            RedisBootstrapTripletAssessment.Reason.LEASE_DATASET_HASH_MISMATCH
        );

        RedisCoordinatorLease.Unverified wrongIncarnation = freeLease(
            valid.control.getIdentity(),
            valid.keyspace.datasetHash(),
            new RedisIncarnation(RUN_ID, OTHER_STORAGE_UUID),
            7,
            4,
            2
        );
        assertReason(
            observation(valid, wrongIncarnation, 2),
            RedisBootstrapTripletAssessment.Reason.INCARNATION_MISMATCH
        );

        RedisCoordinatorLease.Unverified wrongBinding = freeLease(
            valid.control.getIdentity(),
            valid.keyspace.datasetHash(),
            valid.control.getIncarnation(),
            6,
            4,
            2
        );
        assertReason(
            observation(valid, wrongBinding, 2),
            RedisBootstrapTripletAssessment.Reason.BOUND_CONTROL_COUNTER_MISMATCH
        );

        RedisCoordinatorLease.Unverified ahead = freeLease(
            valid.control.getIdentity(),
            valid.keyspace.datasetHash(),
            valid.control.getIncarnation(),
            7,
            4,
            3
        );
        assertReason(
            observation(valid, ahead, 2),
            RedisBootstrapTripletAssessment.Reason.COUNTER_BEHIND_FENCING_SEQUENCE
        );
    }

    @Test
    void structurallyIndistinguishableHistoriesRemainIndistinguishable() {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        RedisBootstrapTripletObservation virgin = new RedisBootstrapTripletObservation(
            keyspace,
            primary(RUN_ID),
            null,
            null,
            null
        );
        RedisBootstrapTripletObservation flushed = new RedisBootstrapTripletObservation(
            keyspace,
            primary(RUN_ID),
            null,
            null,
            null
        );
        assertThat(RedisBootstrapTripletClassifier.assess(virgin))
            .isEqualTo(RedisBootstrapTripletClassifier.assess(flushed));

        Fixture legitimateRestart = fixture(
            "tenant-a", OLD_RUN_ID, STORAGE_UUID, 7, 4, 2, 5
        );
        Fixture coherentRollbackClone = fixture(
            "tenant-a", OLD_RUN_ID, STORAGE_UUID, 7, 4, 2, 5
        );
        assertThat(assess(legitimateRestart, RUN_ID))
            .isEqualTo(assess(coherentRollbackClone, RUN_ID));
    }

    @Test
    void exposesNoPlanAuthorityFactoryOrSensitiveDiagnostics() {
        assertThat(Modifier.isPublic(RedisBootstrapTripletClassifier.class.getModifiers()))
            .isFalse();
        assertThat(Modifier.isPublic(RedisBootstrapTripletAssessment.class.getModifiers()))
            .isFalse();
        assertThat(RedisBootstrapTripletClassifier.class.getDeclaredConstructors())
            .allSatisfy(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers()))
                .isTrue());

        Set<Class<?>> forbiddenReturnTypes = Set.of(
            RedisIncarnation.class,
            RedisControlEnvelope.class,
            RedisCoordinatorLease.Verified.class,
            UUID.class
        );
        for (Class<?> type : ListSupport.classes(
            RedisBootstrapTripletClassifier.class,
            RedisBootstrapTripletAssessment.class
        )) {
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(name).doesNotContain("plan", "allowed", "authorize", "bootstrap");
                assertThat(forbiddenReturnTypes).doesNotContain(method.getReturnType());
            }
        }

        Fixture fixture = fixture("secret-tenant", RUN_ID, STORAGE_UUID, 7, 4, 2, 5);
        RedisBootstrapTripletObservation observation = observation(
            fixture,
            fixture.lease,
            fixture.counter
        );
        String rendered = observation + " " + RedisBootstrapTripletClassifier.assess(observation);
        assertThat(rendered)
            .doesNotContain("secret-tenant", RUN_ID, STORAGE_UUID.toString(), "counter=5");
    }

    @Test
    void observationRejectsInvalidDirectCounterValuesAndNullInputs() {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        assertThatThrownBy(() -> new RedisBootstrapTripletObservation(
            keyspace,
            primary(RUN_ID),
            null,
            null,
            -1L
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaseCounter");
        assertThatThrownBy(() -> new RedisBootstrapTripletObservation(
            keyspace,
            primary(RUN_ID),
            null,
            null,
            RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER + 1
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("leaseCounter");
        assertThatThrownBy(() -> RedisBootstrapTripletClassifier.assess(null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("observation");
    }

    private static RedisBootstrapTripletAssessment assess(Fixture fixture, String currentRunId) {
        return RedisBootstrapTripletClassifier.assess(new RedisBootstrapTripletObservation(
            fixture.keyspace,
            primary(currentRunId),
            fixture.control,
            fixture.lease,
            fixture.counter
        ));
    }

    private static RedisBootstrapTripletObservation observation(
        Fixture fixture,
        RedisCoordinatorLease.Unverified lease,
        long counter
    ) {
        return new RedisBootstrapTripletObservation(
            fixture.keyspace,
            primary(RUN_ID),
            fixture.control,
            lease,
            counter
        );
    }

    private static void assertReason(
        RedisBootstrapTripletObservation observation,
        RedisBootstrapTripletAssessment.Reason expected
    ) {
        RedisBootstrapTripletAssessment assessment =
            RedisBootstrapTripletClassifier.assess(observation);
        assertThat(assessment.kind()).isEqualTo(
            RedisBootstrapTripletAssessment.Kind.INCOHERENT
        );
        assertThat(assessment.reason()).contains(expected);
    }

    private static Fixture fixture(
        String datasetId,
        String storedRunId,
        UUID storageUuid,
        long controlCounter,
        long revision,
        long sequence,
        long counter
    ) {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(datasetId);
        SecurityDatasetIdentity identity = identity(datasetId);
        RedisIncarnation incarnation = new RedisIncarnation(storedRunId, storageUuid);
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            incarnation,
            controlCounter,
            SNAPSHOT_ID,
            RedisControlState.READY
        );
        return new Fixture(
            keyspace,
            control,
            freeLease(
                identity,
                keyspace.datasetHash(),
                incarnation,
                controlCounter,
                revision,
                sequence
            ),
            counter
        );
    }

    private static SecurityDatasetIdentity identity(String datasetId) {
        return new SecurityDatasetIdentity(datasetId, 1);
    }

    private static RedisCoordinatorLease.Unverified freeLease(
        SecurityDatasetIdentity identity,
        String datasetHash,
        RedisIncarnation incarnation,
        long boundControlCounter,
        long revision,
        long sequence
    ) {
        return RedisCoordinatorLease.unverified(
            identity,
            datasetHash,
            incarnation,
            boundControlCounter,
            RedisCoordinatorLease.State.FREE,
            revision,
            sequence,
            null,
            null,
            0,
            0
        );
    }

    private static RedisPrimaryObservation primary(String runId) {
        return new RedisPrimaryObservation(runId, "master", false, "noeviction");
    }

    private record Fixture(
        RedisDatasetKeyspace keyspace,
        RedisControlEnvelope control,
        RedisCoordinatorLease.Unverified lease,
        long counter
    ) {
    }

    private static final class ListSupport {

        private ListSupport() {
        }

        static List<Class<?>> classes(Class<?> first, Class<?> second) {
            return List.of(first, second);
        }
    }
}
