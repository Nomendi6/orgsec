package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import io.lettuce.core.ReadFrom;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictRedisProtocolClientMasterReplicaIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:6.0-alpine");
    private static final int REDIS_PORT = 6379;
    private static final String PRIMARY_ALIAS = "orgsec-primary";
    private static final UUID PRIMARY_STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID REPLICA_STORAGE_UUID =
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID PRIMARY_SNAPSHOT_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID REPLICA_SNAPSHOT_ID =
        UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

    private static Network network;
    private static GenericContainer<?> primary;
    private static GenericContainer<?> replica;
    private static LettuceConnectionFactory primaryFactory;
    private static LettuceConnectionFactory replicaFactory;

    private final RedisControlEnvelopeCodec controlCodec = new RedisControlEnvelopeCodec();

    @BeforeAll
    static void startTopology() {
        network = Network.newNetwork();
        primary = new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withNetworkAliases(PRIMARY_ALIAS)
            .withExposedPorts(REDIS_PORT)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");
        replica = new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withExposedPorts(REDIS_PORT)
            .withCommand(
                "redis-server",
                "--save",
                "",
                "--appendonly",
                "no",
                "--replicaof",
                PRIMARY_ALIAS,
                Integer.toString(REDIS_PORT),
                "--replica-read-only",
                "no"
            );

        try {
            primary.start();
            replica.start();
            primaryFactory = standaloneFactory(primary);
            replicaFactory = standaloneFactory(replica);
            assertThat(info(primaryFactory, "server").getProperty("redis_version"))
                .startsWith("6.0.");
            assertThat(info(replicaFactory, "server").getProperty("redis_version"))
                .startsWith("6.0.");
            Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "up".equals(info(replicaFactory, "replication")
                    .getProperty("master_link_status")));
        } catch (RuntimeException | Error failure) {
            closeResources(failure);
            throw failure;
        }
    }

    @AfterAll
    static void stopTopology() {
        closeResources(null);
    }

    @Test
    void directWritableReplicaFailsWithTypedTopologyError() {
        assertThatThrownBy(() -> new StrictRedisProtocolClient(replicaFactory)
            .readPrimarySnapshot(new RedisDatasetKeyspace("direct-replica")))
            .isInstanceOf(RedisProtocolTopologyException.class)
            .hasMessageContaining("role=slave");
    }

    @Test
    void replicaReadPreferenceStillRoutesAtomicEvalToPrimary() {
        assertPrimarySnapshotUnder(ReadFrom.REPLICA, "read-from-replica", true);
    }

    @Test
    void anyReadPreferenceNeverMakesAtomicEvalAcceptAReplicaSnapshot() {
        assertPrimarySnapshotUnder(ReadFrom.ANY, "read-from-any", false);
    }

    private void assertPrimarySnapshotUnder(
        ReadFrom readFrom,
        String datasetId,
        boolean assertOrdinaryReplicaRead
    ) {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(datasetId);
        String primaryRunId = info(primaryFactory, "server").getProperty("run_id");
        String replicaRunId = info(replicaFactory, "server").getProperty("run_id");
        RedisControlEnvelope primaryEnvelope = envelope(
            datasetId,
            primaryRunId,
            PRIMARY_STORAGE_UUID,
            PRIMARY_SNAPSHOT_ID
        );
        RedisControlEnvelope replicaEnvelope = envelope(
            datasetId,
            replicaRunId,
            REPLICA_STORAGE_UUID,
            REPLICA_SNAPSHOT_ID
        );

        writeEnvelope(primaryFactory, keyspace, primaryEnvelope);
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(50))
            .until(() -> Long.valueOf(RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT)
                .equals(hashLength(replicaFactory, keyspace.controlKey())));
        writeEnvelope(replicaFactory, keyspace, replicaEnvelope);

        LettuceConnectionFactory routedFactory = masterReplicaFactory(readFrom);
        try {
            if (assertOrdinaryReplicaRead) {
                assertThat(readHashField(
                    routedFactory,
                    keyspace.controlKey(),
                    RedisControlEnvelopeCodec.FIELD_ACTIVE_SNAPSHOT_ID
                )).isEqualTo(REPLICA_SNAPSHOT_ID.toString());
            }

            RedisPrimarySnapshot snapshot = new StrictRedisProtocolClient(routedFactory)
                .readPrimarySnapshot(keyspace);

            assertThat(snapshot.observation().runId()).isEqualTo(primaryRunId);
            assertThat(snapshot.controlEnvelope()).contains(primaryEnvelope);
            assertThat(snapshot.controlEnvelope().orElseThrow()).isNotEqualTo(replicaEnvelope);
        } finally {
            routedFactory.destroy();
        }
    }

    private static LettuceConnectionFactory standaloneFactory(GenericContainer<?> container) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
            container.getHost(),
            container.getMappedPort(REDIS_PORT)
        );
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    private static LettuceConnectionFactory masterReplicaFactory(ReadFrom readFrom) {
        RedisStaticMasterReplicaConfiguration configuration =
            new RedisStaticMasterReplicaConfiguration(
                primary.getHost(),
                primary.getMappedPort(REDIS_PORT)
            );
        configuration.addNode(replica.getHost(), replica.getMappedPort(REDIS_PORT));
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
            .readFrom(readFrom)
            .commandTimeout(Duration.ofSeconds(5))
            .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
            configuration,
            clientConfiguration
        );
        factory.afterPropertiesSet();
        return factory;
    }

    private void writeEnvelope(
        LettuceConnectionFactory factory,
        RedisDatasetKeyspace keyspace,
        RedisControlEnvelope envelope
    ) {
        Map<byte[], byte[]> raw = new LinkedHashMap<>();
        for (Map.Entry<String, String> field : controlCodec.encode(envelope).entrySet()) {
            raw.put(utf8(field.getKey()), utf8(field.getValue()));
        }
        try (RedisConnection connection = factory.getConnection()) {
            connection.hashCommands().hMSet(utf8(keyspace.controlKey()), raw);
        }
    }

    private static Long hashLength(LettuceConnectionFactory factory, String key) {
        try (RedisConnection connection = factory.getConnection()) {
            return connection.hashCommands().hLen(utf8(key));
        }
    }

    private static String readHashField(
        LettuceConnectionFactory factory,
        String key,
        String field
    ) {
        try (RedisConnection connection = factory.getConnection()) {
            byte[] value = connection.hashCommands().hGet(utf8(key), utf8(field));
            return value == null ? null : new String(value, StandardCharsets.UTF_8);
        }
    }

    private static Properties info(LettuceConnectionFactory factory, String section) {
        try (RedisConnection connection = factory.getConnection()) {
            return connection.serverCommands().info(section);
        }
    }

    private static RedisControlEnvelope envelope(
        String datasetId,
        String primaryRunId,
        UUID storageUuid,
        UUID snapshotId
    ) {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity(datasetId, 1);
        return new RedisControlEnvelope(
            identity,
            new RedisIncarnation(primaryRunId, storageUuid),
            7,
            snapshotId,
            RedisControlState.READY
        );
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void closeResources(Throwable pendingFailure) {
        Throwable cleanupFailure = null;
        cleanupFailure = destroy(primaryFactory, cleanupFailure);
        primaryFactory = null;
        cleanupFailure = destroy(replicaFactory, cleanupFailure);
        replicaFactory = null;
        cleanupFailure = stop(replica, cleanupFailure);
        replica = null;
        cleanupFailure = stop(primary, cleanupFailure);
        primary = null;
        cleanupFailure = close(network, cleanupFailure);
        network = null;

        if (cleanupFailure != null) {
            if (pendingFailure != null) {
                pendingFailure.addSuppressed(cleanupFailure);
            } else if (cleanupFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            } else if (cleanupFailure instanceof Error error) {
                throw error;
            } else {
                throw new IllegalStateException("Failed to clean up Redis topology", cleanupFailure);
            }
        }
    }

    private static Throwable destroy(
        LettuceConnectionFactory factory,
        Throwable cleanupFailure
    ) {
        if (factory == null) {
            return cleanupFailure;
        }
        try {
            factory.destroy();
            return cleanupFailure;
        } catch (RuntimeException | Error failure) {
            return accumulate(cleanupFailure, failure);
        }
    }

    private static Throwable stop(GenericContainer<?> container, Throwable cleanupFailure) {
        if (container == null) {
            return cleanupFailure;
        }
        try {
            container.stop();
            return cleanupFailure;
        } catch (RuntimeException | Error failure) {
            return accumulate(cleanupFailure, failure);
        }
    }

    private static Throwable close(Network redisNetwork, Throwable cleanupFailure) {
        if (redisNetwork == null) {
            return cleanupFailure;
        }
        try {
            redisNetwork.close();
            return cleanupFailure;
        } catch (RuntimeException | Error failure) {
            return accumulate(cleanupFailure, failure);
        }
    }

    private static Throwable accumulate(Throwable accumulated, Throwable failure) {
        if (accumulated == null) {
            return failure;
        }
        accumulated.addSuppressed(failure);
        return accumulated;
    }
}
