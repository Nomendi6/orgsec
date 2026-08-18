package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCoordinatorLeaseTest {

    private static final String SHA_B = "0123456789abcdef".repeat(4);
    private static final String DATASET_HASH =
        "80a707af7dc77ee1228f9127180f3964835e5beb4c4ab0d812f0fe7593579b3a";
    private static final String PRIMARY_RUN_ID = "a".repeat(40);
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID OWNER_SESSION_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID ACQUISITION_ID =
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final long ISSUED_AT = 1_700_000_000_000L;
    private static final long EXPIRES_AT = ISSUED_AT + 5_000L;

    private final RedisCoordinatorLeaseCodec codec = new RedisCoordinatorLeaseCodec();

    @Test
    void activeStateNeedsExactCompositeFenceContextAndRedisTime() {
        RedisCoordinatorLease.Unverified candidate = activeCandidate();

        RedisCoordinatorLease.Verified verified = verifyActive(
            candidate,
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            ISSUED_AT
        );
        RedisCoordinatorLease.Verified again = verifyActive(
            codec.decode(codec.encode(verified)),
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            EXPIRES_AT - 1
        );

        assertThat(candidate).isExactlyInstanceOf(RedisCoordinatorLease.Unverified.class);
        assertThat(verified).isExactlyInstanceOf(RedisCoordinatorLease.Verified.class);
        assertThat(verified.identity()).isEqualTo(identity());
        assertThat(verified.datasetHash()).isEqualTo(DATASET_HASH);
        assertThat(verified.incarnation()).isEqualTo(incarnation());
        assertThat(verified.boundControlCounter()).isEqualTo(7);
        assertThat(verified.state()).isEqualTo(RedisCoordinatorLease.State.ACTIVE);
        assertThat(verified.revision()).isEqualTo(9);
        assertThat(verified.fencingSequence()).isEqualTo(11);
        assertThat(verified.ownerSessionId()).isEqualTo(OWNER_SESSION_ID);
        assertThat(verified.acquisitionId()).isEqualTo(ACQUISITION_ID);
        assertThat(verified.issuedAtRedisMillis()).isEqualTo(ISSUED_AT);
        assertThat(verified.expiresAtRedisMillis()).isEqualTo(EXPIRES_AT);
        assertThat(again).isEqualTo(verified).hasSameHashCodeAs(verified);

        assertThat(Modifier.isFinal(RedisCoordinatorLease.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(RedisCoordinatorLease.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(RedisCoordinatorLease.class.getDeclaredConstructors()))
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Modifier.isFinal(RedisCoordinatorLease.Unverified.class.getModifiers()))
            .isTrue();
        assertThat(Modifier.isFinal(RedisCoordinatorLease.Verified.class.getModifiers()))
            .isTrue();
    }

    @Test
    void freeStateIsTrustableOnlyWithExactRetainedSequenceAndRevision() {
        RedisCoordinatorLease.Unverified candidate = freeCandidate();

        RedisCoordinatorLease.Verified verified = RedisCoordinatorLease.verifyFreeForContext(
            candidate,
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            10,
            11
        );

        assertThat(verified.state()).isEqualTo(RedisCoordinatorLease.State.FREE);
        assertThat(verified.fencingSequence()).isEqualTo(11);
        assertThat(verified.revision()).isEqualTo(10);
        assertThat(verified.ownerSessionId()).isNull();
        assertThat(verified.acquisitionId()).isNull();
        assertThat(verified.issuedAtRedisMillis()).isZero();
        assertThat(verified.expiresAtRedisMillis()).isZero();

        assertVerification(
            expectVerification(() -> RedisCoordinatorLease.verifyFreeForContext(
                candidate,
                identity(),
                DATASET_HASH,
                incarnation(),
                7,
                9,
                11
            )),
            RedisCoordinatorLeaseVerificationException.Reason.REVISION_MISMATCH
        );
        assertVerification(
            expectVerification(() -> RedisCoordinatorLease.verifyFreeForContext(
                candidate,
                identity(),
                DATASET_HASH,
                incarnation(),
                7,
                10,
                10
            )),
            RedisCoordinatorLeaseVerificationException.Reason.FENCING_SEQUENCE_MISMATCH
        );
    }

    @Test
    void rejectsEveryIdentityAndRuntimeMismatchWithFieldSpecificTypedReason() {
        List<Mismatch> mismatches = List.of(
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_DATASET_ID,
                "tenant-b",
                RedisCoordinatorLeaseVerificationException.Reason.DATASET_ID_MISMATCH
            ),
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_DATASET_HASH,
                SHA_B,
                RedisCoordinatorLeaseVerificationException.Reason.DATASET_HASH_MISMATCH
            ),
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID,
                "b".repeat(40),
                RedisCoordinatorLeaseVerificationException.Reason.PRIMARY_RUN_ID_MISMATCH
            ),
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_STORAGE_UUID,
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                RedisCoordinatorLeaseVerificationException.Reason.STORAGE_UUID_MISMATCH
            ),
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER,
                "8",
                RedisCoordinatorLeaseVerificationException.Reason
                    .BOUND_CONTROL_COUNTER_MISMATCH
            ),
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_REVISION,
                "10",
                RedisCoordinatorLeaseVerificationException.Reason.REVISION_MISMATCH
            ),
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE,
                "12",
                RedisCoordinatorLeaseVerificationException.Reason.FENCING_SEQUENCE_MISMATCH
            ),
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID,
                "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                RedisCoordinatorLeaseVerificationException.Reason.OWNER_SESSION_ID_MISMATCH
            ),
            mismatch(
                RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID,
                "ffffffff-ffff-4fff-8fff-ffffffffffff",
                RedisCoordinatorLeaseVerificationException.Reason.ACQUISITION_ID_MISMATCH
            )
        );

        for (Mismatch mismatch : mismatches) {
            Map<String, String> fields = activeFields();
            fields.put(mismatch.field(), mismatch.value());
            RedisCoordinatorLeaseVerificationException exception = expectVerification(() ->
                verifyCanonicalActive(codec.decode(fields), ISSUED_AT)
            );
            assertVerification(exception, mismatch.reason());
            assertThat(exception.getMessage()).doesNotContain(mismatch.value());
        }
    }

    @Test
    void stateSpecificVerificationNeverConfusesFreeAndActiveRecords() {
        assertVerification(
            expectVerification(() -> RedisCoordinatorLease.verifyFreeForContext(
                activeCandidate(),
                identity(),
                DATASET_HASH,
                incarnation(),
                7,
                9,
                11
            )),
            RedisCoordinatorLeaseVerificationException.Reason.STATE_MISMATCH
        );
        assertVerification(
            expectVerification(() -> verifyCanonicalActive(freeCandidate(), ISSUED_AT)),
            RedisCoordinatorLeaseVerificationException.Reason.STATE_MISMATCH
        );
    }

    @Test
    void exactTimeContextRejectsTamperedExtensionBeforeHalfOpenValidityCheck() {
        Map<String, String> extended = activeFields();
        extended.put(
            RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS,
            Long.toString(EXPIRES_AT + 60_000)
        );
        assertVerification(
            expectVerification(() -> verifyCanonicalActive(
                codec.decode(extended),
                EXPIRES_AT - 1
            )),
            RedisCoordinatorLeaseVerificationException.Reason
                .EXPIRES_AT_REDIS_MILLIS_MISMATCH
        );

        Map<String, String> shiftedIssued = activeFields();
        shiftedIssued.put(
            RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS,
            Long.toString(ISSUED_AT - 1)
        );
        assertVerification(
            expectVerification(() -> verifyCanonicalActive(
                codec.decode(shiftedIssued),
                ISSUED_AT
            )),
            RedisCoordinatorLeaseVerificationException.Reason
                .ISSUED_AT_REDIS_MILLIS_MISMATCH
        );
    }

    @Test
    void validityUsesHalfOpenIntervalFromExplicitRedisTime() {
        assertVerification(
            expectVerification(() -> verifyCanonicalActive(
                activeCandidate(),
                ISSUED_AT - 1
            )),
            RedisCoordinatorLeaseVerificationException.Reason.LEASE_NOT_YET_VALID
        );
        assertVerification(
            expectVerification(() -> verifyCanonicalActive(
                activeCandidate(),
                EXPIRES_AT
            )),
            RedisCoordinatorLeaseVerificationException.Reason.LEASE_EXPIRED
        );
        assertThat(verifyCanonicalActive(activeCandidate(), ISSUED_AT)).isNotNull();
        assertThat(verifyCanonicalActive(activeCandidate(), EXPIRES_AT - 1)).isNotNull();
    }

    @Test
    void trustedDatasetHashMustBeCanonicalAndDerivedFromExactIdentity() {
        assertThatThrownBy(() -> verifyActive(
            activeCandidate(),
            identity(),
            SHA_B,
            incarnation(),
            7,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            ISSUED_AT
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("expectedDatasetHash must match expectedIdentity.securityDatasetId");
        assertThatThrownBy(() -> verifyActive(
            activeCandidate(),
            identity(),
            DATASET_HASH.toUpperCase(),
            incarnation(),
            7,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            ISSUED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
            "datasetHash"
        );
    }

    @Test
    void rejectsNullNonV4AndOutOfRangeTrustedContextBeforeGrantingAuthority() {
        assertThatThrownBy(() -> verifyActive(
            null,
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            ISSUED_AT
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("candidate");
        assertThatThrownBy(() -> verifyActive(
            activeCandidate(),
            identity(),
            DATASET_HASH,
            incarnation(),
            -1,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            ISSUED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
            "expectedBoundControlCounter"
        );
        assertThatThrownBy(() -> verifyActive(
            activeCandidate(),
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            9,
            11,
            UUID.fromString("aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa"),
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            ISSUED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
            "expectedOwnerSessionId"
        );
        assertThatThrownBy(() -> verifyActive(
            activeCandidate(),
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            9,
            RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER + 1,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            ISSUED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
            "expectedFencingSequence"
        );
        assertThatThrownBy(() -> verifyActive(
            activeCandidate(),
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            EXPIRES_AT,
            ISSUED_AT,
            ISSUED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
            "expectedExpiresAtRedisMillis"
        );
    }

    @Test
    void futureProtocolCannotCreateOrVerifyLeaseAuthority() {
        SecurityDatasetIdentity futureIdentity = new SecurityDatasetIdentity("sensitive-dataset", 2);

        assertThatThrownBy(() -> RedisCoordinatorLease.unverified(
            futureIdentity,
            DATASET_HASH,
            incarnation(),
            7,
            RedisCoordinatorLease.State.ACTIVE,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");

        assertThatThrownBy(() -> verifyActive(
            activeCandidate(),
            futureIdentity,
            DATASET_HASH,
            incarnation(),
            7,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            ISSUED_AT
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
    }

    private RedisCoordinatorLease.Unverified activeCandidate() {
        return codec.decode(activeFields());
    }

    private RedisCoordinatorLease.Unverified freeCandidate() {
        return codec.decode(freeFields());
    }

    private RedisCoordinatorLease.Verified verifyCanonicalActive(
        RedisCoordinatorLease.Unverified candidate,
        long observedRedisTimeMillis
    ) {
        return verifyActive(
            candidate,
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            9,
            11,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            ISSUED_AT,
            EXPIRES_AT,
            observedRedisTimeMillis
        );
    }

    private static RedisCoordinatorLease.Verified verifyActive(
        RedisCoordinatorLease.Unverified candidate,
        SecurityDatasetIdentity expectedIdentity,
        String expectedDatasetHash,
        RedisIncarnation expectedIncarnation,
        long expectedBoundControlCounter,
        long expectedRevision,
        long expectedFencingSequence,
        UUID expectedOwnerSessionId,
        UUID expectedAcquisitionId,
        long expectedIssuedAtRedisMillis,
        long expectedExpiresAtRedisMillis,
        long observedRedisTimeMillis
    ) {
        return RedisCoordinatorLease.verifyActiveForContext(
            candidate,
            expectedIdentity,
            expectedDatasetHash,
            expectedIncarnation,
            expectedBoundControlCounter,
            expectedRevision,
            expectedFencingSequence,
            expectedOwnerSessionId,
            expectedAcquisitionId,
            expectedIssuedAtRedisMillis,
            expectedExpiresAtRedisMillis,
            observedRedisTimeMillis
        );
    }

    private static Map<String, String> activeFields() {
        Map<String, String> fields = baseFields();
        fields.put(RedisCoordinatorLeaseCodec.FIELD_STATE, "ACTIVE");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_REVISION, "9");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE, "11");
        fields.put(
            RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID,
            OWNER_SESSION_ID.toString()
        );
        fields.put(
            RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID,
            ACQUISITION_ID.toString()
        );
        fields.put(
            RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS,
            Long.toString(ISSUED_AT)
        );
        fields.put(
            RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS,
            Long.toString(EXPIRES_AT)
        );
        return fields;
    }

    private static Map<String, String> freeFields() {
        Map<String, String> fields = baseFields();
        fields.put(RedisCoordinatorLeaseCodec.FIELD_STATE, "FREE");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_REVISION, "10");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE, "11");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID, "");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID, "");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS, "0");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS, "0");
        return fields;
    }

    private static Map<String, String> baseFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put(RedisCoordinatorLeaseCodec.FIELD_SCHEMA_VERSION, "1");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID, "tenant-a");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_DATASET_HASH, DATASET_HASH);
        fields.put(RedisCoordinatorLeaseCodec.FIELD_PROTOCOL_VERSION, "1");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID, PRIMARY_RUN_ID);
        fields.put(RedisCoordinatorLeaseCodec.FIELD_STORAGE_UUID, STORAGE_UUID.toString());
        fields.put(RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER, "7");
        return fields;
    }

    private static SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity("tenant-a", 1);
    }

    private static RedisIncarnation incarnation() {
        return new RedisIncarnation(PRIMARY_RUN_ID, STORAGE_UUID);
    }

    private static Mismatch mismatch(
        String field,
        String value,
        RedisCoordinatorLeaseVerificationException.Reason reason
    ) {
        return new Mismatch(field, value, reason);
    }

    private static RedisCoordinatorLeaseVerificationException expectVerification(
        Runnable action
    ) {
        try {
            action.run();
        } catch (RedisCoordinatorLeaseVerificationException exception) {
            return exception;
        }
        throw new AssertionError("expected RedisCoordinatorLeaseVerificationException");
    }

    private static void assertVerification(
        RedisCoordinatorLeaseVerificationException exception,
        RedisCoordinatorLeaseVerificationException.Reason reason
    ) {
        assertThat(exception.reason()).isEqualTo(reason);
        assertThat(exception.getMessage()).isEqualTo(
            RedisCoordinatorLeaseVerificationException.DIAGNOSTIC_CODE + ":" + reason.name()
        );
    }

    private record Mismatch(
        String field,
        String value,
        RedisCoordinatorLeaseVerificationException.Reason reason
    ) {
    }
}
