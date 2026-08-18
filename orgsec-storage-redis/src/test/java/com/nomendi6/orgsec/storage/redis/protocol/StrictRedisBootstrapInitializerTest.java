package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrictRedisBootstrapInitializerTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void writesOneCanonicalAbsentTripletWithOneBoundedAtomicEval() {
        Fixture fixture = fixture(ascii("INITIALIZED"));
        SecurityDatasetIdentity identity = identity("tenant:*?[x]:a");
        RedisPrimarySnapshot primary = primary(identity);

        assertThat(fixture.initializer.initialize(identity, primary)).isEqualTo(
            StrictRedisBootstrapInitializer.Outcome.INITIALIZED
        );

        ArgumentCaptor<byte[]> scriptCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.commands).eval(
            scriptCaptor.capture(),
            eq(ReturnType.VALUE),
            eq(3),
            any(byte[][].class)
        );
        byte[][] elements = fixture.commandElements.get();
        assertThat(elements).isNotNull();
        assertThat(elements.length).isEqualTo(
            3 + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
                + RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT + 1
        );

        RedisDatasetKeyspace keyspace = primary.requestedKeyspace().orElseThrow();
        assertThat(utf8(elements[0])).isEqualTo(keyspace.controlKey());
        assertThat(utf8(elements[1])).isEqualTo(keyspace.leaseKey());
        assertThat(utf8(elements[2])).isEqualTo(keyspace.leaseCounterKey());
        assertThat(utf8(elements[0]))
            .startsWith("orgsec:v1:{")
            .doesNotContain(identity.getSecurityDatasetId());

        int argumentOffset = 3;
        Map<String, String> controlFields = fields(
            elements,
            argumentOffset,
            RedisControlEnvelopeCodec.orderedFields()
        );
        RedisControlEnvelope control = new RedisControlEnvelopeCodec().decode(controlFields);
        assertThat(control.getIdentity()).isEqualTo(identity);
        assertThat(control.getIncarnation()).isEqualTo(
            new RedisIncarnation(RUN_ID, STORAGE_UUID)
        );
        assertThat(control.getCounter()).isZero();
        assertThat(control.getActiveSnapshotId()).isNull();
        assertThat(control.getState()).isEqualTo(RedisControlState.INITIALIZING);

        int leaseOffset = argumentOffset + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;
        Map<String, String> leaseFields = fields(
            elements,
            leaseOffset,
            RedisCoordinatorLeaseCodec.orderedFields()
        );
        RedisCoordinatorLease.Unverified lease =
            new RedisCoordinatorLeaseCodec().decode(leaseFields);
        RedisCoordinatorLease.Verified verified = RedisCoordinatorLease.verifyFreeForContext(
            lease,
            identity,
            keyspace.datasetHash(),
            control.getIncarnation(),
            0,
            0,
            0
        );
        assertThat(verified.state()).isEqualTo(RedisCoordinatorLease.State.FREE);
        assertThat(verified.ownerSessionId()).isNull();
        assertThat(verified.acquisitionId()).isNull();
        assertThat(verified.issuedAtRedisMillis()).isZero();
        assertThat(verified.expiresAtRedisMillis()).isZero();
        assertThat(utf8(elements[elements.length - 1])).isEqualTo("0");

        String script = new String(scriptCaptor.getValue(), StandardCharsets.US_ASCII);
        assertScriptContract(script);
        verify(fixture.connection).close();
    }

    @Test
    void requiresValidationForPresentMetadataAndMapsEveryFailClosedScriptStatus() {
        SecurityDatasetIdentity identity = identity("tenant-a");
        RedisPrimarySnapshot primary = primary(identity);

        Fixture present = fixture(ascii("PRESENT_REQUIRES_VALIDATION"));
        assertThat(present.initializer.initialize(identity, primary)).isEqualTo(
            StrictRedisBootstrapInitializer.Outcome.PRESENT_REQUIRES_VALIDATION
        );
        verify(present.connection).close();

        for (RedisBootstrapInitializationException.Reason reason :
            RedisBootstrapInitializationException.Reason.values()) {
            if (reason == RedisBootstrapInitializationException.Reason
                .INVALID_SCRIPT_RESPONSE) {
                continue;
            }
            Fixture failure = fixture(ascii(reason.name()));
            assertThatThrownBy(() -> failure.initializer.initialize(identity, primary))
                .isInstanceOfSatisfying(
                    RedisBootstrapInitializationException.class,
                    exception -> assertThat(exception.reason()).isEqualTo(reason)
                )
                .hasMessageContaining(
                    RedisBootstrapInitializationException.DIAGNOSTIC_CODE,
                    reason.name()
                );
            verify(failure.connection).close();
        }

        for (Object response : new Object[] {ascii("UNKNOWN"), "INITIALIZED"}) {
            Fixture invalid = fixture(response);
            assertThatThrownBy(() -> invalid.initializer.initialize(identity, primary))
                .isInstanceOfSatisfying(
                    RedisBootstrapInitializationException.class,
                    exception -> assertThat(exception.reason()).isEqualTo(
                        RedisBootstrapInitializationException.Reason
                            .INVALID_SCRIPT_RESPONSE
                    )
                );
            verify(invalid.connection).close();
        }
    }

    @Test
    void validatesTheWholeTrustedRequestBeforeOpeningAConnection() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisControlEnvelopeCodec controlCodec = new RedisControlEnvelopeCodec();
        RedisCoordinatorLeaseCodec leaseCodec = new RedisCoordinatorLeaseCodec();

        assertThatThrownBy(() -> new StrictRedisBootstrapInitializer(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("connectionFactory");
        assertThatThrownBy(() -> new StrictRedisBootstrapInitializer(
            factory,
            null,
            leaseCodec,
            () -> STORAGE_UUID
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("controlCodec");
        assertThatThrownBy(() -> new StrictRedisBootstrapInitializer(
            factory,
            controlCodec,
            null,
            () -> STORAGE_UUID
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("leaseCodec");
        assertThatThrownBy(() -> new StrictRedisBootstrapInitializer(
            factory,
            controlCodec,
            leaseCodec,
            null
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("storageUuidSupplier");

        StrictRedisBootstrapInitializer initializer = new StrictRedisBootstrapInitializer(
            factory,
            controlCodec,
            leaseCodec,
            () -> STORAGE_UUID
        );
        SecurityDatasetIdentity identity = identity("tenant-a");
        assertThatThrownBy(() -> initializer.initialize(null, primary(identity)))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("identity");
        assertThatThrownBy(() -> initializer.initialize(identity, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("expectedPrimary");
        assertThatThrownBy(() -> initializer.initialize(
            identity,
            new RedisPrimarySnapshot(
                new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
                null
            )
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bound");
        assertThatThrownBy(() -> initializer.initialize(
            identity,
            primary(identity("tenant-b"))
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keyspace");
        assertThatThrownBy(() -> initializer.initialize(
            new SecurityDatasetIdentity("tenant-a", 2),
            primary(identity)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE);

        StrictRedisBootstrapInitializer invalidUuid = new StrictRedisBootstrapInitializer(
            factory,
            controlCodec,
            leaseCodec,
            () -> UUID.fromString("aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa")
        );
        assertThatThrownBy(() -> invalidUuid.initialize(identity, primary(identity)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("storageUuid");
        verifyNoInteractions(factory);
    }

    @Test
    void treatsTransportAndMissingResponsesAsAmbiguousAndPreservesCloseFailures() {
        RedisConnectionFactory acquisitionFailure = mock(RedisConnectionFactory.class);
        IllegalStateException offline = new IllegalStateException("offline");
        when(acquisitionFailure.getConnection()).thenThrow(offline);
        assertThatThrownBy(() -> new StrictRedisBootstrapInitializer(acquisitionFailure)
            .initialize(identity("tenant-a"), primary(identity("tenant-a"))))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasCause(offline);

        RedisConnectionFactory nullFactory = mock(RedisConnectionFactory.class);
        when(nullFactory.getConnection()).thenReturn(null);
        assertThatThrownBy(() -> new StrictRedisBootstrapInitializer(nullFactory)
            .initialize(identity("tenant-a"), primary(identity("tenant-a"))))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("no connection");

        Fixture missingCommands = fixture(ascii("INITIALIZED"));
        when(missingCommands.connection.scriptingCommands()).thenReturn(null);
        assertThatThrownBy(() -> missingCommands.initializer.initialize(
            identity("tenant-a"),
            primary(identity("tenant-a"))
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("no scripting commands");
        verify(missingCommands.connection).close();

        Fixture evalFailure = fixture(ascii("INITIALIZED"));
        IllegalStateException denied = new IllegalStateException("EVAL denied");
        when(evalFailure.commands.eval(
            any(byte[].class),
            eq(ReturnType.VALUE),
            eq(3),
            any(byte[][].class)
        )).thenThrow(denied);
        assertThatThrownBy(() -> evalFailure.initializer.initialize(
            identity("tenant-a"),
            primary(identity("tenant-a"))
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("ambiguous")
            .hasCause(denied);
        verify(evalFailure.connection).close();

        Fixture nullResponse = fixture(null);
        assertThatThrownBy(() -> nullResponse.initializer.initialize(
            identity("tenant-a"),
            primary(identity("tenant-a"))
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("ambiguous");
        verify(nullResponse.connection).close();

        Fixture typedFailure = fixture(ascii("PARTIAL_METADATA"));
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        doThrow(closeFailure).when(typedFailure.connection).close();
        assertThatThrownBy(() -> typedFailure.initializer.initialize(
            identity("tenant-a"),
            primary(identity("tenant-a"))
        )).isInstanceOfSatisfying(
            RedisBootstrapInitializationException.class,
            failure -> {
                assertThat(failure.reason()).isEqualTo(
                    RedisBootstrapInitializationException.Reason.PARTIAL_METADATA
                );
                assertThat(failure.getSuppressed()).hasSize(1);
                assertThat(failure.getSuppressed()[0])
                    .isInstanceOf(RedisProtocolUnavailableException.class)
                    .hasCause(closeFailure);
            }
        );

        Fixture successfulCloseFailure = fixture(ascii("INITIALIZED"));
        doThrow(closeFailure).when(successfulCloseFailure.connection).close();
        assertThatThrownBy(() -> successfulCloseFailure.initializer.initialize(
            identity("tenant-a"),
            primary(identity("tenant-a"))
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("close")
            .hasCause(closeFailure);
    }

    private static void assertScriptContract(String script) {
        int presenceGuard = script.indexOf(
            "if present > 0 and present < 3 then return 'PARTIAL_METADATA' end"
        );
        int counterWrite = script.indexOf("redis.pcall('SETNX', KEYS[3]");
        int leaseWrite = script.indexOf("local leaseWrite = writeHash(KEYS[2]");
        int controlWrite = script.indexOf("local controlWrite = writeHash(KEYS[1]");

        assertThat(script)
            .startsWith("redis.replicate_commands()\n")
            .contains("local MAX_INFO = 65536")
            .contains("if #KEYS ~= 3 or #ARGV ~= 22")
            .contains("redis.call('INFO', 'server')")
            .contains("redis.call('INFO', 'replication')")
            .contains("redis.call('INFO', 'cluster')")
            .contains("redis.call('INFO', 'memory')")
            .contains("infoValue(replication, 'connected_slaves')")
            .contains("connectedReplicas ~= '0'")
            .contains("maxmemoryPolicy ~= 'noeviction'")
            .contains("redis.call('TYPE', key)")
            .contains("redis.call('PTTL', KEYS[1])")
            .contains("redis.call('PTTL', KEYS[2])")
            .contains("redis.call('PTTL', KEYS[3])")
            .contains("redis.call('HLEN', key) ~= #names")
            .contains("redis.call('HEXISTS', key, name)")
            .contains("redis.call('HSTRLEN', key, name)")
            .contains("counterLength == 0 or counterLength > 16")
            .contains("redis.pcall('SETNX', KEYS[3]")
            .contains("counterWrite ~= 1")
            .contains("redis.pcall('HSET', key, unpack(values))")
            .contains("redis.pcall('DEL', KEYS[index])")
            .contains("redis.pcall('TYPE', KEYS[index])")
            .contains("return 'WRITE_FAILED_ROLLED_BACK'")
            .contains("return 'ROLLBACK_FAILED'")
            .doesNotContain("HGETALL", "WAIT", "SENTINEL", "capacity", "signature");
        assertThat(presenceGuard).isPositive().isLessThan(counterWrite);
        assertThat(counterWrite).isPositive().isLessThan(leaseWrite);
        assertThat(leaseWrite).isLessThan(controlWrite);
    }

    private static Map<String, String> fields(
        byte[][] elements,
        int offset,
        java.util.List<String> names
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            fields.put(names.get(index), utf8(elements[offset + index]));
        }
        return fields;
    }

    private static Fixture fixture(Object response) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisScriptingCommands commands = mock(RedisScriptingCommands.class);
        AtomicReference<byte[][]> elements = new AtomicReference<>();
        when(factory.getConnection()).thenReturn(connection);
        when(connection.scriptingCommands()).thenReturn(commands);
        when(commands.eval(
            any(byte[].class),
            eq(ReturnType.VALUE),
            eq(3),
            any(byte[][].class)
        )).thenAnswer(invocation -> {
            Object[] invocationArguments = invocation.getArguments();
            byte[][] supplied = new byte[invocationArguments.length - 3][];
            for (int index = 3; index < invocationArguments.length; index++) {
                supplied[index - 3] = ((byte[]) invocationArguments[index]).clone();
            }
            elements.set(supplied);
            return response;
        });
        StrictRedisBootstrapInitializer initializer = new StrictRedisBootstrapInitializer(
            factory,
            new RedisControlEnvelopeCodec(),
            new RedisCoordinatorLeaseCodec(),
            () -> STORAGE_UUID
        );
        return new Fixture(factory, connection, commands, elements, initializer);
    }

    private static SecurityDatasetIdentity identity(String datasetId) {
        return new SecurityDatasetIdentity(datasetId, 1);
    }

    private static RedisPrimarySnapshot primary(SecurityDatasetIdentity identity) {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
            identity.getSecurityDatasetId()
        );
        return new RedisPrimarySnapshot(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            null
        ).bindRequestedKeyspace(keyspace);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String utf8(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private record Fixture(
        RedisConnectionFactory factory,
        RedisConnection connection,
        RedisScriptingCommands commands,
        AtomicReference<byte[][]> commandElements,
        StrictRedisBootstrapInitializer initializer
    ) {
    }
}
