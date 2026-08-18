package com.nomendi6.orgsec.storage.redis.protocol;

import io.lettuce.core.KillArgs;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LettuceNonReconnectingPrimaryStreamIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:6.0-alpine")
    ).withExposedPorts(REDIS_PORT)
        .withCommand("redis-server", "--save", "", "--appendonly", "no");

    private static LettuceConnectionFactory sourceFactory;

    @BeforeAll
    static void createSourceFactory() {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
            REDIS.getHost(),
            REDIS.getMappedPort(REDIS_PORT)
        );
        sourceFactory = new LettuceConnectionFactory(server);
        sourceFactory.afterPropertiesSet();

        try (RedisConnection connection = sourceFactory.getConnection()) {
            assertThat(connection.serverCommands().info("server").getProperty("redis_version"))
                .startsWith("6.0.");
        }
    }

    @AfterAll
    static void destroySourceFactory() {
        if (sourceFactory != null) {
            sourceFactory.destroy();
        }
    }

    @Test
    void eachStreamOwnsAnotherClientAndRunsEvalAndClientIdOnIt() {
        try (LettuceNonReconnectingPrimaryStreamFactory factory =
                 new LettuceNonReconnectingPrimaryStreamFactory(sourceFactory);
             RedisPrimaryCommandStream first = factory.open();
             RedisPrimaryCommandStream second = factory.open()) {
            long firstClientId = first.clientId();
            long secondClientId = second.clientId();

            assertThat(firstClientId).isPositive().isNotEqualTo(secondClientId);
            assertThat(secondClientId).isPositive();

            Long firstResult = first.eval(
                ascii("return redis.call('INCRBY', KEYS[1], ARGV[1])"),
                ScriptOutputType.INTEGER,
                bytes("orgsec:stream:first"),
                bytes("7")
            );
            Long secondResult = second.eval(
                ascii("return redis.call('INCRBY', KEYS[1], ARGV[1])"),
                ScriptOutputType.INTEGER,
                bytes("orgsec:stream:second"),
                bytes("11")
            );

            assertThat(firstResult).isEqualTo(7L);
            assertThat(secondResult).isEqualTo(11L);
            assertThat(first.clientId()).isEqualTo(firstClientId);
            assertThat(second.clientId()).isEqualTo(secondClientId);
            assertThat(first.isPoisoned()).isFalse();
            assertThat(second.isPoisoned()).isFalse();
        }
    }

    @Test
    void sourceReplicaReadPreferenceIsNotCopiedIntoDedicatedStream() {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
            REDIS.getHost(),
            REDIS.getMappedPort(REDIS_PORT)
        );
        LettuceClientConfiguration sourceClient = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.REPLICA)
            .commandTimeout(Duration.ofSeconds(2))
            .build();
        LettuceConnectionFactory replicaPreferredSource = new LettuceConnectionFactory(
            server,
            sourceClient
        );
        replicaPreferredSource.afterPropertiesSet();
        try (LettuceNonReconnectingPrimaryStreamFactory factory =
                 new LettuceNonReconnectingPrimaryStreamFactory(replicaPreferredSource);
             RedisPrimaryCommandStream stream = factory.open()) {
            Long result = stream.eval(
                ascii("return redis.call('INCRBY', KEYS[1], ARGV[1])"),
                ScriptOutputType.INTEGER,
                bytes("orgsec:stream:forced-primary"),
                bytes("13")
            );

            assertThat(result).isEqualTo(13L);
            assertThat(stream.clientId()).isPositive();
            assertThat(stream.isPoisoned()).isFalse();
        } finally {
            replicaPreferredSource.destroy();
        }
    }

    @Test
    void ownerThreadIsEnforcedAndOwnerCanStillCloseTheStream() {
        try (LettuceNonReconnectingPrimaryStreamFactory factory =
                 new LettuceNonReconnectingPrimaryStreamFactory(sourceFactory)) {
            RedisPrimaryCommandStream stream = factory.open();
            long clientId = stream.clientId();

            CompletableFuture<Long> crossThread = CompletableFuture.supplyAsync(
                stream::clientId
            );
            assertThatThrownBy(crossThread::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                    "Redis primary command stream may only be used by its owner thread"
                );

            assertThat(stream.clientId()).isEqualTo(clientId);
            stream.close();
            assertThatThrownBy(stream::clientId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after the Redis command stream closed");
        }
    }

    @Test
    void evalRejectsOversizedInputsBeforeAllocationOrRedisIo() {
        try (LettuceNonReconnectingPrimaryStreamFactory factory =
                 new LettuceNonReconnectingPrimaryStreamFactory(sourceFactory);
             RedisPrimaryCommandStream stream = factory.open()) {
            byte[] validScript = ascii("return 1");
            byte[] maxArgument = new byte[RedisPrimaryCommandStream.MAX_ARGUMENT_BYTES];
            byte[][] overTotal = new byte[9][];
            Arrays.fill(overTotal, maxArgument);

            assertThatThrownBy(() -> stream.eval(
                new byte[RedisPrimaryCommandStream.MAX_SCRIPT_BYTES + 1],
                ScriptOutputType.INTEGER,
                new byte[0][],
                new byte[0][]
            )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_SCRIPT_BYTES");
            assertThatThrownBy(() -> stream.eval(
                validScript,
                ScriptOutputType.INTEGER,
                new byte[][]{new byte[RedisPrimaryCommandStream.MAX_KEY_BYTES + 1]},
                new byte[0][]
            )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keys")
                .hasMessageContaining("oversized");
            assertThatThrownBy(() -> stream.eval(
                validScript,
                ScriptOutputType.INTEGER,
                new byte[0][],
                overTotal
            )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAX_COMMAND_BYTES");

            assertThat(stream.isPoisoned()).isFalse();
            assertThat(stream.clientId()).isPositive();
        }
    }

    @Test
    void closingStreamClosesItsOwnedRedisClient() {
        try (LettuceNonReconnectingPrimaryStreamFactory factory =
                 new LettuceNonReconnectingPrimaryStreamFactory(sourceFactory)) {
            RedisPrimaryCommandStream stream = factory.open();
            long clientId = stream.clientId();
            assertThat(clientIds()).contains(clientId);

            stream.close();

            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> assertThat(clientIds()).doesNotContain(clientId));
        }
    }

    @Test
    void killedConnectionPermanentlyPoisonsWithoutTransparentReconnect() {
        try (LettuceNonReconnectingPrimaryStreamFactory factory =
                 new LettuceNonReconnectingPrimaryStreamFactory(sourceFactory)) {
            RedisPrimaryCommandStream stream = factory.open();
            long killedClientId = stream.clientId();

            killClient(killedClientId);
            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(25))
                .until(stream::isPoisoned);

            assertThatThrownBy(stream::clientId)
                .isInstanceOf(RedisProtocolUnavailableException.class)
                .hasMessageContaining("permanently poisoned");
            assertThatThrownBy(stream::clientId)
                .isInstanceOf(RedisProtocolUnavailableException.class)
                .hasMessageContaining("permanently poisoned");

            try (RedisPrimaryCommandStream replacement = factory.open()) {
                assertThat(replacement.clientId()).isNotEqualTo(killedClientId);
                assertThat(replacement.isPoisoned()).isFalse();
            }
            stream.close();
        }
    }

    @Test
    void commandExceptionPermanentlyPoisonsTheStream() {
        try (LettuceNonReconnectingPrimaryStreamFactory factory =
                 new LettuceNonReconnectingPrimaryStreamFactory(sourceFactory)) {
            RedisPrimaryCommandStream stream = factory.open();

            assertThatThrownBy(() -> stream.eval(
                ascii("this is not valid Lua"),
                ScriptOutputType.INTEGER,
                new byte[0][],
                new byte[0][]
            )).isInstanceOf(RedisProtocolUnavailableException.class)
                .hasMessageContaining("script execution failed");
            assertThat(stream.isPoisoned()).isTrue();
            assertThatThrownBy(stream::clientId)
                .isInstanceOf(RedisProtocolUnavailableException.class)
                .hasMessageContaining("permanently poisoned");

            stream.close();
        }
    }

    private static void killClient(long clientId) {
        try (RedisConnection connection = sourceFactory.getConnection()) {
            nativeCommands(connection).clientKill(new KillArgs().id(clientId).skipme(false));
        }
    }

    private static long[] clientIds() {
        try (RedisConnection connection = sourceFactory.getConnection()) {
            String clientList = nativeCommands(connection).clientList();
            return Arrays.stream(clientList.split("\\r?\\n"))
                .filter(line -> !line.isBlank())
                .map(line -> Arrays.stream(line.split(" "))
                    .filter(field -> field.startsWith("id="))
                    .findFirst()
                    .orElseThrow())
                .map(field -> field.substring("id=".length()))
                .mapToLong(Long::parseLong)
                .toArray();
        }
    }

    private static RedisCommands<byte[], byte[]> nativeCommands(
        RedisConnection connection
    ) {
        Object rawCommands = connection.getNativeConnection();
        if (!(rawCommands instanceof RedisAsyncCommands<?, ?> untypedCommands)) {
            throw new IllegalStateException(
                "Lettuce connection did not expose standalone native commands"
            );
        }
        @SuppressWarnings("unchecked")
        RedisAsyncCommands<byte[], byte[]> commands =
            (RedisAsyncCommands<byte[], byte[]>) untypedCommands;
        return commands.getStatefulConnection().sync();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[][] bytes(String... values) {
        return Arrays.stream(values).map(LettuceNonReconnectingPrimaryStreamIntegrationTest::ascii)
            .toArray(byte[][]::new);
    }
}
