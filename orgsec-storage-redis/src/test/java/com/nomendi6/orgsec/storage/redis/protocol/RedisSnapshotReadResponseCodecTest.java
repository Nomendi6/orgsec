package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSnapshotReadResponseCodecTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_RUN_ID = "89abcdef0123456789abcdef0123456789abcdef";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private final RedisSnapshotReadResponseCodec codec =
        new RedisSnapshotReadResponseCodec();

    @Test
    void decodesBoundedManifestAndRetainsTheExactlyObservedGeneration() {
        Fixture fixture = fixture();

        RedisSnapshotReadResponseCodec.ManifestRead decoded = codec.decodeManifest(
            fixture.manifestResponse(),
            fixture.generation
        );

        assertThat(decoded.generation()).isEqualTo(fixture.generation);
        assertThat(decoded.wireFields()).isEqualTo(fixture.manifestFields);
        assertThat(decoded.candidate().snapshotId()).isEqualTo(fixture.manifest.snapshotId());
        assertThat(decoded.candidate().personsCount()).isEqualTo(2);
    }

    @Test
    void rejectsAReplayedControlAfterPhysicalPrimaryRunIdChanges() {
        Fixture fixture = fixture();
        List<Object> promotedPrimaryResponse = fixture.manifestResponse();
        promotedPrimaryResponse.set(1, info("Server", "run_id", OTHER_RUN_ID));

        assertThatThrownBy(() -> codec.decodeManifest(
            promotedPrimaryResponse,
            fixture.generation
        )).isInstanceOf(RedisSnapshotReadException.class)
            .satisfies(failure -> assertThat(
                ((RedisSnapshotReadException) failure).reason()
            ).isEqualTo(RedisSnapshotReadException.Reason.GENERATION_CHANGED));
    }

    @Test
    void rejectsInvalidManifestUtf8WithoutEchoingWireBytes() {
        Fixture fixture = fixture();
        List<Object> response = fixture.manifestResponse();
        @SuppressWarnings("unchecked")
        List<Long> lengths = (List<Long>) response.get(9);
        @SuppressWarnings("unchecked")
        List<byte[]> values = (List<byte[]>) response.get(10);
        List<Long> corruptLengths = new ArrayList<>(lengths);
        List<byte[]> corruptValues = new ArrayList<>(values);
        corruptLengths.set(0, 1L);
        corruptValues.set(0, new byte[]{(byte) 0xc3});
        response.set(9, corruptLengths);
        response.set(10, corruptValues);

        assertThatThrownBy(() -> codec.decodeManifest(response, fixture.generation))
            .isInstanceOf(RedisSnapshotReadException.class)
            .hasMessage(
                RedisSnapshotReadException.DIAGNOSTIC_CODE + ":MANIFEST_CORRUPT"
            );
    }

    @Test
    void decodesOnlyTheExactExpectedPageRange() {
        Fixture fixture = fixture();
        RedisSnapshotReadLimits limits = new RedisSnapshotReadLimits(
            10,
            20,
            1024 * 1024,
            1,
            32,
            32,
            64
        );

        RedisSnapshotPage first = codec.decodePage(
            fixture.pageResponse(0, List.of(entry("a", "payload-a"))),
            fixture.generation,
            RedisSnapshotFamily.PERSONS,
            0,
            2,
            limits
        );
        RedisSnapshotPage second = codec.decodePage(
            fixture.pageResponse(1, List.of(entry("b", "payload-b"))),
            fixture.generation,
            RedisSnapshotFamily.PERSONS,
            1,
            2,
            limits
        );
        RedisSnapshotPage done = codec.decodePage(
            fixture.pageResponse(2, List.of()),
            fixture.generation,
            RedisSnapshotFamily.PERSONS,
            2,
            2,
            limits
        );

        assertThat(first.offset()).isZero();
        assertThat(first.nextOffset()).isEqualTo(1);
        assertThat(first.done()).isFalse();
        assertThat(first.entries()).containsExactly(entry("a", "payload-a"));
        assertThat(second.nextOffset()).isEqualTo(2);
        assertThat(second.done()).isTrue();
        assertThat(done.entries()).isEmpty();
        assertThat(done.done()).isTrue();
    }

    @Test
    void pageAlsoRejectsPhysicalPrimaryChangeAndNonCanonicalIndexOrder() {
        Fixture fixture = fixture();
        RedisSnapshotReadLimits limits = new RedisSnapshotReadLimits(
            10,
            20,
            1024 * 1024,
            2,
            32,
            32,
            128
        );
        List<Object> promoted = fixture.pageResponse(
            0,
            List.of(entry("a", "a"), entry("b", "b"))
        );
        promoted.set(1, info("Server", "run_id", OTHER_RUN_ID));

        assertThatThrownBy(() -> codec.decodePage(
            promoted,
            fixture.generation,
            RedisSnapshotFamily.PERSONS,
            0,
            2,
            limits
        )).isInstanceOf(RedisSnapshotReadException.class)
            .hasMessageEndingWith("GENERATION_CHANGED");

        assertThatThrownBy(() -> codec.decodePage(
            fixture.pageResponse(0, List.of(entry("b", "b"), entry("a", "a"))),
            fixture.generation,
            RedisSnapshotFamily.PERSONS,
            0,
            2,
            limits
        )).isInstanceOf(RedisSnapshotReadException.class)
            .hasMessageEndingWith("INDEX_ENTRY_INVALID");
    }

    @Test
    void acceptsOnlyAllowListedStatusAndNeverCopiesUnknownStatusText() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> codec.decodeManifest(
            List.of(bytes("GENERATION_CHANGED")),
            fixture.generation
        )).isInstanceOf(RedisSnapshotReadException.class)
            .hasMessageEndingWith("GENERATION_CHANGED");

        assertThatThrownBy(() -> codec.decodeManifest(
            List.of(bytes("SECRET_WIRE_VALUE")),
            fixture.generation
        )).isInstanceOf(RedisSnapshotReadException.class)
            .hasMessage(RedisSnapshotReadException.DIAGNOSTIC_CODE + ":RESPONSE_CORRUPT")
            .hasMessageNotContaining("SECRET");
    }

    private static Fixture fixture() {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("tenant-a", 1);
        SecurityDatasetFence fence = new SecurityDatasetFence(identity, 9);
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        List<RedisCanonicalEntry> persons = List.of(
            entry("a", "payload-a"),
            entry("b", "payload-b")
        );
        RedisSnapshotContentDigest content = content(keyspace, persons);
        RedisSnapshotManifest.Verified manifest = RedisSnapshotManifest.publication(
            fence,
            content
        );
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            new RedisIncarnation(RUN_ID, STORAGE_UUID),
            7,
            manifest.snapshotId(),
            RedisControlState.READY
        );
        RedisPrimarySnapshot primary = new RedisPrimarySnapshot(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            control
        );
        RedisSnapshotGeneration generation = RedisSnapshotGeneration.from(primary, identity);
        Map<String, String> manifestFields = new RedisSnapshotManifestCodec().encode(manifest);
        return new Fixture(generation, control, manifest, manifestFields);
    }

    private static RedisSnapshotContentDigest content(
        RedisDatasetKeyspace keyspace,
        List<RedisCanonicalEntry> persons
    ) {
        return new RedisSnapshotContentDigest(
            familyDigest(
                keyspace,
                RedisSnapshotFamily.PERSONS,
                RedisSnapshotFamilyCode.PERSONS,
                persons
            ),
            familyDigest(
                keyspace,
                RedisSnapshotFamily.ORGANIZATIONS,
                RedisSnapshotFamilyCode.ORGANIZATIONS,
                List.of()
            ),
            familyDigest(
                keyspace,
                RedisSnapshotFamily.PARTY_ROLES,
                RedisSnapshotFamilyCode.PARTY_ROLES,
                List.of()
            ),
            familyDigest(
                keyspace,
                RedisSnapshotFamily.POSITION_ROLES,
                RedisSnapshotFamilyCode.POSITION_ROLES,
                List.of()
            ),
            familyDigest(
                keyspace,
                RedisSnapshotFamily.ROLES,
                RedisSnapshotFamilyCode.ROLES,
                List.of()
            ),
            familyDigest(
                keyspace,
                RedisSnapshotFamily.PRIVILEGES,
                RedisSnapshotFamilyCode.PRIVILEGES,
                List.of()
            )
        );
    }

    private static RedisSnapshotFamilyDigest familyDigest(
        RedisDatasetKeyspace keyspace,
        RedisSnapshotFamily family,
        RedisSnapshotFamilyCode familyCode,
        List<RedisCanonicalEntry> entries
    ) {
        RedisSnapshotFamilyAccumulator accumulator = new RedisSnapshotFamilyAccumulator(
            familyCode,
            keyspace.familyKey(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"), family),
            keyspace.familyIndexKey(
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                family
            )
        );
        entries.forEach(accumulator::add);
        return accumulator.finish();
    }

    private static RedisCanonicalEntry entry(String key, String payload) {
        return new RedisCanonicalEntry(bytes(key), bytes(payload));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] info(String section, String name, String value) {
        return bytes("# " + section + "\r\n" + name + ":" + value + "\r\n");
    }

    private record Fixture(
        RedisSnapshotGeneration generation,
        RedisControlEnvelope control,
        RedisSnapshotManifest.Verified manifest,
        Map<String, String> manifestFields
    ) {

        List<Object> manifestResponse() {
            List<Object> response = primaryPrefix();
            response.add((long) RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT);
            response.add(lengths(manifestFields, RedisSnapshotManifestCodec.orderedFields()));
            response.add(values(manifestFields, RedisSnapshotManifestCodec.orderedFields()));
            return response;
        }

        List<Object> pageResponse(long offset, List<RedisCanonicalEntry> entries) {
            List<Object> response = primaryPrefix();
            response.add(offset);
            response.add(2L);
            List<byte[]> keys = new ArrayList<>();
            List<Long> payloadLengths = new ArrayList<>();
            List<byte[]> payloads = new ArrayList<>();
            for (RedisCanonicalEntry entry : entries) {
                keys.add(entry.canonicalKey());
                payloadLengths.add((long) entry.canonicalPayloadLength());
                payloads.add(entry.canonicalPayload());
            }
            response.add(keys);
            response.add(payloadLengths);
            response.add(payloads);
            return response;
        }

        private List<Object> primaryPrefix() {
            Map<String, String> controlFields = new RedisControlEnvelopeCodec().encode(control);
            List<Object> response = new ArrayList<>();
            response.add(bytes("OK"));
            response.add(info("Server", "run_id", RUN_ID));
            response.add(info("Replication", "role", "master"));
            response.add(info("Cluster", "cluster_enabled", "0"));
            response.add(info("Memory", "maxmemory_policy", "noeviction"));
            response.add((long) RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT);
            response.add(lengths(controlFields, RedisControlEnvelopeCodec.orderedFields()));
            response.add(values(controlFields, RedisControlEnvelopeCodec.orderedFields()));
            return response;
        }

        private static List<Long> lengths(
            Map<String, String> fields,
            List<String> orderedFields
        ) {
            List<Long> lengths = new ArrayList<>();
            for (String field : orderedFields) {
                lengths.add((long) bytes(fields.get(field)).length);
            }
            return lengths;
        }

        private static List<byte[]> values(
            Map<String, String> fields,
            List<String> orderedFields
        ) {
            List<byte[]> values = new ArrayList<>();
            for (String field : orderedFields) {
                values.add(bytes(fields.get(field)));
            }
            return values;
        }
    }
}
