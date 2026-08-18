package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisCoordinatorLeaseCodecTest {

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

    private static final List<String> LUA_SAFE_FIELDS = List.of(
        RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER,
        RedisCoordinatorLeaseCodec.FIELD_REVISION,
        RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE,
        RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS,
        RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS
    );

    private final RedisCoordinatorLeaseCodec codec = new RedisCoordinatorLeaseCodec();

    @Test
    void exposesExactOrderedFieldsAndTightTransportBounds() {
        List<String> expectedOrder = List.of(
            RedisCoordinatorLeaseCodec.FIELD_SCHEMA_VERSION,
            RedisCoordinatorLeaseCodec.FIELD_DATASET_ID,
            RedisCoordinatorLeaseCodec.FIELD_DATASET_HASH,
            RedisCoordinatorLeaseCodec.FIELD_PROTOCOL_VERSION,
            RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID,
            RedisCoordinatorLeaseCodec.FIELD_STORAGE_UUID,
            RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER,
            RedisCoordinatorLeaseCodec.FIELD_STATE,
            RedisCoordinatorLeaseCodec.FIELD_REVISION,
            RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE,
            RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID,
            RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID,
            RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS,
            RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS
        );
        Map<String, Integer> expectedLimits = Map.ofEntries(
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_SCHEMA_VERSION, 16),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID, 256),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_DATASET_HASH, 64),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_PROTOCOL_VERSION, 10),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID, 40),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_STORAGE_UUID, 36),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER, 16),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_STATE, 6),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_REVISION, 16),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE, 16),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID, 36),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID, 36),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS, 16),
            Map.entry(RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS, 16)
        );

        assertThat(RedisCoordinatorLeaseCodec.orderedFields())
            .containsExactlyElementsOf(expectedOrder);
        assertThat(RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT).isEqualTo(14);
        assertThatThrownBy(() -> RedisCoordinatorLeaseCodec.orderedFields().add("future"))
            .isInstanceOf(UnsupportedOperationException.class);
        for (String field : expectedOrder) {
            assertThat(RedisCoordinatorLeaseCodec.maxUtf8Bytes(field))
                .as(field)
                .isEqualTo(expectedLimits.get(field));
        }
        assertThat(RedisCoordinatorLeaseCodec.allowsEmpty(
            RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID
        )).isTrue();
        assertThat(RedisCoordinatorLeaseCodec.allowsEmpty(
            RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID
        )).isTrue();
        assertThat(RedisCoordinatorLeaseCodec.allowsEmpty(
            RedisCoordinatorLeaseCodec.FIELD_STATE
        )).isFalse();
        assertThatThrownBy(() -> RedisCoordinatorLeaseCodec.maxUtf8Bytes("secret-field"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("field must be a known coordinator lease field")
            .hasMessageNotContaining("secret-field");
    }

    @Test
    void activeRoundTripStaysUnverifiedUntilExactContextAndEncodesCanonically()
        throws Exception {
        RedisCoordinatorLease.Unverified decoded = codec.decode(activeFields());

        assertThat(decoded.identity()).isEqualTo(identity());
        assertThat(decoded.datasetHash()).isEqualTo(DATASET_HASH);
        assertThat(decoded.incarnation()).isEqualTo(incarnation());
        assertThat(decoded.boundControlCounter()).isEqualTo(7);
        assertThat(decoded.state()).isEqualTo(RedisCoordinatorLease.State.ACTIVE);
        assertThat(decoded.revision()).isEqualTo(9);
        assertThat(decoded.fencingSequence()).isEqualTo(11);
        assertThat(decoded.ownerSessionId()).isEqualTo(OWNER_SESSION_ID);
        assertThat(decoded.acquisitionId()).isEqualTo(ACQUISITION_ID);
        assertThat(decoded.issuedAtRedisMillis()).isEqualTo(ISSUED_AT);
        assertThat(decoded.expiresAtRedisMillis()).isEqualTo(EXPIRES_AT);

        RedisCoordinatorLease.Verified verified = verifyActive(decoded, ISSUED_AT);
        Map<String, String> encoded = codec.encode(verified);
        assertThat(new ArrayList<>(encoded.keySet()))
            .containsExactlyElementsOf(RedisCoordinatorLeaseCodec.orderedFields());
        assertThat(encoded).containsExactlyEntriesOf(activeFields());
        assertThatThrownBy(() -> encoded.put("unknown", "value"))
            .isInstanceOf(UnsupportedOperationException.class);

        Method encode = RedisCoordinatorLeaseCodec.class.getDeclaredMethod(
            "encode",
            RedisCoordinatorLease.Verified.class
        );
        assertThat(encode.getParameterTypes())
            .containsExactly(RedisCoordinatorLease.Verified.class)
            .doesNotContain(RedisCoordinatorLease.Unverified.class);
    }

    @Test
    void freeRoundTripRetainsSequenceAndClearsEveryGrantSpecificField() {
        RedisCoordinatorLease.Unverified decoded = codec.decode(freeFields());

        assertThat(decoded.state()).isEqualTo(RedisCoordinatorLease.State.FREE);
        assertThat(decoded.fencingSequence()).isEqualTo(11);
        assertThat(decoded.ownerSessionId()).isNull();
        assertThat(decoded.acquisitionId()).isNull();
        assertThat(decoded.issuedAtRedisMillis()).isZero();
        assertThat(decoded.expiresAtRedisMillis()).isZero();

        RedisCoordinatorLease.Verified verified = RedisCoordinatorLease.verifyFreeForContext(
            decoded,
            identity(),
            DATASET_HASH,
            incarnation(),
            7,
            10,
            11
        );
        assertThat(codec.encode(verified)).containsExactlyEntriesOf(freeFields());
        assertThat(RedisCoordinatorLeaseCodec.encodeBoundedUtf8Value(
            RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID,
            ""
        )).isEmpty();
        assertThat(RedisCoordinatorLeaseCodec.decodeBoundedUtf8Value(
            RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID,
            new byte[0]
        )).isEmpty();
    }

    @Test
    void acceptsExact256ByteUnicodeDatasetIdAndRejectsTheNextCharacter() {
        String maximum = "ž".repeat(128);
        Map<String, String> maximumFields = activeFields();
        maximumFields.put(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID, maximum);
        assertThat(codec.decode(maximumFields).identity().getSecurityDatasetId())
            .isEqualTo(maximum);

        Map<String, String> oversized = activeFields();
        oversized.put(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID, maximum + "ž");
        assertCorruption(
            expectCorruption(() -> codec.decode(oversized)),
            RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_TOO_LARGE
        );
    }

    @Test
    void boundedUtf8HelpersRejectDisallowedEmptyOversizedAndMalformedValues() {
        assertEncoding(
            expectEncoding(() -> RedisCoordinatorLeaseCodec.encodeBoundedUtf8Value(
                RedisCoordinatorLeaseCodec.FIELD_STATE,
                ""
            )),
            RedisCoordinatorLeaseEncodingException.Reason.FIELD_VALUE_EMPTY
        );
        assertEncoding(
            expectEncoding(() -> RedisCoordinatorLeaseCodec.encodeBoundedUtf8Value(
                RedisCoordinatorLeaseCodec.FIELD_DATASET_ID,
                "tenant-\ud800"
            )),
            RedisCoordinatorLeaseEncodingException.Reason.FIELD_UTF8_INVALID
        );
        assertCorruption(
            expectCorruption(() -> RedisCoordinatorLeaseCodec.decodeBoundedUtf8Value(
                RedisCoordinatorLeaseCodec.FIELD_DATASET_ID,
                new byte[]{(byte) 0xc3, 0x28}
            )),
            RedisCoordinatorLeaseCorruptionException.Reason.FIELD_UTF8_INVALID
        );
        assertCorruption(
            expectCorruption(() -> RedisCoordinatorLeaseCodec.decodeBoundedUtf8Value(
                RedisCoordinatorLeaseCodec.FIELD_DATASET_ID,
                new byte[257]
            )),
            RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_TOO_LARGE
        );
    }

    @Test
    void rejectsNullMissingUnknownNullAndEmptyFieldsWithSanitizedDiagnostics() {
        assertCorruption(
            expectCorruption(() -> codec.decode(null)),
            RedisCoordinatorLeaseCorruptionException.Reason.NULL_MAP
        );

        Map<String, String> missing = activeFields();
        missing.remove(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID);
        RedisCoordinatorLeaseCorruptionException missingFailure = expectCorruption(
            () -> codec.decode(missing)
        );
        assertCorruption(
            missingFailure,
            RedisCoordinatorLeaseCorruptionException.Reason.FIELD_SET_MISMATCH
        );

        Map<String, String> unknown = activeFields();
        unknown.remove(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID);
        unknown.put("attacker-secret-field", "attacker-secret-value");
        RedisCoordinatorLeaseCorruptionException unknownFailure = expectCorruption(
            () -> codec.decode(unknown)
        );
        assertThat(unknownFailure.getMessage()).isEqualTo(missingFailure.getMessage());
        assertThat(unknownFailure.getMessage()).doesNotContain("attacker", "secret");

        Map<String, String> nullValue = activeFields();
        nullValue.put(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID, null);
        assertCorruption(
            expectCorruption(() -> codec.decode(nullValue)),
            RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_EMPTY
        );
        assertFieldCorrupt(
            RedisCoordinatorLeaseCodec.FIELD_DATASET_HASH,
            "",
            RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_EMPTY
        );
    }

    @Test
    void rejectsUnknownSchemaBlankDatasetAndInvalidProtocolVersion() {
        for (String value : List.of("0", "2", "01", "+1", "-1")) {
            assertFieldCorrupt(
                RedisCoordinatorLeaseCodec.FIELD_SCHEMA_VERSION,
                value,
                RedisCoordinatorLeaseCorruptionException.Reason.SCHEMA_VERSION_INVALID
            );
        }
        for (String value : List.of(" ", "\t\n")) {
            assertFieldCorrupt(
                RedisCoordinatorLeaseCodec.FIELD_DATASET_ID,
                value,
                RedisCoordinatorLeaseCorruptionException.Reason.DATASET_ID_INVALID
            );
        }
        for (String value : List.of(
            "0", "2", "01", "+1", "-1", "1.0", "2147483648"
        )) {
            assertFieldCorrupt(
                RedisCoordinatorLeaseCodec.FIELD_PROTOCOL_VERSION,
                value,
                RedisCoordinatorLeaseCorruptionException.Reason.PROTOCOL_VERSION_INVALID
            );
        }
    }

    @Test
    void arithmeticFieldsRejectEveryNonCanonicalOrNonLuaSafeInteger() {
        for (String field : LUA_SAFE_FIELDS) {
            for (String value : List.of(
                "-1",
                "00",
                "+1",
                "1.0",
                "9007199254740992"
            )) {
                assertFieldCorrupt(
                    field,
                    value,
                    RedisCoordinatorLeaseCorruptionException.Reason.NUMBER_INVALID
                );
            }
        }

        Map<String, String> maximum = activeFields();
        maximum.put(
            RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER,
            Long.toString(RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER)
        );
        maximum.put(
            RedisCoordinatorLeaseCodec.FIELD_REVISION,
            Long.toString(RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER)
        );
        maximum.put(
            RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE,
            Long.toString(RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER)
        );
        maximum.put(
            RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS,
            Long.toString(RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER - 1)
        );
        maximum.put(
            RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS,
            Long.toString(RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER)
        );
        assertThat(codec.decode(maximum)).isNotNull();
    }

    @Test
    void rejectsUppercaseMalformedDigestsRunIdsAndUuids() {
        for (String value : List.of(
            DATASET_HASH.toUpperCase(),
            DATASET_HASH.substring(1),
            "g".repeat(64)
        )) {
            assertFieldCorrupt(
                RedisCoordinatorLeaseCodec.FIELD_DATASET_HASH,
                value,
                RedisCoordinatorLeaseCorruptionException.Reason.DIGEST_INVALID
            );
        }
        for (String value : List.of(
            PRIMARY_RUN_ID.toUpperCase(),
            PRIMARY_RUN_ID.substring(1),
            "g".repeat(40)
        )) {
            assertFieldCorrupt(
                RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID,
                value,
                RedisCoordinatorLeaseCorruptionException.Reason.PRIMARY_RUN_ID_INVALID
            );
        }
        for (String field : List.of(
            RedisCoordinatorLeaseCodec.FIELD_STORAGE_UUID,
            RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID,
            RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID
        )) {
            for (String value : List.of(
                STORAGE_UUID.toString().toUpperCase(),
                "1-1-1-1-1",
                "not-a-uuid",
                "aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa",
                "bbbbbbbb-bbbb-4bbb-cbbb-bbbbbbbbbbbb"
            )) {
                assertFieldCorrupt(
                    field,
                    value,
                    RedisCoordinatorLeaseCorruptionException.Reason.UUID_INVALID
                );
            }
        }
    }

    @Test
    void stateGrammarAndCrossFieldInvariantsAreStrict() {
        for (String value : List.of("free", "Active", "IDLE", "FUTURE")) {
            assertFieldCorrupt(
                RedisCoordinatorLeaseCodec.FIELD_STATE,
                value,
                RedisCoordinatorLeaseCorruptionException.Reason.STATE_INVALID
            );
        }

        for (String field : List.of(
            RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID,
            RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID
        )) {
            Map<String, String> freeWithGrant = freeFields();
            freeWithGrant.put(field, OWNER_SESSION_ID.toString());
            assertCorruption(
                expectCorruption(() -> codec.decode(freeWithGrant)),
                RedisCoordinatorLeaseCorruptionException.Reason.STATE_FIELDS_INVALID
            );
            Map<String, String> activeWithoutGrant = activeFields();
            activeWithoutGrant.put(field, "");
            assertCorruption(
                expectCorruption(() -> codec.decode(activeWithoutGrant)),
                RedisCoordinatorLeaseCorruptionException.Reason.UUID_INVALID
            );
        }

        for (String field : List.of(
            RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE,
            RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS,
            RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS
        )) {
            Map<String, String> activeZero = activeFields();
            activeZero.put(field, "0");
            assertCorruption(
                expectCorruption(() -> codec.decode(activeZero)),
                RedisCoordinatorLeaseCorruptionException.Reason.STATE_FIELDS_INVALID
            );
        }

        Map<String, String> reversed = activeFields();
        reversed.put(
            RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS,
            Long.toString(ISSUED_AT)
        );
        assertCorruption(
            expectCorruption(() -> codec.decode(reversed)),
            RedisCoordinatorLeaseCorruptionException.Reason.TIME_RANGE_INVALID
        );
    }

    @Test
    void encodeRejectsNullAndWireDecodeRejectsMalformedUnicode() {
        assertThatThrownBy(() -> codec.encode(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("verified lease must not be null");

        Map<String, String> fields = activeFields();
        fields.put(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID, "tenant-\ud800");
        assertCorruption(
            expectCorruption(() -> codec.decode(fields)),
            RedisCoordinatorLeaseCorruptionException.Reason.FIELD_UTF8_INVALID
        );
    }

    @Test
    void encodeRejectsFutureProtocolWithASanitizedArgumentError() {
        SecurityDatasetIdentity futureIdentity = new SecurityDatasetIdentity("sensitive-dataset", 2);
        RedisCoordinatorLease.Verified futureLease = mock(
            RedisCoordinatorLease.Verified.class
        );
        when(futureLease.identity()).thenReturn(futureIdentity);

        assertThatThrownBy(() -> codec.encode(futureLease))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
    }

    private RedisCoordinatorLease.Verified verifyActive(
        RedisCoordinatorLease.Unverified candidate,
        long observedRedisTime
    ) {
        return RedisCoordinatorLease.verifyActiveForContext(
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
            observedRedisTime
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
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RedisCoordinatorLeaseCodec.FIELD_SCHEMA_VERSION, "1");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_DATASET_ID, "tenant-a");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_DATASET_HASH, DATASET_HASH);
        fields.put(RedisCoordinatorLeaseCodec.FIELD_PROTOCOL_VERSION, "1");
        fields.put(RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID, PRIMARY_RUN_ID);
        fields.put(RedisCoordinatorLeaseCodec.FIELD_STORAGE_UUID, STORAGE_UUID.toString());
        fields.put(RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER, "7");
        return fields;
    }

    private void assertFieldCorrupt(
        String field,
        String value,
        RedisCoordinatorLeaseCorruptionException.Reason reason
    ) {
        Map<String, String> fields = new HashMap<>(activeFields());
        fields.put(field, value);
        assertCorruption(expectCorruption(() -> codec.decode(fields)), reason);
    }

    private static SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity("tenant-a", 1);
    }

    private static RedisIncarnation incarnation() {
        return new RedisIncarnation(PRIMARY_RUN_ID, STORAGE_UUID);
    }

    private static RedisCoordinatorLeaseCorruptionException expectCorruption(
        Runnable action
    ) {
        try {
            action.run();
        } catch (RedisCoordinatorLeaseCorruptionException exception) {
            return exception;
        }
        throw new AssertionError("expected RedisCoordinatorLeaseCorruptionException");
    }

    private static RedisCoordinatorLeaseEncodingException expectEncoding(Runnable action) {
        try {
            action.run();
        } catch (RedisCoordinatorLeaseEncodingException exception) {
            return exception;
        }
        throw new AssertionError("expected RedisCoordinatorLeaseEncodingException");
    }

    private static void assertCorruption(
        RedisCoordinatorLeaseCorruptionException exception,
        RedisCoordinatorLeaseCorruptionException.Reason reason
    ) {
        assertThat(exception.reason()).isEqualTo(reason);
        assertThat(exception.getMessage()).isEqualTo(
            RedisCoordinatorLeaseCorruptionException.DIAGNOSTIC_CODE + ":" + reason.name()
        );
    }

    private static void assertEncoding(
        RedisCoordinatorLeaseEncodingException exception,
        RedisCoordinatorLeaseEncodingException.Reason reason
    ) {
        assertThat(exception.reason()).isEqualTo(reason);
        assertThat(exception.getMessage()).isEqualTo(
            RedisCoordinatorLeaseEncodingException.DIAGNOSTIC_CODE + ":" + reason.name()
        );
    }
}
