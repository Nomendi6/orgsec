package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RedisBootstrapTripletCodecTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SNAPSHOT_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private final RedisControlEnvelopeCodec controlCodec = new RedisControlEnvelopeCodec();
    private final RedisCoordinatorLeaseCodec leaseCodec = new RedisCoordinatorLeaseCodec();
    private final RedisBootstrapTripletCodec codec = new RedisBootstrapTripletCodec();
    private final RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");

    @Test
    void decodesOneCanonicalBoundedTripletAndKeepsCounterGapsStructural() {
        RedisBootstrapTripletObservation observation = codec.decode(fullResult("5"), keyspace);

        assertThat(observation.requestedKeyspace()).isSameAs(keyspace);
        assertThat(observation.primary().runId()).isEqualTo(RUN_ID);
        assertThat(observation.control()).contains(control());
        assertThat(observation.lease()).isPresent();
        assertThat(observation.lease().orElseThrow().fencingSequence()).isEqualTo(2);
        assertThat(observation.leaseCounter()).hasValue(5);
        assertThat(RedisBootstrapTripletClassifier.assess(observation).kind()).isEqualTo(
            RedisBootstrapTripletAssessment.Kind.COHERENT_SAME_RUN_ID
        );
    }

    @Test
    void decodesOnlyTheCanonicalAbsentRepresentation() {
        RedisBootstrapTripletObservation observation = codec.decode(
            result(absentHash(), absentHash(), absentCounter()),
            keyspace
        );
        assertThat(RedisBootstrapTripletClassifier.assess(observation).kind()).isEqualTo(
            RedisBootstrapTripletAssessment.Kind.TRIPLET_ABSENT
        );

        List<Object> invalidControl = absentHash();
        invalidControl.set(1, -1L);
        assertCorrupt(
            () -> codec.decode(result(invalidControl, absentHash(), absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.CONTROL_TTL_INVALID
        );

        List<Object> invalidLease = absentHash();
        invalidLease.set(2, 1L);
        assertCorrupt(
            () -> codec.decode(result(absentHash(), invalidLease, absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.LEASE_WIRE_INVALID
        );

        List<Object> invalidCounter = absentCounter();
        invalidCounter.set(1, -1L);
        assertCorrupt(
            () -> codec.decode(result(absentHash(), absentHash(), invalidCounter), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.COUNTER_TTL_INVALID
        );
    }

    @Test
    void rejectsWrongTypesAndEveryExpiringPersistentMetadataKey() {
        List<Object> wrongControl = hashComponent(controlFields());
        wrongControl.set(0, ascii("string"));
        assertCorrupt(
            () -> codec.decode(result(wrongControl, absentHash(), absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.CONTROL_TYPE_INVALID
        );

        List<Object> wrongLease = hashComponent(leaseFields());
        wrongLease.set(0, ascii("list"));
        assertCorrupt(
            () -> codec.decode(result(absentHash(), wrongLease, absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.LEASE_TYPE_INVALID
        );

        assertCorrupt(
            () -> codec.decode(
                result(absentHash(), absentHash(), list(ascii("hash"), -1L, -1L, null)),
                keyspace
            ),
            RedisBootstrapTripletCorruptionException.Reason.COUNTER_TYPE_INVALID
        );

        List<Object> expiringControl = hashComponent(controlFields());
        expiringControl.set(1, 60_000L);
        assertCorrupt(
            () -> codec.decode(result(expiringControl, absentHash(), absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.CONTROL_TTL_INVALID
        );

        List<Object> expiringLease = hashComponent(leaseFields());
        expiringLease.set(1, 1L);
        assertCorrupt(
            () -> codec.decode(result(absentHash(), expiringLease, absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.LEASE_TTL_INVALID
        );

        List<Object> expiringCounter = counter("2");
        expiringCounter.set(1, 10L);
        assertCorrupt(
            () -> codec.decode(result(absentHash(), absentHash(), expiringCounter), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.COUNTER_TTL_INVALID
        );
    }

    @Test
    void rejectsHashCountLengthValueAndUtf8ViolationsWithoutReturningRawData() {
        List<Object> badCount = hashComponent(controlFields());
        badCount.set(2, 12L);
        badCount.set(3, List.of());
        badCount.set(4, List.of());
        assertCorrupt(
            () -> codec.decode(result(badCount, absentHash(), absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.CONTROL_WIRE_INVALID
        );

        List<Object> oversized = hashComponent(controlFields());
        List<Object> lengths = mutableArray(oversized.get(3));
        lengths.set(0, (long) RedisControlEnvelopeCodec.MAX_DATASET_ID_UTF8_BYTES + 1);
        oversized.set(3, lengths);
        assertCorrupt(
            () -> codec.decode(result(oversized, absentHash(), absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.CONTROL_WIRE_INVALID
        );

        List<Object> malformedUtf8 = hashComponent(leaseFields());
        List<Object> leaseValues = mutableArray(malformedUtf8.get(4));
        int datasetIndex = RedisCoordinatorLeaseCodec.orderedFields().indexOf(
            RedisCoordinatorLeaseCodec.FIELD_DATASET_ID
        );
        leaseValues.set(datasetIndex, new byte[] {(byte) 0xc3, 0x28});
        List<Object> leaseLengths = mutableArray(malformedUtf8.get(3));
        leaseLengths.set(datasetIndex, 2L);
        malformedUtf8.set(3, leaseLengths);
        malformedUtf8.set(4, leaseValues);
        assertCorrupt(
            () -> codec.decode(result(absentHash(), malformedUtf8, absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.LEASE_WIRE_INVALID
        );
    }

    @Test
    void rejectsUnsupportedV2AtBothDecodedWireBoundariesWithNoCauseChain() {
        Map<String, String> v2Control = new LinkedHashMap<>(controlFields());
        v2Control.put(RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION, "2");
        assertCorrupt(
            () -> codec.decode(
                result(hashComponent(v2Control), absentHash(), absentCounter()),
                keyspace
            ),
            RedisBootstrapTripletCorruptionException.Reason.CONTROL_WIRE_INVALID
        );

        Map<String, String> v2Lease = new LinkedHashMap<>(leaseFields());
        v2Lease.put(RedisCoordinatorLeaseCodec.FIELD_PROTOCOL_VERSION, "2");
        assertCorrupt(
            () -> codec.decode(
                result(absentHash(), hashComponent(v2Lease), absentCounter()),
                keyspace
            ),
            RedisBootstrapTripletCorruptionException.Reason.LEASE_WIRE_INVALID
        );
    }

    @Test
    void acceptsLuaSafeCounterEdgesAndRejectsEveryNonCanonicalOrUnsafeCounter() {
        for (String accepted : List.of("0", "1", "9007199254740991")) {
            RedisBootstrapTripletObservation observation = codec.decode(
                result(absentHash(), absentHash(), counter(accepted)),
                keyspace
            );
            assertThat(observation.leaseCounter()).hasValue(Long.parseLong(accepted));
        }

        for (String rejected : List.of("", "00", "+1", "-1", "9007199254740992")) {
            assertCorrupt(
                () -> codec.decode(
                    result(absentHash(), absentHash(), counter(rejected)),
                    keyspace
                ),
                RedisBootstrapTripletCorruptionException.Reason.COUNTER_WIRE_INVALID
            );
        }
    }

    @Test
    void strictlyBoundsAndSanitizesInfoTopologyAndConfigurationFailures() {
        assertCorrupt(
            () -> codec.decode(List.of(ascii("INFO_BOUNDS_INVALID")), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.INFO_BOUNDS_INVALID
        );

        List<Object> oversizedInfo = result(absentHash(), absentHash(), absentCounter());
        oversizedInfo.set(1, new byte[RedisBootstrapTripletCodec.MAX_INFO_SECTION_BYTES + 1]);
        assertCorrupt(
            () -> codec.decode(oversizedInfo, keyspace),
            RedisBootstrapTripletCorruptionException.Reason.INFO_BOUNDS_INVALID
        );

        List<Object> replica = result(absentHash(), absentHash(), absentCounter());
        replica.set(2, info("Replication", "role:master", "connected_slaves:1"));
        assertCorrupt(
            () -> codec.decode(replica, keyspace),
            RedisBootstrapTripletCorruptionException.Reason.TOPOLOGY_INVALID
        );

        List<Object> unsafePolicy = result(absentHash(), absentHash(), absentCounter());
        unsafePolicy.set(4, info(
            "Memory",
            "maxmemory_policy:sentinel-secret\r\ninjected:value"
        ));
        assertCorrupt(
            () -> codec.decode(unsafePolicy, keyspace),
            RedisBootstrapTripletCorruptionException.Reason.CONFIGURATION_INVALID
        );

        List<Object> malformedInfo = result(absentHash(), absentHash(), absentCounter());
        malformedInfo.set(1, new byte[] {(byte) 0xc3, 0x28});
        assertCorrupt(
            () -> codec.decode(malformedInfo, keyspace),
            RedisBootstrapTripletCorruptionException.Reason.INFO_WIRE_INVALID
        );
    }

    @Test
    void rejectsMalformedTopLevelAndComponentShapesWithStableReasonOnlyErrors() {
        assertCorrupt(
            () -> codec.decode(null, keyspace),
            RedisBootstrapTripletCorruptionException.Reason.RESULT_SHAPE_INVALID
        );
        assertCorrupt(
            () -> codec.decode(List.of(ascii("UNKNOWN")), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.RESULT_SHAPE_INVALID
        );
        assertCorrupt(
            () -> codec.decode(result("not-a-component", absentHash(), absentCounter()), keyspace),
            RedisBootstrapTripletCorruptionException.Reason.CONTROL_WIRE_INVALID
        );
    }

    private List<Object> fullResult(String counter) {
        return result(hashComponent(controlFields()), hashComponent(leaseFields()), counter(counter));
    }

    private Map<String, String> controlFields() {
        return controlCodec.encode(control());
    }

    private Map<String, String> leaseFields() {
        RedisControlEnvelope control = control();
        RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
            control.getIdentity(),
            keyspace.datasetHash(),
            control.getIncarnation(),
            control.getCounter(),
            RedisCoordinatorLease.State.FREE,
            4,
            2,
            null,
            null,
            0,
            0
        );
        RedisCoordinatorLease.Verified verified = RedisCoordinatorLease.verifyFreeForContext(
            candidate,
            control.getIdentity(),
            keyspace.datasetHash(),
            control.getIncarnation(),
            control.getCounter(),
            4,
            2
        );
        return leaseCodec.encode(verified);
    }

    private RedisControlEnvelope control() {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("tenant-a", 1);
        return new RedisControlEnvelope(
            identity,
            new RedisIncarnation(RUN_ID, STORAGE_UUID),
            7,
            SNAPSHOT_ID,
            RedisControlState.READY
        );
    }

    private static List<Object> result(Object control, Object lease, Object counter) {
        return list(
            ascii("OK"),
            info("Server", "redis_version:6.0.20", "run_id:" + RUN_ID),
            info("Replication", "role:master", "connected_slaves:0"),
            info("Cluster", "cluster_enabled:0"),
            info("Memory", "maxmemory:0", "maxmemory_policy:noeviction"),
            control,
            lease,
            counter
        );
    }

    private static List<Object> hashComponent(Map<String, String> fields) {
        List<Object> lengths = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String value : fields.values()) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            lengths.add((long) encoded.length);
            values.add(encoded);
        }
        return list(ascii("hash"), -1L, (long) fields.size(), lengths, values);
    }

    private static List<Object> absentHash() {
        return list(ascii("none"), -2L, 0L, List.of(), List.of());
    }

    private static List<Object> counter(String value) {
        byte[] encoded = ascii(value);
        return list(ascii("string"), -1L, (long) encoded.length, encoded);
    }

    private static List<Object> absentCounter() {
        return list(ascii("none"), -2L, 0L, null);
    }

    private static List<Object> mutableArray(Object value) {
        return new ArrayList<>((List<?>) value);
    }

    private static List<Object> list(Object... values) {
        List<Object> result = new ArrayList<>(values.length);
        for (Object value : values) {
            result.add(value);
        }
        return result;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] info(String section, String... fields) {
        StringBuilder result = new StringBuilder("# ").append(section).append("\r\n");
        for (String field : fields) {
            result.append(field).append("\r\n");
        }
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void assertCorrupt(
        Runnable action,
        RedisBootstrapTripletCorruptionException.Reason reason
    ) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isExactlyInstanceOf(RedisBootstrapTripletCorruptionException.class);
        RedisBootstrapTripletCorruptionException corruption =
            (RedisBootstrapTripletCorruptionException) failure;
        assertThat(corruption.reason()).isEqualTo(reason);
        assertThat(corruption.getMessage()).isEqualTo(
            RedisBootstrapTripletCorruptionException.DIAGNOSTIC_CODE + ":" + reason
        );
        assertThat(corruption).hasNoCause();
        assertThat(corruption.toString())
            .doesNotContain("tenant-a", RUN_ID, "sentinel-secret", "\r", "\n");
    }
}
