package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.storage.redis.integration.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictRedisProtocolClientIntegrationTest extends AbstractRedisIntegrationTest {

    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID SNAPSHOT_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private final RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
    private final RedisControlEnvelopeCodec controlCodec = new RedisControlEnvelopeCodec();

    @BeforeEach
    void clearData() {
        clearRedis();
    }

    @Test
    void readsRealRedisInfoAndControlInOneAtomicSnapshot() {
        StrictRedisProtocolClient client = new StrictRedisProtocolClient(redisConnectionFactory);

        RedisPrimarySnapshot absent = client.readPrimarySnapshot(keyspace);
        assertThat(absent.controlEnvelope()).isEmpty();
        assertThat(absent.observation().runId()).matches("[0-9a-f]{40}");
        assertThat(absent.observation().role()).isEqualTo("master");
        assertThat(absent.observation().clusterEnabled()).isFalse();
        assertThat(absent.observation().maxmemoryPolicy()).isEqualTo("noeviction");

        RedisControlEnvelope envelope = envelope(absent.observation().runId());
        redisTemplate.opsForHash().putAll(keyspace.controlKey(), controlCodec.encode(envelope));

        RedisPrimarySnapshot present = client.readPrimarySnapshot(keyspace);
        assertThat(present.observation().runId()).isEqualTo(absent.observation().runId());
        assertThat(present.controlEnvelope()).contains(envelope);
        assertThat(present.controlEnvelope().orElseThrow().getIncarnation().getPrimaryRunId())
            .isEqualTo(present.observation().runId());
        assertThat(present.controlEnvelope().orElseThrow().getIdentity().getSecurityDatasetId())
            .isEqualTo("tenant-a");
    }

    @Test
    void rejectsAnOversizedControlHashWithoutReturningItsFields() {
        Map<String, String> corrupt = new LinkedHashMap<>();
        int corruptFieldCount = RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT + 1;
        for (int index = 0; index < corruptFieldCount; index++) {
            corrupt.put("field-" + index, "value-" + index);
        }
        redisTemplate.opsForHash().putAll(keyspace.controlKey(), corrupt);

        assertThatThrownBy(() -> new StrictRedisProtocolClient(redisConnectionFactory)
            .readPrimarySnapshot(keyspace))
            .isInstanceOf(RedisControlCorruptionException.class)
            .hasMessageContaining(
                "contains " + corruptFieldCount + " fields",
                "either 0 or " + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
            );
    }

    @Test
    void rejectsOversizedKnownValuesBeforeHmgetReturnsAnyValue() {
        RedisPrimarySnapshot absent = new StrictRedisProtocolClient(redisConnectionFactory)
            .readPrimarySnapshot(keyspace);
        Map<String, String> oversized = new LinkedHashMap<>(
            controlCodec.encode(envelope(absent.observation().runId()))
        );
        oversized.put(
            RedisControlEnvelopeCodec.FIELD_DATASET_ID,
            "x".repeat(RedisControlEnvelopeCodec.MAX_DATASET_ID_UTF8_BYTES + 1)
        );
        redisTemplate.opsForHash().putAll(keyspace.controlKey(), oversized);

        assertThatThrownBy(() -> new StrictRedisProtocolClient(redisConnectionFactory)
            .readPrimarySnapshot(keyspace))
            .isInstanceOf(RedisControlCorruptionException.class)
            .hasMessageContaining("datasetId", "257 UTF-8 bytes", "maximum is 256");
    }

    @Test
    void neverReturnsHugeUnknownFieldNamesOrValues() {
        Map<String, String> unknown = new LinkedHashMap<>();
        unknown.put("n".repeat(512 * 1024), "small");
        unknown.put("unknown-1", "v".repeat(512 * 1024));
        for (int index = 2; index < RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT; index++) {
            unknown.put("unknown-" + index, "value-" + index);
        }
        redisTemplate.opsForHash().putAll(keyspace.controlKey(), unknown);

        assertThatThrownBy(() -> new StrictRedisProtocolClient(redisConnectionFactory)
            .readPrimarySnapshot(keyspace))
            .isInstanceOf(RedisControlCorruptionException.class)
            .hasMessageContaining("missing required field datasetId")
            .hasMessageNotContaining("unknown-1");
    }

    private static RedisControlEnvelope envelope(String primaryRunId) {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("tenant-a", 1);
        return new RedisControlEnvelope(
            identity,
            new RedisIncarnation(primaryRunId, STORAGE_UUID),
            7,
            SNAPSHOT_ID,
            RedisControlState.READY
        );
    }
}
