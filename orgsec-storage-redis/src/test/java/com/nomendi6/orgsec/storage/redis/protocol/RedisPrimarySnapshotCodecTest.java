package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisPrimarySnapshotCodecTest {

    private static final int CONTROL_FIELD_COUNT =
        RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;
    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SNAPSHOT_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private final RedisControlEnvelopeCodec controlCodec = new RedisControlEnvelopeCodec();
    private final RedisPrimarySnapshotCodec codec = new RedisPrimarySnapshotCodec(controlCodec);

    @Test
    void decodesCanonicalPrimaryObservationAndExactFieldValuesTogether() {
        RedisControlEnvelope envelope = envelope();
        List<Object> values = encodedValues(envelope);

        RedisPrimarySnapshot snapshot = codec.decode(result(
            CONTROL_FIELD_COUNT,
            byteLengths(values),
            values
        ));

        assertThat(snapshot.observation()).isEqualTo(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction")
        );
        assertThat(snapshot.controlEnvelope()).contains(envelope);
    }

    @Test
    void zeroHlenIsTheOnlyAbsentControlRepresentation() {
        RedisPrimarySnapshot snapshot = codec.decode(result(0, List.of(), List.of()));
        assertThat(snapshot.controlEnvelope()).isEmpty();

        assertCorrupt(
            () -> codec.decode(result(0, List.of(0L), List.of())),
            "Absent",
            "metadata"
        );
        assertCorrupt(
            () -> codec.decode(result(0, List.of(), List.of(utf8("unexpected")))),
            "Absent",
            "values"
        );
    }

    @Test
    void rejectsNonzeroFieldCountsOtherThanTheExactProtocolBound() {
        assertCorrupt(
            () -> codec.decode(result(1, List.of(), List.of())),
            "contains 1 fields",
            "either 0 or " + CONTROL_FIELD_COUNT
        );
        assertCorrupt(
            () -> codec.decode(result(CONTROL_FIELD_COUNT + 1L, List.of(), List.of())),
            "contains " + (CONTROL_FIELD_COUNT + 1) + " fields",
            "either 0 or " + CONTROL_FIELD_COUNT
        );
    }

    @Test
    void rejectsMalformedTopLevelAndArrayShapes() {
        assertCorrupt(() -> codec.decode(null), "not an array");
        assertCorrupt(() -> codec.decode("not-an-array"), "not an array");
        assertCorrupt(() -> codec.decode(List.of()), "exactly 7", "contained 0");

        List<Object> invalidCountType = result(0, List.of(), List.of());
        invalidCountType.set(4, Integer.valueOf(0));
        assertCorrupt(() -> codec.decode(invalidCountType), "HLEN", "integer");

        List<Object> negativeCount = result(0, List.of(), List.of());
        negativeCount.set(4, Long.valueOf(-1));
        assertCorrupt(() -> codec.decode(negativeCount), "must not be negative");

        List<Object> invalidLengthsType = result(0, List.of(), List.of());
        invalidLengthsType.set(5, utf8("not-an-array"));
        assertCorrupt(() -> codec.decode(invalidLengthsType), "HSTRLEN", "not an array");

        List<Object> invalidValuesType = result(0, List.of(), List.of());
        invalidValuesType.set(6, utf8("not-an-array"));
        assertCorrupt(() -> codec.decode(invalidValuesType), "HMGET", "not an array");
    }

    @Test
    void rejectsMalformedOrOversizedPerFieldLengthsBeforeInspectingValues() {
        List<Object> values = encodedValues(envelope());
        List<Object> lengths = byteLengths(values);

        assertCorrupt(
            () -> codec.decode(result(
                CONTROL_FIELD_COUNT,
                lengths.subList(0, CONTROL_FIELD_COUNT - 1),
                values
            )),
            "HSTRLEN",
            "contains " + (CONTROL_FIELD_COUNT - 1),
            "expected " + CONTROL_FIELD_COUNT
        );

        List<Object> nonInteger = new ArrayList<>(lengths);
        nonInteger.set(0, Integer.valueOf(8));
        assertCorrupt(
            () -> codec.decode(result(CONTROL_FIELD_COUNT, nonInteger, values)),
            "datasetId",
            "non-negative integer"
        );

        List<Object> negative = new ArrayList<>(lengths);
        negative.set(0, Long.valueOf(-1));
        assertCorrupt(
            () -> codec.decode(result(CONTROL_FIELD_COUNT, negative, values)),
            "datasetId",
            "non-negative integer"
        );

        List<Object> oversized = new ArrayList<>(lengths);
        oversized.set(0, Long.valueOf(RedisControlEnvelopeCodec.MAX_DATASET_ID_UTF8_BYTES + 1L));
        assertCorrupt(
            () -> codec.decode(result(CONTROL_FIELD_COUNT, oversized, List.of())),
            "datasetId",
            "257 UTF-8 bytes",
            "maximum is 256"
        );
    }

    @Test
    void rejectsMissingMalformedOrLengthMismatchedExactFieldValues() {
        List<Object> values = encodedValues(envelope());
        List<Object> lengths = byteLengths(values);

        assertCorrupt(
            () -> codec.decode(result(
                CONTROL_FIELD_COUNT,
                lengths,
                values.subList(0, CONTROL_FIELD_COUNT - 1)
            )),
            "HMGET",
            "contains " + (CONTROL_FIELD_COUNT - 1),
            "expected " + CONTROL_FIELD_COUNT
        );

        List<Object> missing = new ArrayList<>(values);
        missing.set(0, null);
        assertCorrupt(
            () -> codec.decode(result(CONTROL_FIELD_COUNT, lengths, missing)),
            "missing required field datasetId"
        );

        List<Object> invalidType = new ArrayList<>(values);
        invalidType.set(0, "tenant-a");
        assertCorrupt(
            () -> codec.decode(result(CONTROL_FIELD_COUNT, lengths, invalidType)),
            "datasetId",
            "bulk string"
        );

        List<Object> mismatched = new ArrayList<>(lengths);
        mismatched.set(0, Long.valueOf(((Long) mismatched.get(0)) + 1));
        assertCorrupt(
            () -> codec.decode(result(CONTROL_FIELD_COUNT, mismatched, values)),
            "datasetId",
            "does not match HSTRLEN"
        );
    }

    @Test
    void rejectsMalformedUtf8InAnExactFieldValue() {
        List<Object> values = encodedValues(envelope());
        values.set(0, new byte[] {(byte) 0xc3, 0x28});
        List<Object> lengths = byteLengths(values);

        assertCorrupt(
            () -> codec.decode(result(CONTROL_FIELD_COUNT, lengths, values)),
            "control field datasetId",
            "UTF-8"
        );
    }

    @Test
    void strictlyDecodesInfoSectionFramingAndUtf8() {
        List<Object> invalidType = absentResult();
        invalidType.set(0, "not-bytes");
        assertCorrupt(() -> codec.decode(invalidType), "INFO Server", "bulk string");

        List<Object> malformedUtf8 = absentResult();
        malformedUtf8.set(0, new byte[] {(byte) 0xc3, 0x28});
        assertCorrupt(() -> codec.decode(malformedUtf8), "INFO Server", "UTF-8");

        List<Object> badHeader = absentResult();
        badHeader.set(0, info("server", "run_id:" + RUN_ID));
        assertCorrupt(() -> codec.decode(badHeader), "invalid section header");

        List<Object> noTerminator = absentResult();
        noTerminator.set(0, utf8("# Server\r\nrun_id:" + RUN_ID));
        assertCorrupt(() -> codec.decode(noTerminator), "CRLF terminated");

        List<Object> malformedLine = absentResult();
        malformedLine.set(0, info("Server", "run_id:" + RUN_ID, "malformed"));
        assertCorrupt(() -> codec.decode(malformedLine), "malformed field line");

        List<Object> duplicate = absentResult();
        duplicate.set(0, info("Server", "run_id:" + RUN_ID, "run_id:" + RUN_ID));
        assertCorrupt(() -> codec.decode(duplicate), "duplicate field run_id");
    }

    @Test
    void rejectsMissingOrNonCanonicalPrimaryIdentity() {
        List<Object> missingRunId = absentResult();
        missingRunId.set(0, info("Server", "redis_version:6.0.20"));
        assertTopology(missingRunId, "run_id");

        List<Object> uppercaseRunId = absentResult();
        uppercaseRunId.set(0, info("Server", "run_id:" + RUN_ID.toUpperCase()));
        assertTopology(uppercaseRunId, "run_id");

        List<Object> replica = absentResult();
        replica.set(1, info("Replication", "role:slave"));
        assertTopology(replica, "role=slave");

        List<Object> paddedRole = absentResult();
        paddedRole.set(1, info("Replication", "role: master"));
        assertTopology(paddedRole, "role= master");
    }

    @Test
    void rejectsEnabledMissingOrMalformedClusterMode() {
        List<Object> enabled = absentResult();
        enabled.set(2, info("Cluster", "cluster_enabled:1"));
        assertTopology(enabled, "Cluster mode");

        List<Object> malformed = absentResult();
        malformed.set(2, info("Cluster", "cluster_enabled:false"));
        assertTopology(malformed, "invalid cluster_enabled");

        List<Object> missing = absentResult();
        missing.set(2, info("Cluster", "cluster_state:ok"));
        assertTopology(missing, "cluster_enabled");
    }

    @Test
    void rejectsUnsafeMissingOrNonCanonicalMaxmemoryPolicy() {
        List<Object> unsafe = absentResult();
        unsafe.set(3, info("Memory", "maxmemory_policy:allkeys-lru"));
        assertConfiguration(unsafe, "allkeys-lru");

        List<Object> missing = absentResult();
        missing.set(3, info("Memory", "maxmemory:0"));
        assertConfiguration(missing, "maxmemory_policy");

        List<Object> padded = absentResult();
        padded.set(3, info("Memory", "maxmemory_policy: noeviction"));
        assertConfiguration(padded, " noeviction");
    }

    @Test
    void propagatesStrictEnvelopeCorruptionWithoutFallback() {
        List<Object> values = encodedValues(envelope());
        int stateIndex = RedisControlEnvelopeCodec.orderedFields().indexOf(
            RedisControlEnvelopeCodec.FIELD_STATE
        );
        values.set(stateIndex, utf8("BROKEN"));

        assertThatThrownBy(() -> codec.decode(result(
            CONTROL_FIELD_COUNT,
            byteLengths(values),
            values
        )))
            .isInstanceOf(RedisControlCorruptionException.class)
            .hasMessageContaining("state", "canonical");
    }

    @Test
    void observationRejectsNonCanonicalValuesEvenWhenConstructedDirectly() {
        assertThatThrownBy(() -> new RedisPrimaryObservation("short", "master", false, "noeviction"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("runId");
        assertThatThrownBy(() -> new RedisPrimaryObservation(RUN_ID, "primary", false, "noeviction"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("role");
        assertThatThrownBy(() -> new RedisPrimaryObservation(RUN_ID, "master", true, "noeviction"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("clusterEnabled");
        assertThatThrownBy(() -> new RedisPrimaryObservation(RUN_ID, "master", false, "allkeys-lru"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxmemoryPolicy");
    }

    private static List<Object> absentResult() {
        return result(0, List.of(), List.of());
    }

    private static List<Object> result(long fieldCount, List<?> lengths, List<?> values) {
        return new ArrayList<>(List.of(
            info("Server", "redis_version:6.0.20", "run_id:" + RUN_ID),
            info("Replication", "role:master", "connected_slaves:0"),
            info("Cluster", "cluster_enabled:0"),
            info("Memory", "maxmemory:0", "maxmemory_policy:noeviction"),
            Long.valueOf(fieldCount),
            lengths,
            values
        ));
    }

    private List<Object> encodedValues(RedisControlEnvelope envelope) {
        Map<String, String> encoded = controlCodec.encode(envelope);
        List<Object> result = new ArrayList<>();
        for (String field : RedisControlEnvelopeCodec.orderedFields()) {
            result.add(utf8(encoded.get(field)));
        }
        return result;
    }

    private static List<Object> byteLengths(List<?> values) {
        List<Object> lengths = new ArrayList<>(values.size());
        for (Object value : values) {
            lengths.add(value instanceof byte[] bytes ? Long.valueOf(bytes.length) : Long.valueOf(0));
        }
        return lengths;
    }

    private static RedisControlEnvelope envelope() {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("tenant-a", 1);
        RedisIncarnation incarnation = new RedisIncarnation(RUN_ID, STORAGE_UUID);
        return new RedisControlEnvelope(
            identity,
            incarnation,
            7,
            SNAPSHOT_ID,
            RedisControlState.READY
        );
    }

    private void assertTopology(List<Object> result, String message) {
        assertThatThrownBy(() -> codec.decode(result))
            .isInstanceOf(RedisProtocolTopologyException.class)
            .hasMessageContaining(message);
    }

    private void assertConfiguration(List<Object> result, String message) {
        assertThatThrownBy(() -> codec.decode(result))
            .isInstanceOf(RedisProtocolConfigurationException.class)
            .hasMessageContaining(message);
    }

    private void assertCorrupt(Runnable action, String... messages) {
        assertThatThrownBy(action::run)
            .isInstanceOf(RedisControlCorruptionException.class)
            .hasMessageContainingAll(messages);
    }

    private static byte[] info(String section, String... fields) {
        StringBuilder result = new StringBuilder("# ").append(section).append("\r\n");
        for (String field : fields) {
            result.append(field).append("\r\n");
        }
        return utf8(result.toString());
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
