package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisControlEnvelopeCodecTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SNAPSHOT_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private final RedisControlEnvelopeCodec codec = new RedisControlEnvelopeCodec();

    @Test
    void encodesTheExactCanonicalFieldMapAndRoundTripsIt() {
        RedisControlEnvelope envelope = envelope(7, SNAPSHOT_ID, RedisControlState.READY);

        Map<String, String> encoded = codec.encode(envelope);

        assertThat(encoded).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
            Map.entry(RedisControlEnvelopeCodec.FIELD_DATASET_ID, "tenant-a"),
            Map.entry(RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION, "1"),
            Map.entry(RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID, RUN_ID),
            Map.entry(RedisControlEnvelopeCodec.FIELD_STORAGE_UUID, STORAGE_UUID.toString()),
            Map.entry(RedisControlEnvelopeCodec.FIELD_COUNTER, "7"),
            Map.entry(RedisControlEnvelopeCodec.FIELD_ACTIVE_SNAPSHOT_ID, SNAPSHOT_ID.toString()),
            Map.entry(RedisControlEnvelopeCodec.FIELD_STATE, "READY")
        ));
        assertThat(encoded).hasSize(7);
        assertThat(codec.decode(encoded)).isEqualTo(envelope);
        assertThatThrownBy(() -> encoded.put("unknown", "value"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void encodesAbsentActiveSnapshotAsTheRequiredEmptyField() {
        RedisControlEnvelope initializing = envelope(0, null, RedisControlState.INITIALIZING);
        RedisControlEnvelope updating = envelope(0, null, RedisControlState.UPDATING);

        assertThat(codec.encode(initializing))
            .containsEntry(RedisControlEnvelopeCodec.FIELD_ACTIVE_SNAPSHOT_ID, "");
        assertThat(codec.decode(codec.encode(initializing))).isEqualTo(initializing);
        assertThat(codec.decode(codec.encode(updating))).isEqualTo(updating);
    }

    @Test
    void roundTripsTheLuaSafeCounterBoundaryAndRejectsTheNextInteger() {
        RedisControlEnvelope boundary = envelope(
            RedisControlEnvelope.MAX_COUNTER,
            SNAPSHOT_ID,
            RedisControlState.READY
        );

        assertThat(codec.encode(boundary))
            .containsEntry(RedisControlEnvelopeCodec.FIELD_COUNTER, "9007199254740991");
        assertThat(codec.decode(codec.encode(boundary))).isEqualTo(boundary);

        Map<String, String> aboveBoundary = mutableCanonical();
        aboveBoundary.put(RedisControlEnvelopeCodec.FIELD_COUNTER, "9007199254740992");
        assertCorrupt(
            () -> codec.decode(aboveBoundary),
            "counter",
            "Lua-safe integer maximum"
        );
    }

    @Test
    void rejectsNullMissingAndUnknownFields() {
        assertCorrupt(() -> codec.decode(null), "map is null");

        Map<String, String> missing = mutableCanonical();
        missing.remove(RedisControlEnvelopeCodec.FIELD_COUNTER);
        assertCorrupt(() -> codec.decode(missing), "missing fields", "counter");

        Map<String, String> unknown = mutableCanonical();
        unknown.put("futureField", "futureValue");
        assertCorrupt(() -> codec.decode(unknown), "unknown fields", "futureField");

        Map<String, String> nullValue = mutableCanonical();
        nullValue.put(RedisControlEnvelopeCodec.FIELD_DATASET_ID, null);
        assertCorrupt(() -> codec.decode(nullValue), "datasetId", "empty");

        Map<String, String> nullSnapshot = mutableCanonical();
        nullSnapshot.put(RedisControlEnvelopeCodec.FIELD_ACTIVE_SNAPSHOT_ID, null);
        assertCorrupt(() -> codec.decode(nullSnapshot), "activeSnapshotId", "null");
    }

    @Test
    void rejectsBlankDatasetIdentity() {
        assertFieldCorrupt(RedisControlEnvelopeCodec.FIELD_DATASET_ID, " ", "blank");
    }

    @Test
    void enforcesDatasetIdAsA256ByteCanonicalUtf8FieldOnEncodeAndDecode() {
        String exactlyAtLimit = "ž".repeat(128);
        String aboveLimit = "ž".repeat(129);

        RedisControlEnvelope atLimit = envelope(
            exactlyAtLimit,
            7,
            SNAPSHOT_ID,
            RedisControlState.READY
        );
        assertThat(codec.encode(atLimit))
            .containsEntry(RedisControlEnvelopeCodec.FIELD_DATASET_ID, exactlyAtLimit);
        assertThat(codec.decode(codec.encode(atLimit))).isEqualTo(atLimit);

        assertThatThrownBy(() -> codec.encode(envelope(
            aboveLimit,
            7,
            SNAPSHOT_ID,
            RedisControlState.READY
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("datasetId", "256", "UTF-8 bytes");

        Map<String, String> oversizedWire = mutableCanonical();
        oversizedWire.put(RedisControlEnvelopeCodec.FIELD_DATASET_ID, aboveLimit);
        assertCorrupt(() -> codec.decode(oversizedWire), "datasetId", "256", "UTF-8 bytes");

        String malformedUtf16 = "tenant-\ud800";
        assertThatThrownBy(() -> codec.encode(envelope(
            malformedUtf16,
            7,
            SNAPSHOT_ID,
            RedisControlState.READY
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("datasetId", "canonical UTF-8");
        Map<String, String> malformedWire = mutableCanonical();
        malformedWire.put(RedisControlEnvelopeCodec.FIELD_DATASET_ID, malformedUtf16);
        assertCorrupt(() -> codec.decode(malformedWire), "datasetId", "canonical UTF-8");
    }

    @Test
    void enforcesTheDeclaredUtf8ByteLimitForEveryControlField() {
        for (String field : RedisControlEnvelopeCodec.orderedFields()) {
            assertThat(RedisControlEnvelopeCodec.maxUtf8Bytes(field)).isPositive();
        }

    }

    @Test
    void rejectsMalformedAndNonCanonicalNumericFields() {
        assertFieldCorrupt(RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION, "0", "positive integer");
        assertFieldCorrupt(RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION, "01", "positive integer");
        assertFieldCorrupt(RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION, "+1", "positive integer");
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION,
            "9999999999",
            "integer range"
        );
        assertFieldCorrupt(RedisControlEnvelopeCodec.FIELD_COUNTER, "1.0", "non-negative integer");
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_COUNTER,
            "9999999999999999999",
            "maximum of 16 UTF-8 bytes"
        );
    }

    @Test
    void rejectsFutureProtocolOnEncodeAndDecodeWithoutEchoingIt() {
        SecurityDatasetIdentity futureIdentity = new SecurityDatasetIdentity("sensitive-dataset", 2);
        RedisControlEnvelope futureEnvelope = mock(RedisControlEnvelope.class);
        when(futureEnvelope.getIdentity()).thenReturn(futureIdentity);

        assertThatThrownBy(() -> codec.encode(futureEnvelope))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");

        Map<String, String> futureWire = mutableCanonical();
        futureWire.put(RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION, "2");
        assertThatThrownBy(() -> codec.decode(futureWire))
            .isInstanceOf(RedisControlCorruptionException.class)
            .hasMessage(
                RedisControlCorruptionException.DIAGNOSTIC_CODE
                    + ": PROTOCOL_VERSION_INVALID"
            )
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
    }

    @Test
    void rejectsMalformedAndNonCanonicalRunIdsAndUuids() {
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID,
            RUN_ID.toUpperCase(),
            "lower-case Redis run ID"
        );
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID,
            RUN_ID.substring(1),
            "lower-case Redis run ID"
        );
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_STORAGE_UUID,
            STORAGE_UUID.toString().toUpperCase(),
            "lower-case UUID"
        );
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_STORAGE_UUID,
            "not-a-uuid",
            "lower-case UUID"
        );
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_STORAGE_UUID,
            "aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa",
            "invariants"
        );
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_ACTIVE_SNAPSHOT_ID,
            "1-1-1-1-1",
            "lower-case UUID"
        );
        assertFieldCorrupt(
            RedisControlEnvelopeCodec.FIELD_ACTIVE_SNAPSHOT_ID,
            SNAPSHOT_ID.toString().toUpperCase(),
            "lower-case UUID"
        );
    }

    @Test
    void rejectsUnknownOrNonCanonicalStatesAndInvalidSnapshotStateShapes() {
        assertFieldCorrupt(RedisControlEnvelopeCodec.FIELD_STATE, "ready", "canonical state");
        assertFieldCorrupt(RedisControlEnvelopeCodec.FIELD_STATE, "BROKEN", "canonical state");

        Map<String, String> readyWithoutSnapshot = mutableCanonical();
        readyWithoutSnapshot.put(RedisControlEnvelopeCodec.FIELD_ACTIVE_SNAPSHOT_ID, "");
        assertCorrupt(() -> codec.decode(readyWithoutSnapshot), "invariants");

        Map<String, String> initializingWithSnapshot = mutableCanonical();
        initializingWithSnapshot.put(
            RedisControlEnvelopeCodec.FIELD_STATE,
            RedisControlState.INITIALIZING.name()
        );
        assertCorrupt(() -> codec.decode(initializingWithSnapshot), "invariants");
    }

    @Test
    void rejectsNullEncodeInputWithAnArgumentError() {
        assertThatThrownBy(() -> codec.encode(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("envelope");
    }

    @Test
    void corruptionExceptionHasAStableDiagnosticCodeAndPreservesCause() {
        IllegalArgumentException cause = new IllegalArgumentException("bad wire value");
        RedisControlCorruptionException exception =
            new RedisControlCorruptionException("invalid envelope", cause);

        assertThat(exception.getMessage())
            .startsWith(RedisControlCorruptionException.DIAGNOSTIC_CODE)
            .contains("invalid envelope");
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(new RedisControlCorruptionException("missing envelope").getMessage())
            .contains("missing envelope");
    }

    private void assertFieldCorrupt(String field, String value, String messagePart) {
        Map<String, String> fields = mutableCanonical();
        fields.put(field, value);
        assertCorrupt(() -> codec.decode(fields), field, messagePart);
    }

    private void assertCorrupt(Runnable action, String... messageParts) {
        assertThatThrownBy(action::run)
            .isInstanceOf(RedisControlCorruptionException.class)
            .hasMessageStartingWith(RedisControlCorruptionException.DIAGNOSTIC_CODE)
            .hasMessageContainingAll(messageParts);
    }

    private Map<String, String> mutableCanonical() {
        return new HashMap<>(codec.encode(envelope(7, SNAPSHOT_ID, RedisControlState.READY)));
    }

    private static RedisControlEnvelope envelope(
        long counter,
        UUID snapshotId,
        RedisControlState state
    ) {
        return envelope("tenant-a", counter, snapshotId, state);
    }

    private static RedisControlEnvelope envelope(
        String datasetId,
        long counter,
        UUID snapshotId,
        RedisControlState state
    ) {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity(datasetId, 1);
        RedisIncarnation incarnation = new RedisIncarnation(RUN_ID, STORAGE_UUID);
        return new RedisControlEnvelope(identity, incarnation, counter, snapshotId, state);
    }
}
