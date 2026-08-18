package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import com.nomendi6.orgsec.storage.redis.integration.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictRedisSnapshotWriterIntegrationTest extends AbstractRedisIntegrationTest {

    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    private final RedisControlEnvelopeCodec controlCodec = new RedisControlEnvelopeCodec();
    private final RedisSnapshotManifestCodec manifestCodec = new RedisSnapshotManifestCodec();

    @BeforeEach
    void clearData() {
        clearRedis();
    }

    @Test
    void realRedisStagesRetriesAndSealsWithoutChangingControl() {
        Prepared prepared = prepare("writer-happy");
        StrictRedisSnapshotWriter writer = new StrictRedisSnapshotWriter(
            redisConnectionFactory,
            new RedisSnapshotWriteLimits(16, 64, 1024, 8192)
        );

        try (RedisStagingSession session = writer.begin(
            prepared.primary(),
            prepared.fence(),
            prepared.lease()
        )) {
            UUID snapshotId = session.snapshotId();
            RedisDatasetKeyspace keyspace = prepared.keyspace();
            Map<Object, Object> controlBefore = controlFields(keyspace);
            RedisCanonicalEntry a = personEntry(1);
            RedisCanonicalEntry b = personEntry(2);
            RedisCanonicalEntry c = personEntry(3);
            RedisCanonicalEntry d = personEntry(4);

            session.append(RedisSnapshotFamily.PERSONS, List.of(c, d));
            session.append(RedisSnapshotFamily.PERSONS, List.of(a, b));
            session.append(RedisSnapshotFamily.PERSONS, List.of(a, b));
            completeAll(session);

            Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> allEntries =
                new EnumMap<>(RedisSnapshotFamily.class);
            allEntries.put(RedisSnapshotFamily.PERSONS, List.of(a, b, c, d));
            RedisSnapshotContentDigest content = content(keyspace, snapshotId, allEntries);
            RedisSnapshotManifest.Verified manifest =
                session.sealAndWriteManifest(content);
            assertThat(session.sealAndWriteManifest(content)).isSameAs(manifest);

            assertThat(redisTemplate.opsForHash().size(keyspace.familyKey(
                snapshotId,
                RedisSnapshotFamily.PERSONS
            ))).isEqualTo(4);
            assertThat(redisTemplate.opsForZSet().size(keyspace.familyIndexKey(
                snapshotId,
                RedisSnapshotFamily.PERSONS
            ))).isEqualTo(4);
            Set<String> orderedKeys = redisTemplate.opsForZSet().range(
                keyspace.familyIndexKey(snapshotId, RedisSnapshotFamily.PERSONS),
                0,
                -1
            );
            assertThat(orderedKeys).containsExactly("1", "2", "3", "4");
            assertThat(redisTemplate.opsForHash().get(
                keyspace.familyKey(snapshotId, RedisSnapshotFamily.PERSONS),
                "1"
            )).isEqualTo(new String(a.canonicalPayload(), StandardCharsets.UTF_8));
            assertThat(redisTemplate.opsForZSet().score(
                keyspace.familyIndexKey(snapshotId, RedisSnapshotFamily.PERSONS),
                "1"
            )).isZero();

            Map<Object, Object> actualManifest = redisTemplate.opsForHash().entries(
                keyspace.manifestKey(snapshotId)
            );
            assertThat(actualManifest).containsExactlyEntriesOf(
                new LinkedHashMap<>(manifestCodec.encode(manifest))
            );
            assertThat(controlFields(keyspace)).isEqualTo(controlBefore);

            assertThatThrownBy(() -> session.append(
                RedisSnapshotFamily.PERSONS,
                List.of(personEntry(5))
            )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SEALED");
            assertThat(controlFields(keyspace)).isEqualTo(controlBefore);

            redisTemplate.opsForHash().put(
                keyspace.manifestKey(snapshotId),
                RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST,
                "x".repeat(1024 * 1024)
            );
            assertThatThrownBy(() -> session.sealAndWriteManifest(content))
                .isInstanceOfSatisfying(
                    RedisStagingCorruptionException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(
                        RedisStagingCorruptionException.Reason.MANIFEST_MISMATCH
                    )
                );
            assertThat(controlFields(keyspace)).isEqualTo(controlBefore);
        }
    }

    @Test
    void realRedisCorruptionPreflightDoesNotApplyEarlierBatchEntries() {
        assertRealCorruptionDoesNotPartiallyMutate("writer-half", true, false);
        assertRealCorruptionDoesNotPartiallyMutate("writer-payload", false, false);
        assertRealCorruptionDoesNotPartiallyMutate("writer-huge-payload", false, true);
    }

    @Test
    void realRedisRejectsOversizedChangedControlBeforeAnyStagingWrite() {
        Prepared prepared = prepare("writer-huge-control");
        StrictRedisSnapshotWriter writer = new StrictRedisSnapshotWriter(
            redisConnectionFactory,
            new RedisSnapshotWriteLimits(16, 64, 1024, 8192)
        );
        RedisStagingSession session = writer.begin(
            prepared.primary(),
            prepared.fence(),
            prepared.lease()
        );
        UUID snapshotId = session.snapshotId();
        redisTemplate.opsForHash().put(
            prepared.keyspace().controlKey(),
            RedisControlEnvelopeCodec.FIELD_DATASET_ID,
            "x".repeat(1024 * 1024)
        );

        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(personEntry(1))
        )).isInstanceOfSatisfying(
            RedisStagingFenceException.class,
            failure -> assertThat(failure.reason())
                .isEqualTo(RedisStagingFenceException.Reason.GENERATION_CHANGED)
        );
        assertThat(redisTemplate.hasKey(prepared.keyspace().familyKey(
            snapshotId,
            RedisSnapshotFamily.PERSONS
        ))).isFalse();
        assertThat(redisTemplate.hasKey(prepared.keyspace().familyIndexKey(
            snapshotId,
            RedisSnapshotFamily.PERSONS
        ))).isFalse();
    }

    private void assertRealCorruptionDoesNotPartiallyMutate(
        String datasetId,
        boolean halfPair,
        boolean hugePayload
    ) {
        Prepared prepared = prepare(datasetId);
        StrictRedisSnapshotWriter writer = new StrictRedisSnapshotWriter(
            redisConnectionFactory,
            new RedisSnapshotWriteLimits(16, 64, 1024, 8192)
        );
        RedisStagingSession session = writer.begin(
            prepared.primary(),
            prepared.fence(),
            prepared.lease()
        );
        UUID snapshotId = session.snapshotId();
        String dataKey = prepared.keyspace().familyKey(
            snapshotId,
            RedisSnapshotFamily.PERSONS
        );
        String indexKey = prepared.keyspace().familyIndexKey(
            snapshotId,
            RedisSnapshotFamily.PERSONS
        );
        RedisCanonicalEntry old = personEntry(2);
        redisTemplate.opsForHash().put(
            dataKey,
            "2",
            halfPair
                ? new String(old.canonicalPayload(), StandardCharsets.UTF_8)
                : hugePayload
                    ? "x".repeat(1024 * 1024)
                    : new String(personPayload(9), StandardCharsets.UTF_8)
        );
        if (!halfPair) {
            redisTemplate.opsForZSet().add(indexKey, "2", 0);
        }

        RedisStagingCorruptionException.Reason expected = halfPair
            ? RedisStagingCorruptionException.Reason.ENTRY_HALF_PAIR
            : RedisStagingCorruptionException.Reason.ENTRY_PAYLOAD_MISMATCH;
        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(personEntry(1), personEntry(2))
        )).isInstanceOfSatisfying(
            RedisStagingCorruptionException.class,
            failure -> assertThat(failure.reason()).isEqualTo(expected)
        );
        assertThat(redisTemplate.opsForHash().hasKey(dataKey, "1")).isFalse();
        assertThat(redisTemplate.opsForZSet().score(indexKey, "1")).isNull();
        assertThat(redisTemplate.hasKey(prepared.keyspace().manifestKey(snapshotId))).isFalse();
    }

    private Prepared prepare(String datasetId) {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(datasetId);
        RedisPrimarySnapshot absent = new StrictRedisProtocolClient(redisConnectionFactory)
            .readPrimarySnapshot(keyspace);
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity(datasetId, 1);
        SecurityDatasetFence fence = new SecurityDatasetFence(identity, 4);
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            new RedisIncarnation(absent.observation().runId(), STORAGE_UUID),
            5,
            null,
            RedisControlState.INITIALIZING
        );
        redisTemplate.opsForHash().putAll(keyspace.controlKey(), controlCodec.encode(control));
        Long redisTime = redisTemplate.execute(
            (org.springframework.data.redis.core.RedisCallback<Long>)
                org.springframework.data.redis.connection.RedisConnection::time
        );
        long now = redisTime == null ? System.currentTimeMillis() : redisTime;
        RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
            identity,
            keyspace.datasetHash(),
            control.getIncarnation(),
            control.getCounter(),
            RedisCoordinatorLease.State.ACTIVE,
            1,
            1,
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            now - 1_000L,
            now + 120_000L
        );
        RedisCoordinatorLease.Verified lease = RedisCoordinatorLease.verifyActiveForContext(
            candidate,
            identity,
            keyspace.datasetHash(),
            control.getIncarnation(),
            control.getCounter(),
            1,
            1,
            candidate.ownerSessionId(),
            candidate.acquisitionId(),
            candidate.issuedAtRedisMillis(),
            candidate.expiresAtRedisMillis(),
            now
        );
        redisTemplate.opsForHash().putAll(
            keyspace.leaseKey(),
            new RedisCoordinatorLeaseCodec().encode(lease)
        );
        RedisPrimarySnapshot primary = new StrictRedisProtocolClient(redisConnectionFactory)
            .readPrimarySnapshot(keyspace);
        assertThat(primary.controlEnvelope()).contains(control);
        return new Prepared(keyspace, fence, primary, lease);
    }

    private Map<Object, Object> controlFields(RedisDatasetKeyspace keyspace) {
        return new LinkedHashMap<>(redisTemplate.opsForHash().entries(keyspace.controlKey()));
    }

    private static void completeAll(RedisStagingSession session) {
        session.complete(RedisSnapshotFamily.PRIVILEGES);
        session.complete(RedisSnapshotFamily.ROLES);
        session.complete(RedisSnapshotFamily.PARTY_ROLES);
        session.complete(RedisSnapshotFamily.PERSONS);
        session.complete(RedisSnapshotFamily.POSITION_ROLES);
        session.complete(RedisSnapshotFamily.ORGANIZATIONS);
    }

    private static RedisSnapshotContentDigest content(
        RedisDatasetKeyspace keyspace,
        UUID snapshotId,
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries
    ) {
        List<RedisSnapshotFamilyDigest> digests = new ArrayList<>(6);
        for (RedisSnapshotFamily family : List.of(
            RedisSnapshotFamily.PERSONS,
            RedisSnapshotFamily.ORGANIZATIONS,
            RedisSnapshotFamily.PARTY_ROLES,
            RedisSnapshotFamily.POSITION_ROLES,
            RedisSnapshotFamily.ROLES,
            RedisSnapshotFamily.PRIVILEGES
        )) {
            RedisSnapshotFamilyAccumulator accumulator = new RedisSnapshotFamilyAccumulator(
                familyCode(family),
                keyspace.familyKey(snapshotId, family),
                keyspace.familyIndexKey(snapshotId, family)
            );
            for (RedisCanonicalEntry entry : RedisCanonicalEntryOrder.sortedCopy(
                entries.getOrDefault(family, Collections.emptyList())
            )) {
                accumulator.add(entry);
            }
            digests.add(accumulator.finish());
        }
        return new RedisSnapshotContentDigest(
            digests.get(0),
            digests.get(1),
            digests.get(2),
            digests.get(3),
            digests.get(4),
            digests.get(5)
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

    private static RedisCanonicalEntry personEntry(long personId) {
        return new RedisCanonicalEntry(
            Long.toString(personId).getBytes(StandardCharsets.US_ASCII),
            personPayload(personId)
        );
    }

    private static byte[] personPayload(long personId) {
        return new RedisCanonicalSnapshotPayloadCodec().encode(
            new PersonDef(personId, null)
        );
    }

    private record Prepared(
        RedisDatasetKeyspace keyspace,
        SecurityDatasetFence fence,
        RedisPrimarySnapshot primary,
        RedisCoordinatorLease.Verified lease
    ) {
    }
}
