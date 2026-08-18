package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictRedisSnapshotReaderPromotionIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse(
        "redis:8.4.0-alpine"
    );
    private static final int REDIS_PORT = 6379;
    private static final String PRIMARY_ALIAS = "orgsec-reader-primary";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void promotedReplicaCannotServeAPageUnderTheReplicatedOldControlRunId() throws Exception {
        Network network = Network.newNetwork();
        GenericContainer<?> primary = new GenericContainer<>(REDIS_IMAGE)
            .withNetwork(network)
            .withNetworkAliases(PRIMARY_ALIAS)
            .withExposedPorts(REDIS_PORT)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");
        GenericContainer<?> replica = new GenericContainer<>(REDIS_IMAGE)
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
                Integer.toString(REDIS_PORT)
            );
        LettuceConnectionFactory primaryFactory = null;
        LettuceConnectionFactory replicaFactory = null;
        try {
            primary.start();
            replica.start();
            primaryFactory = factory(primary);
            replicaFactory = factory(replica);
            LettuceConnectionFactory livePrimaryFactory = primaryFactory;
            LettuceConnectionFactory liveReplicaFactory = replicaFactory;
            Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "up".equals(info(
                    liveReplicaFactory,
                    "replication"
                ).getProperty("master_link_status")));

            SwitchingRedisConnectionFactory routedFactory =
                new SwitchingRedisConnectionFactory(livePrimaryFactory);
            Published published = publish(livePrimaryFactory);
            Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> replicated(liveReplicaFactory, published));
            RedisSnapshotReadSession session = reader(routedFactory).openActive(
                published.generation,
                published.fence
            );

            primary.stop();
            var promotion = replica.execInContainer(
                "redis-cli",
                "REPLICAOF",
                "NO",
                "ONE"
            );
            assertThat(promotion.getExitCode()).isZero();
            Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> "master".equals(info(
                    liveReplicaFactory,
                    "replication"
                ).getProperty("role")));
            assertThat(info(liveReplicaFactory, "server").getProperty("run_id"))
                .isNotEqualTo(published.generation.observation().runId());
            routedFactory.switchTo(liveReplicaFactory);

            assertThatThrownBy(() -> session.readNextPage(RedisSnapshotFamily.PERSONS))
                .isInstanceOf(RedisSnapshotReadException.class)
                .satisfies(failure -> assertThat(
                    ((RedisSnapshotReadException) failure).reason()
                ).isEqualTo(RedisSnapshotReadException.Reason.GENERATION_CHANGED));
        } finally {
            destroy(replicaFactory);
            destroy(primaryFactory);
            replica.stop();
            primary.stop();
            network.close();
        }
    }

    private static Published publish(LettuceConnectionFactory primaryFactory) {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("promotion-tenant");
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity("promotion-tenant", 1);
        SecurityDatasetFence fence = new SecurityDatasetFence(identity, 9);
        RedisSnapshotManifest.PendingPublication pending =
            RedisSnapshotManifest.beginPublication(fence);
        UUID snapshotId = pending.snapshotId();
        RedisCanonicalEntry person = new RedisCanonicalEntry(
            bytes("1"),
            new RedisCanonicalSnapshotPayloadCodec().encode(new PersonDef(1L, "Person 1"))
        );
        RedisSnapshotFamilyAccumulator persons = accumulator(
            keyspace,
            snapshotId,
            RedisSnapshotFamily.PERSONS,
            RedisSnapshotFamilyCode.PERSONS
        );
        persons.add(person);
        RedisSnapshotContentDigest content = new RedisSnapshotContentDigest(
            persons.finish(),
            empty(
                keyspace,
                snapshotId,
                RedisSnapshotFamily.ORGANIZATIONS,
                RedisSnapshotFamilyCode.ORGANIZATIONS
            ),
            empty(
                keyspace,
                snapshotId,
                RedisSnapshotFamily.PARTY_ROLES,
                RedisSnapshotFamilyCode.PARTY_ROLES
            ),
            empty(
                keyspace,
                snapshotId,
                RedisSnapshotFamily.POSITION_ROLES,
                RedisSnapshotFamilyCode.POSITION_ROLES
            ),
            empty(
                keyspace,
                snapshotId,
                RedisSnapshotFamily.ROLES,
                RedisSnapshotFamilyCode.ROLES
            ),
            empty(
                keyspace,
                snapshotId,
                RedisSnapshotFamily.PRIVILEGES,
                RedisSnapshotFamilyCode.PRIVILEGES
            )
        );
        RedisSnapshotManifest.Verified manifest = pending.seal(content);
        RedisPrimarySnapshot absent = new StrictRedisProtocolClient(primaryFactory)
            .readPrimarySnapshot(keyspace);
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            new RedisIncarnation(absent.observation().runId(), STORAGE_UUID),
            7,
            snapshotId,
            RedisControlState.READY
        );

        try (RedisConnection connection = primaryFactory.getConnection()) {
            connection.hashCommands().hSet(
                bytes(keyspace.familyKey(snapshotId, RedisSnapshotFamily.PERSONS)),
                person.canonicalKey(),
                person.canonicalPayload()
            );
            connection.zSetCommands().zAdd(
                bytes(keyspace.familyIndexKey(snapshotId, RedisSnapshotFamily.PERSONS)),
                0,
                person.canonicalKey()
            );
            connection.hashCommands().hMSet(
                bytes(keyspace.manifestKey(snapshotId)),
                raw(new RedisSnapshotManifestCodec().encode(manifest))
            );
            connection.hashCommands().hMSet(
                bytes(keyspace.controlKey()),
                raw(new RedisControlEnvelopeCodec().encode(control))
            );
        }
        RedisPrimarySnapshot primary = new StrictRedisProtocolClient(primaryFactory)
            .readPrimarySnapshot(keyspace);
        return new Published(
            keyspace,
            fence,
            RedisSnapshotGeneration.from(primary, identity),
            snapshotId
        );
    }

    private static boolean replicated(
        LettuceConnectionFactory replicaFactory,
        Published published
    ) {
        try (RedisConnection connection = replicaFactory.getConnection()) {
            Long controlCount = connection.hashCommands().hLen(
                bytes(published.keyspace.controlKey())
            );
            Long manifestCount = connection.hashCommands().hLen(
                bytes(published.keyspace.manifestKey(published.snapshotId))
            );
            Long familyCount = connection.hashCommands().hLen(bytes(
                published.keyspace.familyKey(
                    published.snapshotId,
                    RedisSnapshotFamily.PERSONS
                )
            ));
            Long indexCount = connection.zSetCommands().zCard(bytes(
                published.keyspace.familyIndexKey(
                    published.snapshotId,
                    RedisSnapshotFamily.PERSONS
                )
            ));
            return Long.valueOf(RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT)
                .equals(controlCount)
                && Long.valueOf(RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT)
                    .equals(manifestCount)
                && Long.valueOf(1).equals(familyCount)
                && Long.valueOf(1).equals(indexCount);
        }
    }

    private static StrictRedisSnapshotReader reader(RedisConnectionFactory factory) {
        return new StrictRedisSnapshotReader(
            factory,
            new RedisSnapshotReadLimits(10, 20, 1024 * 1024, 1, 64, 1024, 2048)
        );
    }

    private static RedisSnapshotFamilyAccumulator accumulator(
        RedisDatasetKeyspace keyspace,
        UUID snapshotId,
        RedisSnapshotFamily family,
        RedisSnapshotFamilyCode code
    ) {
        return new RedisSnapshotFamilyAccumulator(
            code,
            keyspace.familyKey(snapshotId, family),
            keyspace.familyIndexKey(snapshotId, family)
        );
    }

    private static RedisSnapshotFamilyDigest empty(
        RedisDatasetKeyspace keyspace,
        UUID snapshotId,
        RedisSnapshotFamily family,
        RedisSnapshotFamilyCode code
    ) {
        return accumulator(keyspace, snapshotId, family, code).finish();
    }

    private static Map<byte[], byte[]> raw(Map<String, String> values) {
        Map<byte[], byte[]> raw = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            raw.put(bytes(entry.getKey()), bytes(entry.getValue()));
        }
        return raw;
    }

    private static LettuceConnectionFactory factory(GenericContainer<?> container) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
            container.getHost(),
            container.getMappedPort(REDIS_PORT)
        );
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    private static Properties info(LettuceConnectionFactory factory, String section) {
        try (RedisConnection connection = factory.getConnection()) {
            return connection.serverCommands().info(section);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void destroy(LettuceConnectionFactory factory) {
        if (factory != null) {
            factory.destroy();
        }
    }

    private record Published(
        RedisDatasetKeyspace keyspace,
        SecurityDatasetFence fence,
        RedisSnapshotGeneration generation,
        UUID snapshotId
    ) {
    }

    private static final class SwitchingRedisConnectionFactory
        implements RedisConnectionFactory {

        private volatile RedisConnectionFactory delegate;

        private SwitchingRedisConnectionFactory(RedisConnectionFactory delegate) {
            this.delegate = delegate;
        }

        void switchTo(RedisConnectionFactory next) {
            this.delegate = next;
        }

        @Override
        public boolean getConvertPipelineAndTxResults() {
            return delegate.getConvertPipelineAndTxResults();
        }

        @Override
        public RedisConnection getConnection() {
            return delegate.getConnection();
        }

        @Override
        public RedisClusterConnection getClusterConnection() {
            return delegate.getClusterConnection();
        }

        @Override
        public RedisSentinelConnection getSentinelConnection() {
            return delegate.getSentinelConnection();
        }

        @Override
        public DataAccessException translateExceptionIfPossible(RuntimeException exception) {
            return delegate.translateExceptionIfPossible(exception);
        }
    }
}
