package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrictRedisBootstrapTripletProbeTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void executesExactlyOneWriteRoutedBoundedReadOnlyEvalOverTrustedTripletKeys() {
        Fixture fixture = fixture();
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant:*?[x]:a");
        Object raw = List.of("raw");
        RedisBootstrapTripletObservation decoded = absentObservation(keyspace);
        when(fixture.commands.eval(
            any(byte[].class),
            eq(ReturnType.MULTI),
            eq(3),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class)
        )).thenReturn(raw);
        when(fixture.codec.decode(raw, keyspace)).thenReturn(decoded);

        assertThat(fixture.probe.readTriplet(keyspace)).isSameAs(decoded);

        ArgumentCaptor<byte[]> scriptCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> controlKey = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> leaseKey = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> counterKey = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.commands).eval(
            scriptCaptor.capture(),
            eq(ReturnType.MULTI),
            eq(3),
            controlKey.capture(),
            leaseKey.capture(),
            counterKey.capture()
        );
        assertThat(utf8(controlKey.getValue())).isEqualTo(keyspace.controlKey());
        assertThat(utf8(leaseKey.getValue())).isEqualTo(keyspace.leaseKey());
        assertThat(utf8(counterKey.getValue())).isEqualTo(keyspace.leaseCounterKey());
        assertThat(utf8(controlKey.getValue()))
            .doesNotContain("tenant:*?[x]:a")
            .startsWith("orgsec:v1:{")
            .endsWith(":control");

        String script = new String(scriptCaptor.getValue(), StandardCharsets.US_ASCII);
        assertThat(script)
            .contains("local MAX_INFO = 65536")
            .contains("if #KEYS ~= 3")
            .contains("redis.call('INFO', 'server')")
            .contains("redis.call('INFO', 'replication')")
            .contains("redis.call('INFO', 'cluster')")
            .contains("redis.call('INFO', 'memory')")
            .contains("redis.call('TYPE', key)")
            .contains("redis.call('PTTL', key)")
            .contains("redis.call('HLEN', key)")
            .contains("redis.call('HSTRLEN', key, name)")
            .contains("redis.call('HMGET', key, unpack(names))")
            .contains("redis.call('STRLEN', key)")
            .contains("redis.call('GET', key)")
            .contains("length == 0 or length > 16")
            .contains("'datasetId'", "'activeSnapshotId'", "'schemaVersion'")
            .contains("'fencingSequence'", "'expiresAtRedisMillis'")
            .doesNotContain(
                "HGETALL",
                "redis.call('SET'",
                "redis.call('HSET'",
                "redis.call('INCR'",
                "redis.call('DEL'",
                "redis.call('UNLINK'",
                "redis.call('EXPIRE'",
                "redis.call('PEXPIRE'",
                "redis.pcall"
            );
        verify(fixture.codec).decode(raw, keyspace);
        verify(fixture.connection).close();
    }

    @Test
    void validatesTrustedKeyspaceAndDependenciesBeforeAnyIo() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisBootstrapTripletCodec codec = mock(RedisBootstrapTripletCodec.class);

        assertThatThrownBy(() -> new StrictRedisBootstrapTripletProbe(null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("connectionFactory");
        assertThatThrownBy(() -> new StrictRedisBootstrapTripletProbe(factory, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("codec");
        assertThatThrownBy(() -> new StrictRedisBootstrapTripletProbe(factory, codec)
            .readTriplet(null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("requestedKeyspace");
        verifyNoInteractions(factory, codec);
    }

    @Test
    void wrapsConnectionAndScriptTransportFailuresAndAlwaysCloses() {
        RedisConnectionFactory failingFactory = mock(RedisConnectionFactory.class);
        IllegalStateException acquisition = new IllegalStateException("offline");
        when(failingFactory.getConnection()).thenThrow(acquisition);
        assertThatThrownBy(() -> new StrictRedisBootstrapTripletProbe(failingFactory)
            .readTriplet(new RedisDatasetKeyspace("tenant-a")))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasCause(acquisition);

        RedisConnectionFactory nullFactory = mock(RedisConnectionFactory.class);
        when(nullFactory.getConnection()).thenReturn(null);
        assertThatThrownBy(() -> new StrictRedisBootstrapTripletProbe(nullFactory)
            .readTriplet(new RedisDatasetKeyspace("tenant-a")))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("no connection");

        Fixture missingCommands = fixture();
        when(missingCommands.connection.scriptingCommands()).thenReturn(null);
        assertThatThrownBy(() -> missingCommands.probe.readTriplet(
            new RedisDatasetKeyspace("tenant-a")
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("no scripting commands");
        verify(missingCommands.connection).close();

        Fixture failingScript = fixture();
        IllegalStateException evalFailure = new IllegalStateException("EVAL denied");
        when(failingScript.commands.eval(
            any(byte[].class),
            eq(ReturnType.MULTI),
            eq(3),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class)
        )).thenThrow(evalFailure);
        assertThatThrownBy(() -> failingScript.probe.readTriplet(
            new RedisDatasetKeyspace("tenant-a")
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasCause(evalFailure);
        verify(failingScript.connection).close();
        verifyNoInteractions(failingScript.codec);
    }

    @Test
    void preservesTypedDecodeFailureAndSuppressesOnlyTypedCloseFailure() {
        Fixture fixture = fixtureWithRawResult();
        RedisBootstrapTripletCorruptionException decodeFailure =
            new RedisBootstrapTripletCorruptionException(
                RedisBootstrapTripletCorruptionException.Reason.CONTROL_WIRE_INVALID
            );
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        when(fixture.codec.decode(any(), any())).thenThrow(decodeFailure);
        doThrow(closeFailure).when(fixture.connection).close();

        assertThatThrownBy(() -> fixture.probe.readTriplet(
            new RedisDatasetKeyspace("tenant-a")
        )).isSameAs(decodeFailure).satisfies(failure -> {
            assertThat(failure.getSuppressed()).hasSize(1);
            assertThat(failure.getSuppressed()[0])
                .isInstanceOf(RedisProtocolUnavailableException.class)
                .hasCause(closeFailure);
        });
    }

    @Test
    void rejectsNullScriptResultAndReportsCloseFailureAfterSuccessfulDecode() {
        Fixture nullResult = fixture();
        when(nullResult.commands.eval(
            any(byte[].class),
            eq(ReturnType.MULTI),
            eq(3),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class)
        )).thenReturn(null);
        assertThatThrownBy(() -> nullResult.probe.readTriplet(
            new RedisDatasetKeyspace("tenant-a")
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("returned no result");
        verify(nullResult.connection).close();

        Fixture closeFailure = fixtureWithRawResult();
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        when(closeFailure.codec.decode(any(), any())).thenReturn(absentObservation(keyspace));
        IllegalStateException cause = new IllegalStateException("close failed");
        doThrow(cause).when(closeFailure.connection).close();
        assertThatThrownBy(() -> closeFailure.probe.readTriplet(keyspace))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("close")
            .hasCause(cause);
    }

    private static Fixture fixtureWithRawResult() {
        Fixture fixture = fixture();
        when(fixture.commands.eval(
            any(byte[].class),
            eq(ReturnType.MULTI),
            eq(3),
            any(byte[].class),
            any(byte[].class),
            any(byte[].class)
        )).thenReturn(List.of("raw"));
        return fixture;
    }

    private static Fixture fixture() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisScriptingCommands commands = mock(RedisScriptingCommands.class);
        RedisBootstrapTripletCodec codec = mock(RedisBootstrapTripletCodec.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.scriptingCommands()).thenReturn(commands);
        return new Fixture(
            factory,
            connection,
            commands,
            codec,
            new StrictRedisBootstrapTripletProbe(factory, codec)
        );
    }

    private static RedisBootstrapTripletObservation absentObservation(
        RedisDatasetKeyspace keyspace
    ) {
        return new RedisBootstrapTripletObservation(
            keyspace,
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            null,
            null,
            null
        );
    }

    private static String utf8(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private record Fixture(
        RedisConnectionFactory factory,
        RedisConnection connection,
        RedisScriptingCommands commands,
        RedisBootstrapTripletCodec codec,
        StrictRedisBootstrapTripletProbe probe
    ) {
    }
}
