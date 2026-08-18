package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrictRedisSnapshotReaderTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void openAndFinishUseByteIdenticalManifestScriptOnSeparateConnections() {
        Fixture data = fixture();
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisSnapshotReadResponseCodec responseCodec = mock(
            RedisSnapshotReadResponseCodec.class
        );
        List<byte[]> scripts = new ArrayList<>();
        List<Integer> keyCounts = new ArrayList<>();
        List<Integer> commandElementCounts = new ArrayList<>();
        Object rawResult = List.of(bytes("raw"));
        RedisScriptingCommands commands = (RedisScriptingCommands) Proxy.newProxyInstance(
            RedisScriptingCommands.class.getClassLoader(),
            new Class<?>[]{RedisScriptingCommands.class},
            (proxy, method, arguments) -> {
                if ("eval".equals(method.getName())) {
                    scripts.add(((byte[]) arguments[0]).clone());
                    keyCounts.add((Integer) arguments[2]);
                    commandElementCounts.add(((byte[][]) arguments[3]).length);
                    return rawResult;
                }
                return null;
            }
        );
        when(factory.getConnection()).thenReturn(connection);
        when(connection.scriptingCommands()).thenReturn(commands);
        RedisSnapshotReadResponseCodec.ManifestRead manifestRead =
            new RedisSnapshotReadResponseCodec.ManifestRead(
                data.generation,
                data.manifestFields,
                data.candidate
            );
        when(responseCodec.decodeManifest(any(), eq(data.generation)))
            .thenReturn(manifestRead);
        List<RedisSnapshotFamily> families = List.of(
            RedisSnapshotFamily.ROLES,
            RedisSnapshotFamily.PERSONS,
            RedisSnapshotFamily.PRIVILEGES,
            RedisSnapshotFamily.ORGANIZATIONS,
            RedisSnapshotFamily.POSITION_ROLES,
            RedisSnapshotFamily.PARTY_ROLES
        );
        for (RedisSnapshotFamily family : families) {
            when(responseCodec.decodePage(
                any(),
                eq(data.generation),
                eq(family),
                eq(0L),
                eq(0L),
                any(RedisSnapshotReadLimits.class)
            )).thenReturn(new RedisSnapshotPage(family, 0, 0, List.of(), true));
        }
        StrictRedisSnapshotReader reader = new StrictRedisSnapshotReader(
            factory,
            new RedisSnapshotReadLimits(10, 20, 1024, 1, 64, 128, 256),
            new RedisControlEnvelopeCodec(),
            new RedisSnapshotManifestCodec(),
            responseCodec
        );

        RedisSnapshotReadSession session = reader.openActive(data.generation, data.fence);
        families.forEach(session::readNextPage);
        RedisVerifiedSnapshotView view = session.finish();

        assertThat(view.snapshotId()).isEqualTo(data.manifest.snapshotId());
        assertThat(scripts).hasSize(8);
        assertThat(scripts.get(0)).containsExactly(scripts.get(7));
        assertThat(new String(scripts.get(0), StandardCharsets.UTF_8))
            .startsWith("-- READ_MANIFEST_IF_CURRENT");
        assertThat(keyCounts).containsExactly(2, 4, 4, 4, 4, 4, 4, 2);
        int manifestCommandElementCount = 2 + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;
        int pageCommandElementCount = 4
            + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
            + RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT
            + 6;
        assertThat(commandElementCounts).containsExactly(
            manifestCommandElementCount,
            pageCommandElementCount,
            pageCommandElementCount,
            pageCommandElementCount,
            pageCommandElementCount,
            pageCommandElementCount,
            pageCommandElementCount,
            manifestCommandElementCount
        );
        verify(factory, times(8)).getConnection();
        verify(connection, times(8)).close();
        verify(responseCodec, times(2)).decodeManifest(any(), eq(data.generation));
    }

    @Test
    void rejectsFutureExpectedFenceBeforeAcquiringAConnection() {
        Fixture data = fixture();
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        SecurityDatasetFence futureFence = new SecurityDatasetFence(
            new SecurityDatasetIdentity("sensitive-dataset", 2),
            9
        );
        StrictRedisSnapshotReader reader = new StrictRedisSnapshotReader(
            factory,
            new RedisSnapshotReadLimits(10, 20, 1024, 1, 64, 128, 256)
        );

        assertThatThrownBy(() -> reader.openActive(data.generation, futureFence))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
        verifyNoInteractions(factory);
    }

    private static Fixture fixture() {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("tenant-a", 1);
        SecurityDatasetFence fence = new SecurityDatasetFence(identity, 9);
        RedisSnapshotManifest.PendingPublication pending =
            RedisSnapshotManifest.beginPublication(fence);
        UUID snapshotId = pending.snapshotId();
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        RedisSnapshotContentDigest content = new RedisSnapshotContentDigest(
            empty(keyspace, snapshotId, RedisSnapshotFamily.PERSONS,
                RedisSnapshotFamilyCode.PERSONS),
            empty(keyspace, snapshotId, RedisSnapshotFamily.ORGANIZATIONS,
                RedisSnapshotFamilyCode.ORGANIZATIONS),
            empty(keyspace, snapshotId, RedisSnapshotFamily.PARTY_ROLES,
                RedisSnapshotFamilyCode.PARTY_ROLES),
            empty(keyspace, snapshotId, RedisSnapshotFamily.POSITION_ROLES,
                RedisSnapshotFamilyCode.POSITION_ROLES),
            empty(keyspace, snapshotId, RedisSnapshotFamily.ROLES,
                RedisSnapshotFamilyCode.ROLES),
            empty(keyspace, snapshotId, RedisSnapshotFamily.PRIVILEGES,
                RedisSnapshotFamilyCode.PRIVILEGES)
        );
        RedisSnapshotManifest.Verified manifest = pending.seal(content);
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            new RedisIncarnation(RUN_ID, STORAGE_UUID),
            7,
            snapshotId,
            RedisControlState.READY
        );
        RedisSnapshotGeneration generation = RedisSnapshotGeneration.from(
            new RedisPrimarySnapshot(
                new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
                control
            ),
            identity
        );
        Map<String, String> fields = new RedisSnapshotManifestCodec().encode(manifest);
        RedisSnapshotManifest.Unverified candidate = new RedisSnapshotManifestCodec()
            .decode(fields);
        return new Fixture(fence, generation, manifest, content, fields, candidate);
    }

    private static RedisSnapshotFamilyDigest empty(
        RedisDatasetKeyspace keyspace,
        UUID snapshotId,
        RedisSnapshotFamily family,
        RedisSnapshotFamilyCode code
    ) {
        return new RedisSnapshotFamilyAccumulator(
            code,
            keyspace.familyKey(snapshotId, family),
            keyspace.familyIndexKey(snapshotId, family)
        ).finish();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
        SecurityDatasetFence fence,
        RedisSnapshotGeneration generation,
        RedisSnapshotManifest.Verified manifest,
        RedisSnapshotContentDigest content,
        Map<String, String> manifestFields,
        RedisSnapshotManifest.Unverified candidate
    ) {
    }
}
