package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import com.nomendi6.orgsec.storage.redis.integration.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictRedisSnapshotReaderIntegrationTest extends AbstractRedisIntegrationTest {

    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private final RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
    private final RedisControlEnvelopeCodec controlCodec = new RedisControlEnvelopeCodec();
    private final RedisSnapshotManifestCodec manifestCodec = new RedisSnapshotManifestCodec();

    @BeforeEach
    void clearData() {
        clearRedis();
    }

    @Test
    void readsAllSixFamiliesInBoundedPagesAndAdoptsOnlyAfterFinalRecheck() {
        PublishedSnapshot published = publishTwoPersons();
        StrictRedisSnapshotReader reader = reader(1);

        RedisSnapshotReadSession session = reader.openActive(
            published.generation,
            published.fence
        );
        readAllFamiliesInArbitraryOrder(session);
        RedisVerifiedSnapshotView view = session.finish();

        assertThat(view.generation()).isEqualTo(published.generation);
        assertThat(view.snapshotId()).isEqualTo(published.manifest.snapshotId());
        assertThat(view.manifest()).isEqualTo(published.manifest);
    }

    @Test
    void pageFailsClosedWhenControlChangesAfterOpen() {
        PublishedSnapshot published = publishTwoPersons();
        StrictRedisSnapshotReader reader = reader(1);
        RedisSnapshotReadSession session = reader.openActive(
            published.generation,
            published.fence
        );
        RedisControlEnvelope changed = new RedisControlEnvelope(
            published.generation.identity(),
            published.generation.incarnation(),
            published.generation.counter() + 1,
            published.generation.activeSnapshotId(),
            RedisControlState.READY
        );
        redisTemplate.opsForHash().putAll(keyspace.controlKey(), controlCodec.encode(changed));

        assertReason(
            () -> session.readNextPage(RedisSnapshotFamily.PERSONS),
            RedisSnapshotReadException.Reason.GENERATION_CHANGED
        );
    }

    @Test
    void pageFailsClosedWhenManifestChangesAfterOpen() {
        PublishedSnapshot published = publishTwoPersons();
        StrictRedisSnapshotReader reader = reader(1);
        RedisSnapshotReadSession session = reader.openActive(
            published.generation,
            published.fence
        );
        redisTemplate.opsForHash().put(
            keyspace.manifestKey(published.manifest.snapshotId()),
            RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST,
            "0".repeat(64)
        );

        assertReason(
            () -> session.readNextPage(RedisSnapshotFamily.PERSONS),
            RedisSnapshotReadException.Reason.MANIFEST_CHANGED
        );
    }

    @Test
    void pageRejectsHashIndexCardinalityDriftBeforeReturningPayloads() {
        PublishedSnapshot published = publishTwoPersons();
        StrictRedisSnapshotReader reader = reader(1);
        RedisSnapshotReadSession session = reader.openActive(
            published.generation,
            published.fence
        );
        redisTemplate.opsForHash().delete(
            keyspace.familyKey(published.manifest.snapshotId(), RedisSnapshotFamily.PERSONS),
            "1"
        );

        assertReason(
            () -> session.readNextPage(RedisSnapshotFamily.PERSONS),
            RedisSnapshotReadException.Reason.FAMILY_CARDINALITY_MISMATCH
        );
    }

    @Test
    void finalRecheckRejectsGenerationSwitchAfterAllPagesWereRead() {
        PublishedSnapshot published = publishTwoPersons();
        StrictRedisSnapshotReader reader = reader(1);
        RedisSnapshotReadSession session = reader.openActive(
            published.generation,
            published.fence
        );
        readAllFamiliesInArbitraryOrder(session);
        RedisControlEnvelope changed = new RedisControlEnvelope(
            published.generation.identity(),
            published.generation.incarnation(),
            published.generation.counter() + 1,
            published.generation.activeSnapshotId(),
            RedisControlState.UPDATING
        );
        redisTemplate.opsForHash().putAll(keyspace.controlKey(), controlCodec.encode(changed));

        assertReason(
            session::finish,
            RedisSnapshotReadException.Reason.GENERATION_CHANGED
        );
    }

    @Test
    void openRejectsManifestLogicalByteLimitBeforeStartingAnyPageLoop() {
        PublishedSnapshot published = publishTwoPersons();
        RedisSnapshotReadLimits limits = new RedisSnapshotReadLimits(
            1,
            6,
            1024 * 1024,
            1,
            64,
            1024,
            2048
        );
        StrictRedisSnapshotReader reader = new StrictRedisSnapshotReader(
            redisConnectionFactory,
            limits
        );

        assertReason(
            () -> reader.openActive(published.generation, published.fence),
            RedisSnapshotReadException.Reason.SNAPSHOT_LIMIT_EXCEEDED
        );
    }

    private PublishedSnapshot publishTwoPersons() {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("tenant-a", 1);
        SecurityDatasetFence fence = new SecurityDatasetFence(identity, 9);
        RedisSnapshotManifest.PendingPublication pending =
            RedisSnapshotManifest.beginPublication(fence);
        UUID snapshotId = pending.snapshotId();
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries = new EnumMap<>(
            RedisSnapshotFamily.class
        );
        RedisCanonicalSnapshotPayloadCodec payloadCodec =
            new RedisCanonicalSnapshotPayloadCodec();
        entries.put(
            RedisSnapshotFamily.PERSONS,
            List.of(
                entry("1", payloadCodec.encode(new PersonDef(1L, "Person 1"))),
                entry("2", payloadCodec.encode(new PersonDef(2L, "Person 2")))
            )
        );
        for (RedisSnapshotFamily family : RedisSnapshotFamily.values()) {
            entries.putIfAbsent(family, List.of());
        }
        RedisSnapshotContentDigest content = content(snapshotId, entries);
        RedisSnapshotManifest.Verified manifest = pending.seal(content);

        for (Map.Entry<RedisSnapshotFamily, List<RedisCanonicalEntry>> family :
            entries.entrySet()) {
            String dataKey = keyspace.familyKey(snapshotId, family.getKey());
            String indexKey = keyspace.familyIndexKey(snapshotId, family.getKey());
            for (RedisCanonicalEntry entry : family.getValue()) {
                redisTemplate.opsForHash().put(
                    dataKey,
                    string(entry.canonicalKey()),
                    string(entry.canonicalPayload())
                );
                redisTemplate.opsForZSet().add(
                    indexKey,
                    string(entry.canonicalKey()),
                    0
                );
            }
        }
        redisTemplate.opsForHash().putAll(
            keyspace.manifestKey(snapshotId),
            manifestCodec.encode(manifest)
        );

        RedisPrimarySnapshot absent = new StrictRedisProtocolClient(redisConnectionFactory)
            .readPrimarySnapshot(keyspace);
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            new RedisIncarnation(absent.observation().runId(), STORAGE_UUID),
            7,
            snapshotId,
            RedisControlState.READY
        );
        redisTemplate.opsForHash().putAll(keyspace.controlKey(), controlCodec.encode(control));
        RedisPrimarySnapshot primary = new StrictRedisProtocolClient(redisConnectionFactory)
            .readPrimarySnapshot(keyspace);
        RedisSnapshotGeneration generation = RedisSnapshotGeneration.from(primary, identity);
        return new PublishedSnapshot(fence, generation, manifest);
    }

    private static void readAllFamiliesInArbitraryOrder(RedisSnapshotReadSession session) {
        List<RedisSnapshotFamily> order = List.of(
            RedisSnapshotFamily.ROLES,
            RedisSnapshotFamily.PERSONS,
            RedisSnapshotFamily.PRIVILEGES,
            RedisSnapshotFamily.ORGANIZATIONS,
            RedisSnapshotFamily.POSITION_ROLES,
            RedisSnapshotFamily.PARTY_ROLES
        );
        for (RedisSnapshotFamily family : order) {
            RedisSnapshotPage page;
            do {
                page = session.readNextPage(family);
            } while (!page.done());
        }
    }

    private RedisSnapshotContentDigest content(
        UUID snapshotId,
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries
    ) {
        Map<RedisSnapshotFamily, RedisSnapshotFamilyDigest> digests = new EnumMap<>(
            RedisSnapshotFamily.class
        );
        for (RedisSnapshotFamily family : RedisSnapshotFamily.values()) {
            RedisSnapshotFamilyAccumulator accumulator = accumulator(snapshotId, family);
            entries.get(family).forEach(accumulator::add);
            digests.put(family, accumulator.finish());
        }
        return new RedisSnapshotContentDigest(
            digests.get(RedisSnapshotFamily.PERSONS),
            digests.get(RedisSnapshotFamily.ORGANIZATIONS),
            digests.get(RedisSnapshotFamily.PARTY_ROLES),
            digests.get(RedisSnapshotFamily.POSITION_ROLES),
            digests.get(RedisSnapshotFamily.ROLES),
            digests.get(RedisSnapshotFamily.PRIVILEGES)
        );
    }

    private RedisSnapshotFamilyAccumulator accumulator(
        UUID snapshotId,
        RedisSnapshotFamily family
    ) {
        return new RedisSnapshotFamilyAccumulator(
            familyCode(family),
            keyspace.familyKey(snapshotId, family),
            keyspace.familyIndexKey(snapshotId, family)
        );
    }

    private StrictRedisSnapshotReader reader(int pageEntries) {
        return new StrictRedisSnapshotReader(
            redisConnectionFactory,
            new RedisSnapshotReadLimits(
                100,
                500,
                1024 * 1024,
                pageEntries,
                64,
                1024,
                2048
            )
        );
    }

    private static RedisSnapshotFamilyCode familyCode(RedisSnapshotFamily family) {
        return switch (family) {
            case PERSONS -> RedisSnapshotFamilyCode.PERSONS;
            case ORGANIZATIONS -> RedisSnapshotFamilyCode.ORGANIZATIONS;
            case PARTY_ROLES -> RedisSnapshotFamilyCode.PARTY_ROLES;
            case POSITION_ROLES -> RedisSnapshotFamilyCode.POSITION_ROLES;
            case ROLES -> RedisSnapshotFamilyCode.ROLES;
            case PRIVILEGES -> RedisSnapshotFamilyCode.PRIVILEGES;
        };
    }

    private static RedisCanonicalEntry entry(String key, byte[] payload) {
        return new RedisCanonicalEntry(bytes(key), payload);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String string(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void assertReason(
        org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
        RedisSnapshotReadException.Reason reason
    ) {
        assertThatThrownBy(operation)
            .isInstanceOf(RedisSnapshotReadException.class)
            .satisfies(failure -> assertThat(
                ((RedisSnapshotReadException) failure).reason()
            ).isEqualTo(reason));
    }

    private record PublishedSnapshot(
        SecurityDatasetFence fence,
        RedisSnapshotGeneration generation,
        RedisSnapshotManifest.Verified manifest
    ) {
    }
}
