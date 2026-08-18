package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrictRedisProtocolClientTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void executesOneBoundedPrimaryScriptAgainstTheCanonicalControlKeyAndCloses() {
        Fixture fixture = fixture();
        Object rawResult = List.of("raw");
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        RedisPrimarySnapshot decoded = snapshot();
        when(fixture.scriptingCommands.eval(
            any(byte[].class),
            eq(ReturnType.MULTI),
            eq(1),
            any(byte[].class)
        )).thenReturn(rawResult);
        when(fixture.snapshotCodec.decode(rawResult)).thenReturn(decoded);

        RedisPrimarySnapshot result = fixture.client.readPrimarySnapshot(keyspace);
        assertThat(result).isNotSameAs(decoded);
        assertThat(result.requestedKeyspace()).containsSame(keyspace);
        assertThat(result.observation()).isSameAs(decoded.observation());
        assertThat(result.controlEnvelope()).isEmpty();

        ArgumentCaptor<byte[]> script = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> key = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.scriptingCommands).eval(
            script.capture(),
            eq(ReturnType.MULTI),
            eq(1),
            key.capture()
        );
        assertThat(new String(script.getValue(), StandardCharsets.UTF_8))
            .contains("redis.call('HLEN', KEYS[1])")
            .contains("local withinLimits = fieldCount == " +
                RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT)
            .contains("redis.call('HSTRLEN', KEYS[1], fieldName)")
            .contains("redis.call('HMGET', KEYS[1], unpack(fieldNames))")
            .contains("'datasetId'", "'activeSnapshotId'", "'state'")
            .contains(controlFieldLimits())
            .contains("redis.call('INFO', 'server')")
            .contains("redis.call('INFO', 'replication')")
            .contains("redis.call('INFO', 'cluster')")
            .contains("redis.call('INFO', 'memory')")
            .doesNotContain("HGETALL");
        assertThat(new String(key.getValue(), StandardCharsets.UTF_8))
            .isEqualTo(keyspace.controlKey());
        verify(fixture.connectionFactory).getConnection();
        verify(fixture.connection).close();
    }

    private static String controlFieldLimits() {
        StringJoiner limits = new StringJoiner(", ", "{", "}");
        for (String field : RedisControlEnvelopeCodec.orderedFields()) {
            limits.add(Integer.toString(RedisControlEnvelopeCodec.maxUtf8Bytes(field)));
        }
        return limits.toString();
    }

    @Test
    void rejectsDecodedControlThatNamesADifferentRequestedDataset() {
        Fixture fixture = fixtureWithRawResult();
        when(fixture.snapshotCodec.decode(any())).thenReturn(controlSnapshot("tenant-b"));

        assertThatThrownBy(() -> fixture.client.readPrimarySnapshot(
            new RedisDatasetKeyspace("tenant-a")
        )).isInstanceOf(RedisControlCorruptionException.class)
            .hasMessageContaining("control dataset identity")
            .hasMessageContaining("requested keyspace");

        verify(fixture.connection).close();
    }

    @Test
    void validatesArgumentsBeforeOpeningAConnection() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisPrimarySnapshotCodec codec = mock(RedisPrimarySnapshotCodec.class);

        assertThatThrownBy(() -> new StrictRedisProtocolClient(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("connectionFactory");
        assertThatThrownBy(() -> new StrictRedisProtocolClient(factory, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("snapshotCodec");
        assertThatThrownBy(() -> new StrictRedisProtocolClient(factory, codec)
            .readPrimarySnapshot(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("keyspace");

        verifyNoInteractions(factory, codec);
    }

    @Test
    void wrapsConnectionAcquisitionFailuresAndRejectsNullConnections() {
        RedisConnectionFactory failingFactory = mock(RedisConnectionFactory.class);
        IllegalStateException cause = new IllegalStateException("offline");
        when(failingFactory.getConnection()).thenThrow(cause);

        assertThatThrownBy(() -> new StrictRedisProtocolClient(failingFactory)
            .readPrimarySnapshot(new RedisDatasetKeyspace("tenant-a")))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasCause(cause);

        RedisConnectionFactory nullFactory = mock(RedisConnectionFactory.class);
        when(nullFactory.getConnection()).thenReturn(null);
        assertThatThrownBy(() -> new StrictRedisProtocolClient(nullFactory)
            .readPrimarySnapshot(new RedisDatasetKeyspace("tenant-a")))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("no connection");
    }

    @Test
    void wrapsScriptFailuresAndAlwaysClosesTheConnection() {
        Fixture fixture = fixture();
        IllegalStateException cause = new IllegalStateException("EVAL denied");
        when(fixture.scriptingCommands.eval(
            any(byte[].class),
            eq(ReturnType.MULTI),
            eq(1),
            any(byte[].class)
        )).thenThrow(cause);

        assertThatThrownBy(() -> fixture.client.readPrimarySnapshot(
            new RedisDatasetKeyspace("tenant-a")
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("snapshot script")
            .hasCause(cause);

        verify(fixture.connection).close();
        verifyNoInteractions(fixture.snapshotCodec);
    }

    @Test
    void rejectsMissingScriptingCommandsAndNullScriptResults() {
        Fixture missingCommands = fixture();
        when(missingCommands.connection.scriptingCommands()).thenReturn(null);
        assertThatThrownBy(() -> missingCommands.client.readPrimarySnapshot(
            new RedisDatasetKeyspace("tenant-a")
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("no scripting commands");
        verify(missingCommands.connection).close();

        Fixture nullResult = fixture();
        when(nullResult.scriptingCommands.eval(
            any(byte[].class),
            eq(ReturnType.MULTI),
            eq(1),
            any(byte[].class)
        )).thenReturn(null);
        assertThatThrownBy(() -> nullResult.client.readPrimarySnapshot(
            new RedisDatasetKeyspace("tenant-a")
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("returned no result");
        verify(nullResult.connection).close();
    }

    @Test
    void preservesDecodeFailureAndSuppressesTypedCloseFailure() {
        Fixture fixture = fixtureWithRawResult();
        RedisControlCorruptionException decodeFailure =
            new RedisControlCorruptionException("bad shape");
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        when(fixture.snapshotCodec.decode(any())).thenThrow(decodeFailure);
        doThrow(closeFailure).when(fixture.connection).close();

        assertThatThrownBy(() -> fixture.client.readPrimarySnapshot(
            new RedisDatasetKeyspace("tenant-a")
        )).isSameAs(decodeFailure)
            .satisfies(failure -> {
                assertThat(failure.getSuppressed()).hasSize(1);
                assertThat(failure.getSuppressed()[0])
                    .isInstanceOf(RedisProtocolUnavailableException.class)
                    .hasCause(closeFailure);
            });
    }

    @Test
    void reportsCloseFailureAfterSuccessfulSnapshotRead() {
        Fixture fixture = fixtureWithRawResult();
        when(fixture.snapshotCodec.decode(any())).thenReturn(snapshot());
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        doThrow(closeFailure).when(fixture.connection).close();

        assertThatThrownBy(() -> fixture.client.readPrimarySnapshot(
            new RedisDatasetKeyspace("tenant-a")
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("close")
            .hasCause(closeFailure);
    }

    @Test
    void typedExceptionsExposeStableDiagnosticCodes() {
        RuntimeException cause = new RuntimeException("cause");

        assertThat(new RedisProtocolUnavailableException("down"))
            .hasMessageContaining(RedisProtocolUnavailableException.DIAGNOSTIC_CODE);
        assertThat(new RedisProtocolUnavailableException("down", cause)).hasCause(cause);
        assertThat(new RedisProtocolTopologyException("replica"))
            .hasMessageContaining(RedisProtocolTopologyException.DIAGNOSTIC_CODE);
        assertThat(new RedisProtocolConfigurationException("policy"))
            .hasMessageContaining(RedisProtocolConfigurationException.DIAGNOSTIC_CODE);
    }

    private static Fixture fixtureWithRawResult() {
        Fixture fixture = fixture();
        when(fixture.scriptingCommands.eval(
            any(byte[].class),
            eq(ReturnType.MULTI),
            eq(1),
            any(byte[].class)
        )).thenReturn(List.of("raw"));
        return fixture;
    }

    private static Fixture fixture() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisScriptingCommands scriptingCommands = mock(RedisScriptingCommands.class);
        RedisPrimarySnapshotCodec snapshotCodec = mock(RedisPrimarySnapshotCodec.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.scriptingCommands()).thenReturn(scriptingCommands);
        return new Fixture(
            connectionFactory,
            connection,
            scriptingCommands,
            snapshotCodec,
            new StrictRedisProtocolClient(connectionFactory, snapshotCodec)
        );
    }

    private static RedisPrimarySnapshot snapshot() {
        return new RedisPrimarySnapshot(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            null
        );
    }

    private static RedisPrimarySnapshot controlSnapshot(String datasetId) {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity(datasetId, 1);
        return new RedisPrimarySnapshot(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            new RedisControlEnvelope(
                identity,
                new RedisIncarnation(RUN_ID, STORAGE_UUID),
                7,
                null,
                RedisControlState.INITIALIZING
            )
        );
    }

    private record Fixture(
        RedisConnectionFactory connectionFactory,
        RedisConnection connection,
        RedisScriptingCommands scriptingCommands,
        RedisPrimarySnapshotCodec snapshotCodec,
        StrictRedisProtocolClient client
    ) {
    }
}
