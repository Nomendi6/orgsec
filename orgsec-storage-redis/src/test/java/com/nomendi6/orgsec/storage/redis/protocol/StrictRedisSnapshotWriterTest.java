package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrictRedisSnapshotWriterTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_RUN_ID =
        "89abcdef0123456789abcdef0123456789abcdef";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void stagesArbitraryBatchOrderRetriesExactlyAndWritesOneFrozenManifest() {
        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        RedisDatasetKeyspace keyspace = fixture.keyspace();
        UUID snapshotId = session.snapshotId();
        List<String> controlBefore = fixture.redis.controlValuesSnapshot();

        List<RedisCanonicalEntry> highBatch = List.of(entry("c", "pc"), entry("d", "pd"));
        List<RedisCanonicalEntry> lowBatch = List.of(entry("a", "pa"), entry("b", "pb"));
        session.append(RedisSnapshotFamily.PERSONS, highBatch);
        session.append(RedisSnapshotFamily.PERSONS, lowBatch);
        session.append(RedisSnapshotFamily.PERSONS, lowBatch);
        completeAll(session);

        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries =
            new EnumMap<>(RedisSnapshotFamily.class);
        entries.put(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "pa"), entry("b", "pb"), entry("c", "pc"),
                entry("d", "pd"))
        );
        RedisSnapshotContentDigest content = content(keyspace, snapshotId, entries);
        RedisSnapshotManifest.Verified first = session.sealAndWriteManifest(content);
        RedisSnapshotManifest.Verified retry = session.sealAndWriteManifest(content);

        assertThat(retry).isSameAs(first);
        assertThat(first.snapshotId()).isEqualTo(snapshotId);
        assertThat(fixture.redis.hashSize(keyspace.familyKey(
            snapshotId,
            RedisSnapshotFamily.PERSONS
        ))).isEqualTo(4);
        assertThat(fixture.redis.indexSize(keyspace.familyIndexKey(
            snapshotId,
            RedisSnapshotFamily.PERSONS
        ))).isEqualTo(4);
        assertThat(fixture.redis.hashSize(keyspace.manifestKey(snapshotId)))
            .isEqualTo(RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT);
        assertThat(fixture.redis.controlValuesSnapshot()).isEqualTo(controlBefore);
        assertThat(fixture.redis.invocationCount).isEqualTo(5);

        String appendScript = fixture.redis.appendScripts().get(0);
        String manifestScript = fixture.redis.manifestScripts().get(0);
        assertGuardAndNoControlWrite(appendScript);
        assertGuardAndNoControlWrite(manifestScript);
        assertThat(appendScript)
            .contains("local entryCount = tonumber(ARGV[" +
                appendEntryCountLuaIndex() + "])")
            .contains("local entryKey = ARGV[" + appendFirstKeyLuaIndex() +
                " + (entryIndex * 2)]")
            .contains("local payload = ARGV[" + appendFirstPayloadLuaIndex() +
                " + (entryIndex * 2)]")
            .contains("redis.call('HEXISTS', KEYS[3], entryKey)")
            .contains("redis.call('HSTRLEN', KEYS[3], entryKey)")
            .contains("redis.call('HGET', KEYS[3], entryKey)")
            .contains("redis.pcall('HSET', KEYS[3]")
            .contains("redis.pcall('ZADD', KEYS[4], 'NX', 0, entryKey)")
            .contains("rollbackAddedKeys", "redis.pcall('HDEL'", "redis.pcall('ZREM'");
        assertThat(appendScript.indexOf("redis.call('HSTRLEN', KEYS[3], entryKey)"))
            .isLessThan(appendScript.indexOf("redis.call('HGET', KEYS[3], entryKey)"));
        assertThat(appendScript.indexOf("return 'ENTRY_SCORE_NOT_ZERO'"))
            .isLessThan(appendScript.indexOf("redis.pcall('HSET', KEYS[3]"));
        assertThat(manifestScript)
            .contains("if #ARGV ~= " + manifestArgumentCount())
            .contains("local familyCountArguments = " + familyCountLuaIndexes())
            .contains("redis.call('HLEN', KEYS[2]) ~= " +
                RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT)
            .contains("manifestWrite ~= " +
                RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT)
            .contains("redis.call('HLEN', KEYS[dataKeyIndex])")
            .contains("redis.call('ZCARD', KEYS[dataKeyIndex + 1])")
            .contains("local manifestFieldLimits =")
            .contains("redis.call('HSTRLEN', KEYS[2], fieldName)")
            .contains("redis.call('HGET', KEYS[2], fieldName)")
            .contains("redis.pcall('HSET', KEYS[2]");
        assertThat(manifestScript.indexOf("redis.call('HSTRLEN', KEYS[2], fieldName)"))
            .isLessThan(manifestScript.indexOf("redis.call('HGET', KEYS[2], fieldName)"));

        String expectedHashTag = keyspace.hashTag();
        assertThat(fixture.redis.observedKeys)
            .allSatisfy(key -> assertThat(key).contains(expectedHashTag));
        assertThat(Arrays.stream(StrictRedisSnapshotWriter.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("begin"))
            .map(Method::getParameterTypes)
            .flatMap(Arrays::stream))
            .doesNotContain(RedisDatasetKeyspace.class);

        verify(fixture.connectionFactory).getConnection();
        verify(fixture.connection, never()).close();
        session.close();
        verify(fixture.connection).close();
    }

    @Test
    void prevalidatesEveryConfiguredBatchBoundBeforeEvaluation() {
        Fixture fixture = fixture(limits(2, 2, 3, 5));
        RedisStagingSession session = fixture.open();

        assertThatThrownBy(() -> session.append(RedisSnapshotFamily.PERSONS, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "p"), entry("b", "p"), entry("c", "p"))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entry count");
        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("abc", "p"))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("key");
        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "1234"))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");
        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "12"), entry("b", "34"))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("batch bytes");
        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("b", "p"), entry("a", "p"))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("strictly increasing");

        assertThat(fixture.redis.invocationCount).isZero();
        session.abort();
        verify(fixture.connection).close();
        assertThatThrownBy(() -> session.complete(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POISONED");
    }

    @Test
    void canonicalEntryVerificationRejectsKeyPayloadAndIdentityBeforeEvaluation() {
        Fixture fixture = fixture(
            limits(8, 32, 512, 2048),
            new RedisCanonicalSnapshotEntryVerifier()::verify
        );
        RedisStagingSession session = fixture.open();

        assertEntryFailure(
            () -> session.append(
                RedisSnapshotFamily.PERSONS,
                List.of(personEntry("01", 1))
            ),
            RedisSnapshotEntryCorruptionException.Reason.KEY_INVALID
        );
        assertEntryFailure(
            () -> session.append(
                RedisSnapshotFamily.PERSONS,
                List.of(new RedisCanonicalEntry(ascii("1"), ascii("{}")))
            ),
            RedisSnapshotEntryCorruptionException.Reason.PAYLOAD_INVALID
        );
        assertEntryFailure(
            () -> session.append(
                RedisSnapshotFamily.PERSONS,
                List.of(personEntry("2", 1))
            ),
            RedisSnapshotEntryCorruptionException.Reason.KEY_PAYLOAD_IDENTITY_MISMATCH
        );
        assertThat(fixture.redis.invocationCount).isZero();

        session.append(RedisSnapshotFamily.PERSONS, List.of(personEntry("1", 1)));
        assertThat(fixture.redis.invocationCount).isOne();
        session.abort();
    }

    @Test
    void corruptionPreflightRejectsHalfPairDifferentPayloadAndNonzeroScoreWithoutMutation() {
        assertCorruptExistingEntry(FakeCorruption.HALF_PAIR,
            RedisStagingCorruptionException.Reason.ENTRY_HALF_PAIR);
        assertCorruptExistingEntry(FakeCorruption.DIFFERENT_PAYLOAD,
            RedisStagingCorruptionException.Reason.ENTRY_PAYLOAD_MISMATCH);
        assertCorruptExistingEntry(FakeCorruption.NONZERO_SCORE,
            RedisStagingCorruptionException.Reason.ENTRY_SCORE_NOT_ZERO);
    }

    @Test
    void writeFailureCompensatesTheEntireCurrentBatchAndPoisonsTheSession() {
        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        RedisDatasetKeyspace keyspace = fixture.keyspace();
        UUID snapshotId = session.snapshotId();
        fixture.redis.failWriteAt = 2;

        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "pa"), entry("b", "pb"), entry("c", "pc"))
        )).isInstanceOf(RedisStagingWriteException.class)
            .hasMessageContaining(RedisStagingWriteException.DIAGNOSTIC_CODE);

        assertThat(fixture.redis.hashSize(keyspace.familyKey(
            snapshotId,
            RedisSnapshotFamily.PERSONS
        ))).isZero();
        assertThat(fixture.redis.indexSize(keyspace.familyIndexKey(
            snapshotId,
            RedisSnapshotFamily.PERSONS
        ))).isZero();
        verify(fixture.connection).close();
        assertThatThrownBy(() -> session.complete(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POISONED");
    }

    @Test
    void rollbackFailureIsTypedCorruptionAndCannotSeal() {
        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        fixture.redis.failWriteAt = 2;
        fixture.redis.failRollback = true;

        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "pa"), entry("b", "pb"))
        )).isInstanceOfSatisfying(
            RedisStagingCorruptionException.class,
            failure -> assertThat(failure.reason())
                .isEqualTo(RedisStagingCorruptionException.Reason.ROLLBACK_FAILED)
        );
        assertThatThrownBy(() -> session.sealAndWriteManifest(emptyContent(
            fixture.keyspace(),
            session.snapshotId()
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POISONED");
    }

    @Test
    void fiveCompletedFamiliesCannotCreateAManifest() {
        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        for (RedisSnapshotFamily family : List.of(
            RedisSnapshotFamily.PERSONS,
            RedisSnapshotFamily.ORGANIZATIONS,
            RedisSnapshotFamily.PARTY_ROLES,
            RedisSnapshotFamily.POSITION_ROLES,
            RedisSnapshotFamily.ROLES
        )) {
            session.complete(family);
        }

        assertThatThrownBy(() -> session.sealAndWriteManifest(emptyContent(
            fixture.keyspace(),
            session.snapshotId()
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("all six");
        assertThat(fixture.redis.manifestCount()).isZero();
        assertThat(fixture.redis.invocationCount).isZero();
        session.abort();
    }

    @Test
    void extraFamilyEntryBlocksManifestAndPoisonsSession() {
        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        RedisDatasetKeyspace keyspace = fixture.keyspace();
        UUID snapshotId = session.snapshotId();
        RedisCanonicalEntry expected = entry("a", "pa");
        session.append(RedisSnapshotFamily.PERSONS, List.of(expected));
        fixture.redis.putPair(
            keyspace.familyKey(snapshotId, RedisSnapshotFamily.PERSONS),
            keyspace.familyIndexKey(snapshotId, RedisSnapshotFamily.PERSONS),
            entry("x", "external"),
            0
        );
        completeAll(session);

        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> entries =
            new EnumMap<>(RedisSnapshotFamily.class);
        entries.put(RedisSnapshotFamily.PERSONS, List.of(expected));
        assertThatThrownBy(() -> session.sealAndWriteManifest(content(
            keyspace,
            snapshotId,
            entries
        ))).isInstanceOfSatisfying(
            RedisStagingCorruptionException.class,
            failure -> assertThat(failure.reason())
                .isEqualTo(RedisStagingCorruptionException.Reason.FAMILY_COUNT_MISMATCH)
        );
        assertThat(fixture.redis.hashSize(keyspace.manifestKey(snapshotId))).isZero();
        verify(fixture.connection).close();
    }

    @Test
    void sealedManifestFreezesEveryAppendAndDifferentRetry() {
        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        completeAll(session);
        RedisSnapshotContentDigest content = emptyContent(
            fixture.keyspace(),
            session.snapshotId()
        );
        session.sealAndWriteManifest(content);
        int callsAfterSeal = fixture.redis.invocationCount;

        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "pa"))
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SEALED");
        assertThatThrownBy(() -> session.complete(RedisSnapshotFamily.PERSONS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SEALED");
        Map<RedisSnapshotFamily, List<RedisCanonicalEntry>> different =
            new EnumMap<>(RedisSnapshotFamily.class);
        different.put(RedisSnapshotFamily.PERSONS, List.of(entry("a", "pa")));
        assertThatThrownBy(() -> session.sealAndWriteManifest(content(
            fixture.keyspace(),
            session.snapshotId(),
            different
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exact same content");
        assertThat(fixture.redis.invocationCount).isEqualTo(callsAfterSeal);
        session.close();
    }

    @Test
    void generationChangeAndTransportFailurePoisonAndClose() {
        Fixture stale = fixture(limits(8, 32, 64, 256));
        RedisStagingSession staleSession = stale.open();
        stale.redis.currentRunId = ascii(OTHER_RUN_ID);
        assertThatThrownBy(() -> staleSession.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "pa"))
        )).isInstanceOfSatisfying(
            RedisStagingFenceException.class,
            failure -> assertThat(failure.reason())
                .isEqualTo(RedisStagingFenceException.Reason.GENERATION_CHANGED)
        );
        verify(stale.connection).close();

        Fixture unavailable = fixture(limits(8, 32, 64, 256));
        RedisStagingSession unavailableSession = unavailable.open();
        RuntimeException network = new RuntimeException("connection reset");
        unavailable.redis.nextFailure = network;
        assertThatThrownBy(() -> unavailableSession.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "pa"))
        )).isInstanceOf(RedisProtocolUnavailableException.class)
            .hasCause(network);
        verify(unavailable.connection).close();
        assertThatThrownBy(() -> unavailableSession.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("b", "pb"))
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POISONED");
    }

    @Test
    void leaseMismatchFailsClosedBeforeAnyStagingWrite() {
        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        fixture.redis.tamperLease();

        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "pa"))
        )).isInstanceOfSatisfying(
            RedisStagingFenceException.class,
            failure -> assertThat(failure.reason())
                .isEqualTo(RedisStagingFenceException.Reason.LEASE_CHANGED)
        );
        verify(fixture.connection).close();
    }

    @Test
    void rejectsInconsistentExpectationBeforeAcquiringAConnection() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        SecurityDatasetFence sourceFence = fence("tenant-a");
        RedisControlEnvelope wrongRunControl = control(sourceFence.getIdentity(), OTHER_RUN_ID);
        RedisPrimarySnapshot inconsistent = new RedisPrimarySnapshot(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            wrongRunControl
        );
        StrictRedisSnapshotWriter writer = new StrictRedisSnapshotWriter(
            factory,
            limits(8, 32, 64, 256)
        );

        assertThatThrownBy(() -> writer.begin(inconsistent, sourceFence, activeLease(sourceFence)))
            .isInstanceOfSatisfying(
                RedisStagingFenceException.class,
                failure -> assertThat(failure.reason())
                    .isEqualTo(RedisStagingFenceException.Reason.EXPECTATION_INCONSISTENT)
            );
        verifyNoInteractions(factory);

        RedisPrimarySnapshot absentControl = new RedisPrimarySnapshot(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            null
        );
        assertThatThrownBy(() -> writer.begin(absentControl, sourceFence, activeLease(sourceFence)))
            .isInstanceOf(RedisStagingFenceException.class);
        verifyNoInteractions(factory);
    }

    @Test
    void rejectsFutureSourceProtocolBeforeAcquiringAConnection() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        SecurityDatasetFence acceptedFence = fence("tenant-a");
        SecurityDatasetFence futureFence = new SecurityDatasetFence(
            new SecurityDatasetIdentity("sensitive-dataset", 2),
            4
        );
        StrictRedisSnapshotWriter writer = new StrictRedisSnapshotWriter(
            factory,
            limits(8, 32, 64, 256)
        );

        assertThatThrownBy(() -> writer.begin(
            primary(acceptedFence),
            futureFence,
            activeLease(acceptedFence)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
        verifyNoInteractions(factory);
    }

    @Test
    void validatesLimitsConnectionAcquisitionAndOwnerThread() throws InterruptedException {
        assertThatThrownBy(() -> limits(0, 1, 1, 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxEntries");
        assertThatThrownBy(() -> limits(1, 0, 1, 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxKeyBytes");
        assertThatThrownBy(() -> limits(1, 1, 0, 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxPayloadBytes");
        assertThatThrownBy(() -> limits(1, 1, 1, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxBatchBytes");

        RedisConnectionFactory failingFactory = mock(RedisConnectionFactory.class);
        RuntimeException cause = new RuntimeException("offline");
        when(failingFactory.getConnection()).thenThrow(cause);
        StrictRedisSnapshotWriter failing = new StrictRedisSnapshotWriter(
            failingFactory,
            limits(1, 1, 1, 1)
        );
        assertThatThrownBy(() -> failing.begin(
            primary(fence("tenant-a")),
            fence("tenant-a"),
            activeLease(fence("tenant-a"))
        ))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasCause(cause);

        RedisConnectionFactory nullFactory = mock(RedisConnectionFactory.class);
        StrictRedisSnapshotWriter nullWriter = new StrictRedisSnapshotWriter(
            nullFactory,
            limits(1, 1, 1, 1)
        );
        assertThatThrownBy(() -> nullWriter.begin(
            primary(fence("tenant-a")),
            fence("tenant-a"),
            activeLease(fence("tenant-a"))
        ))
            .isInstanceOf(RedisProtocolUnavailableException.class)
            .hasMessageContaining("no staging-session connection");

        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        AtomicReference<Throwable> crossThreadFailure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                session.snapshotId();
            } catch (Throwable failure) {
                crossThreadFailure.set(failure);
            }
        });
        thread.start();
        thread.join();
        assertThat(crossThreadFailure.get())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owner thread");
        session.abort();
    }

    @Test
    void productionEvaluatorUsesOneEvalValueCallAndRejectsMissingCommands() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisScriptingCommands commands = mock(RedisScriptingCommands.class);
        when(connection.scriptingCommands()).thenReturn(commands);
        byte[] expected = ascii("OK");
        when(commands.eval(
            any(byte[].class),
            eq(ReturnType.VALUE),
            eq(1),
            any(byte[].class),
            any(byte[].class)
        )).thenReturn(expected);

        assertThat(RedisStagingSession.evaluateOnConnection(
            connection,
            ascii("return 'OK'"),
            new byte[][] {ascii("{slot}:key")},
            new byte[][] {ascii("argument")}
        )).isSameAs(expected);
        verify(commands).eval(
            any(byte[].class),
            eq(ReturnType.VALUE),
            eq(1),
            any(byte[].class),
            any(byte[].class)
        );

        RedisConnection missing = mock(RedisConnection.class);
        assertThatThrownBy(() -> RedisStagingSession.evaluateOnConnection(
            missing,
            ascii("return 'OK'"),
            new byte[][] {ascii("{slot}:key")},
            new byte[0][]
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no scripting commands");
    }

    private static void assertCorruptExistingEntry(
        FakeCorruption corruption,
        RedisStagingCorruptionException.Reason expectedReason
    ) {
        Fixture fixture = fixture(limits(8, 32, 64, 256));
        RedisStagingSession session = fixture.open();
        RedisDatasetKeyspace keyspace = fixture.keyspace();
        UUID snapshotId = session.snapshotId();
        String dataKey = keyspace.familyKey(snapshotId, RedisSnapshotFamily.PERSONS);
        String indexKey = keyspace.familyIndexKey(snapshotId, RedisSnapshotFamily.PERSONS);
        RedisCanonicalEntry existing = entry("b", "old");
        switch (corruption) {
            case HALF_PAIR -> fixture.redis.putHashOnly(dataKey, existing);
            case DIFFERENT_PAYLOAD -> fixture.redis.putPair(dataKey, indexKey, existing, 0);
            case NONZERO_SCORE -> fixture.redis.putPair(
                dataKey,
                indexKey,
                entry("b", "new"),
                1
            );
        }

        assertThatThrownBy(() -> session.append(
            RedisSnapshotFamily.PERSONS,
            List.of(entry("a", "new-a"), entry("b", "new"))
        )).isInstanceOfSatisfying(
            RedisStagingCorruptionException.class,
            failure -> assertThat(failure.reason()).isEqualTo(expectedReason)
        );
        assertThat(fixture.redis.hasHashField(dataKey, ascii("a"))).isFalse();
        assertThat(fixture.redis.hasIndexMember(indexKey, ascii("a"))).isFalse();
        verify(fixture.connection).close();
    }

    private static void assertGuardAndNoControlWrite(String script) {
        assertThat(script)
            .contains("redis.call('INFO', 'server')")
            .contains("redis.call('INFO', 'replication')")
            .contains("redis.call('INFO', 'cluster')")
            .contains("redis.call('INFO', 'memory')")
            .contains("#serverInfo > 16384", "currentRole ~= 'master'")
            .contains("clusterEnabled ~= '0'", "maxmemoryPolicy ~= 'noeviction'")
            .contains("redis.call('HLEN', KEYS[1]) ~= " +
                RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT)
            .contains("local controlFieldLimits =")
            .contains("redis.call('HSTRLEN', KEYS[1], fieldName)")
            .contains("redis.call('HGET', KEYS[1], fieldName)")
            .contains("local leaseKey = KEYS[#KEYS]")
            .contains("return 'LEASE_CHANGED'")
            .contains("redis.call('TIME')")
            .doesNotContain("redis.call('HSET', KEYS[1]")
            .doesNotContain("redis.pcall('HSET', KEYS[1]")
            .doesNotContain("redis.call('DEL', KEYS[1]")
            .doesNotContain("redis.pcall('DEL', KEYS[1]")
            .doesNotContain("READY", "UPDATING");
        assertThat(script.indexOf("redis.call('HSTRLEN', KEYS[1], fieldName)"))
            .isLessThan(script.indexOf("redis.call('HGET', KEYS[1], fieldName)"));
    }

    private static int generationArgumentCount() {
        return 1
            + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
            + RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT;
    }

    private static int appendEntryCountLuaIndex() {
        return generationArgumentCount() + 1;
    }

    private static int appendFirstKeyLuaIndex() {
        return appendEntryCountLuaIndex() + 1;
    }

    private static int appendFirstPayloadLuaIndex() {
        return appendFirstKeyLuaIndex() + 1;
    }

    private static int manifestArgumentCount() {
        return generationArgumentCount() + RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT;
    }

    private static List<String> manifestCountFields() {
        return List.of(
            RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT,
            RedisSnapshotManifestCodec.FIELD_ORGANIZATIONS_COUNT,
            RedisSnapshotManifestCodec.FIELD_PARTY_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_POSITION_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_PRIVILEGES_COUNT
        );
    }

    private static String familyCountLuaIndexes() {
        StringJoiner indexes = new StringJoiner(", ", "{", "}");
        List<String> manifestFields = RedisSnapshotManifestCodec.orderedFields();
        for (String field : manifestCountFields()) {
            indexes.add(Integer.toString(
                generationArgumentCount() + manifestFields.indexOf(field) + 1
            ));
        }
        return indexes.toString();
    }

    private static Fixture fixture(RedisSnapshotWriteLimits limits) {
        return fixture(limits, (family, entry) -> { });
    }

    private static Fixture fixture(
        RedisSnapshotWriteLimits limits,
        RedisStagingSession.EntryVerifier entryVerifier
    ) {
        SecurityDatasetFence fence = fence("tenant-a");
        RedisControlEnvelope control = control(fence.getIdentity(), RUN_ID);
        RedisPrimarySnapshot primary = new RedisPrimarySnapshot(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            control
        );
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        RedisCoordinatorLease.Verified lease = activeLease(fence);
        FakeRedis redis = new FakeRedis(RUN_ID, control, lease);
        StrictRedisSnapshotWriter writer = new StrictRedisSnapshotWriter(
            connectionFactory,
            limits,
            redis::eval,
            new RedisControlEnvelopeCodec(),
            new RedisSnapshotManifestCodec(),
            entryVerifier
        );
        return new Fixture(
            fence,
            primary,
            lease,
            connectionFactory,
            connection,
            redis,
            writer
        );
    }

    private static SecurityDatasetFence fence(String datasetId) {
        return new SecurityDatasetFence(new SecurityDatasetIdentity(datasetId, 1), 4);
    }

    private static RedisControlEnvelope control(
        SecurityDatasetIdentity identity,
        String runId
    ) {
        return new RedisControlEnvelope(
            identity,
            new RedisIncarnation(runId, STORAGE_UUID),
            5,
            null,
            RedisControlState.INITIALIZING
        );
    }

    private static final UUID OWNER_SESSION_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID ACQUISITION_ID =
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final long LEASE_ISSUED_AT = 1_700_000_000_000L;
    private static final long LEASE_EXPIRES_AT = LEASE_ISSUED_AT + 60_000L;

    private static RedisCoordinatorLease.Verified activeLease(SecurityDatasetFence fence) {
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
            fence.getIdentity().getSecurityDatasetId()
        );
        RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
            fence.getIdentity(),
            keyspace.datasetHash(),
            new RedisIncarnation(RUN_ID, STORAGE_UUID),
            5,
            RedisCoordinatorLease.State.ACTIVE,
            1,
            1,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            LEASE_ISSUED_AT,
            LEASE_EXPIRES_AT
        );
        return RedisCoordinatorLease.verifyActiveForContext(
            candidate,
            fence.getIdentity(),
            keyspace.datasetHash(),
            new RedisIncarnation(RUN_ID, STORAGE_UUID),
            5,
            1,
            1,
            OWNER_SESSION_ID,
            ACQUISITION_ID,
            LEASE_ISSUED_AT,
            LEASE_EXPIRES_AT,
            LEASE_ISSUED_AT
        );
    }

    private static RedisPrimarySnapshot primary(SecurityDatasetFence fence) {
        return new RedisPrimarySnapshot(
            new RedisPrimaryObservation(RUN_ID, "master", false, "noeviction"),
            control(fence.getIdentity(), RUN_ID)
        );
    }

    private static RedisSnapshotWriteLimits limits(
        int entries,
        int keyBytes,
        int payloadBytes,
        long batchBytes
    ) {
        return new RedisSnapshotWriteLimits(entries, keyBytes, payloadBytes, batchBytes);
    }

    private static RedisCanonicalEntry entry(String key, String payload) {
        return new RedisCanonicalEntry(ascii(key), ascii(payload));
    }

    private static RedisCanonicalEntry personEntry(String key, long personId) {
        PersonDef person = new PersonDef(personId, null);
        return new RedisCanonicalEntry(
            ascii(key),
            new RedisCanonicalSnapshotPayloadCodec().encode(person)
        );
    }

    private static void assertEntryFailure(
        Runnable operation,
        RedisSnapshotEntryCorruptionException.Reason reason
    ) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(
            RedisSnapshotEntryCorruptionException.class,
            failure -> assertThat(failure.reason()).isEqualTo(reason)
        );
    }

    private static void completeAll(RedisStagingSession session) {
        session.complete(RedisSnapshotFamily.PRIVILEGES);
        session.complete(RedisSnapshotFamily.ROLES);
        session.complete(RedisSnapshotFamily.PARTY_ROLES);
        session.complete(RedisSnapshotFamily.PERSONS);
        session.complete(RedisSnapshotFamily.POSITION_ROLES);
        session.complete(RedisSnapshotFamily.ORGANIZATIONS);
    }

    private static RedisSnapshotContentDigest emptyContent(
        RedisDatasetKeyspace keyspace,
        UUID snapshotId
    ) {
        return content(keyspace, snapshotId, Collections.emptyMap());
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
                entries.getOrDefault(family, List.of())
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

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private enum FakeCorruption {
        HALF_PAIR,
        DIFFERENT_PAYLOAD,
        NONZERO_SCORE
    }

    private record Fixture(
        SecurityDatasetFence fence,
        RedisPrimarySnapshot primary,
        RedisCoordinatorLease.Verified lease,
        RedisConnectionFactory connectionFactory,
        RedisConnection connection,
        FakeRedis redis,
        StrictRedisSnapshotWriter writer
    ) {
        RedisStagingSession open() {
            return writer.begin(primary, fence, lease);
        }

        RedisDatasetKeyspace keyspace() {
            return new RedisDatasetKeyspace(
                fence.getIdentity().getSecurityDatasetId()
            );
        }
    }

    /** Deterministic unit model of the two Lua contracts; it never interprets payload text. */
    private static final class FakeRedis {

        private final List<byte[]> expectedControlValues;
        private final List<byte[]> expectedLeaseValues;
        private final Map<String, Map<Bytes, byte[]>> hashes = new HashMap<>();
        private final Map<String, Map<Bytes, Double>> indexes = new HashMap<>();
        private final List<String> scripts = new ArrayList<>();
        private final List<String> observedKeys = new ArrayList<>();

        private byte[] currentRunId;
        private RuntimeException nextFailure;
        private int failWriteAt = -1;
        private boolean failRollback;
        private int invocationCount;

        private FakeRedis(
            String runId,
            RedisControlEnvelope control,
            RedisCoordinatorLease.Verified lease
        ) {
            this.currentRunId = ascii(runId);
            Map<String, String> fields = new RedisControlEnvelopeCodec().encode(control);
            this.expectedControlValues = new ArrayList<>(
                RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
            );
            for (String field : RedisControlEnvelopeCodec.orderedFields()) {
                expectedControlValues.add(fields.get(field).getBytes(StandardCharsets.UTF_8));
            }
            Map<String, String> leaseFields = new RedisCoordinatorLeaseCodec().encode(lease);
            this.expectedLeaseValues = new ArrayList<>(
                RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT
            );
            for (String field : RedisCoordinatorLeaseCodec.orderedFields()) {
                expectedLeaseValues.add(leaseFields.get(field).getBytes(StandardCharsets.UTF_8));
            }
        }

        Object eval(
            RedisConnection ignored,
            byte[] rawScript,
            byte[][] rawKeys,
            byte[][] arguments
        ) {
            invocationCount++;
            String script = new String(rawScript, StandardCharsets.UTF_8);
            scripts.add(script);
            String[] keys = Arrays.stream(rawKeys)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .toArray(String[]::new);
            observedKeys.addAll(List.of(keys));
            if (nextFailure != null) {
                RuntimeException failure = nextFailure;
                nextFailure = null;
                throw failure;
            }
            if (!generationMatches(arguments)) {
                return ascii("CONTROL_CHANGED");
            }
            if (!leaseMatches(arguments)) {
                return ascii("LEASE_CHANGED");
            }
            if (keys.length == 5) {
                return append(keys, arguments);
            }
            if (keys.length == 15) {
                return manifest(keys, arguments);
            }
            return ascii("INVALID");
        }

        private Object append(String[] keys, byte[][] arguments) {
            if (hashes.containsKey(keys[1])) {
                return ascii("MANIFEST_PRESENT");
            }
            int generationArgumentCount = generationArgumentCount();
            int count = Integer.parseInt(text(arguments[generationArgumentCount]));
            Map<Bytes, byte[]> data = hashes.getOrDefault(keys[2], Map.of());
            Map<Bytes, Double> index = indexes.getOrDefault(keys[3], Map.of());
            Set<Bytes> seen = new HashSet<>();
            List<Bytes> entryKeys = new ArrayList<>(count);
            List<byte[]> payloads = new ArrayList<>(count);
            List<Boolean> existing = new ArrayList<>(count);
            for (int entryIndex = 0; entryIndex < count; entryIndex++) {
                Bytes entryKey = new Bytes(arguments[
                    generationArgumentCount + 1 + entryIndex * 2
                ]);
                byte[] payload = arguments[
                    generationArgumentCount + 2 + entryIndex * 2
                ].clone();
                if (!seen.add(entryKey)) {
                    return ascii("BATCH_DUPLICATE");
                }
                boolean inHash = data.containsKey(entryKey);
                boolean inIndex = index.containsKey(entryKey);
                if (inHash != inIndex) {
                    return ascii("ENTRY_HALF_PAIR");
                }
                if (inHash && data.get(entryKey).length != payload.length) {
                    return ascii("ENTRY_PAYLOAD_MISMATCH");
                }
                if (inHash && Double.compare(index.get(entryKey), 0D) != 0) {
                    return ascii("ENTRY_SCORE_NOT_ZERO");
                }
                entryKeys.add(entryKey);
                payloads.add(payload);
                existing.add(inHash);
            }
            for (int entryIndex = 0; entryIndex < count; entryIndex++) {
                if (existing.get(entryIndex)
                    && !Arrays.equals(data.get(entryKeys.get(entryIndex)), payloads.get(entryIndex))) {
                    return ascii("ENTRY_PAYLOAD_MISMATCH");
                }
            }

            Map<Bytes, byte[]> mutableData = hashes.computeIfAbsent(
                keys[2],
                ignored -> new LinkedHashMap<>()
            );
            Map<Bytes, Double> mutableIndex = indexes.computeIfAbsent(
                keys[3],
                ignored -> new LinkedHashMap<>()
            );
            List<Bytes> added = new ArrayList<>();
            int writeOrdinal = 0;
            for (int entryIndex = 0; entryIndex < count; entryIndex++) {
                if (!existing.get(entryIndex)) {
                    writeOrdinal++;
                    if (writeOrdinal == failWriteAt) {
                        return compensate(mutableData, mutableIndex, added);
                    }
                    Bytes entryKey = entryKeys.get(entryIndex);
                    mutableData.put(entryKey, payloads.get(entryIndex));
                    mutableIndex.put(entryKey, 0D);
                    added.add(entryKey);
                }
            }
            removeEmpty(keys[2], keys[3]);
            return ascii("APPEND_OK");
        }

        private Object compensate(
            Map<Bytes, byte[]> data,
            Map<Bytes, Double> index,
            List<Bytes> added
        ) {
            for (Bytes key : added) {
                if (!failRollback) {
                    data.remove(key);
                    index.remove(key);
                }
            }
            return ascii(failRollback ? "ROLLBACK_FAILED" : "WRITE_FAILED_ROLLED_BACK");
        }

        private Object manifest(String[] keys, byte[][] arguments) {
            List<String> manifestFields = RedisSnapshotManifestCodec.orderedFields();
            List<String> countFields = manifestCountFields();
            int manifestArgumentOffset = generationArgumentCount();
            for (int familyIndex = 0; familyIndex < countFields.size(); familyIndex++) {
                int countFieldIndex = manifestFields.indexOf(countFields.get(familyIndex));
                long expectedCount = Long.parseLong(text(
                    arguments[manifestArgumentOffset + countFieldIndex]
                ));
                if (hashSize(keys[2 + familyIndex * 2]) != expectedCount
                    || indexSize(keys[3 + familyIndex * 2]) != expectedCount) {
                    return ascii("FAMILY_COUNT_MISMATCH");
                }
            }
            Map<Bytes, byte[]> existing = hashes.get(keys[1]);
            if (existing != null) {
                if (existing.size() != manifestFields.size()) {
                    return ascii("MANIFEST_MISMATCH");
                }
                for (int fieldIndex = 0; fieldIndex < manifestFields.size(); fieldIndex++) {
                    Bytes field = new Bytes(ascii(
                        manifestFields.get(fieldIndex)
                    ));
                    if (!Arrays.equals(
                        existing.get(field),
                        arguments[manifestArgumentOffset + fieldIndex]
                    )) {
                        return ascii("MANIFEST_MISMATCH");
                    }
                }
                return ascii("MANIFEST_IDENTICAL");
            }
            Map<Bytes, byte[]> manifest = new LinkedHashMap<>();
            for (int fieldIndex = 0; fieldIndex < manifestFields.size(); fieldIndex++) {
                manifest.put(
                    new Bytes(ascii(manifestFields.get(fieldIndex))),
                    arguments[manifestArgumentOffset + fieldIndex].clone()
                );
            }
            hashes.put(keys[1], manifest);
            return ascii("MANIFEST_WRITTEN");
        }

        private boolean generationMatches(byte[][] arguments) {
            if (!Arrays.equals(currentRunId, arguments[0])) {
                return false;
            }
            for (int index = 0; index < expectedControlValues.size(); index++) {
                if (!Arrays.equals(expectedControlValues.get(index), arguments[index + 1])) {
                    return false;
                }
            }
            return true;
        }

        private void tamperLease() {
            expectedLeaseValues.set(0, ascii("tampered"));
        }

        private boolean leaseMatches(byte[][] arguments) {
            int offset = 1 + expectedControlValues.size();
            if (arguments.length < offset + expectedLeaseValues.size()) {
                return false;
            }
            for (int index = 0; index < expectedLeaseValues.size(); index++) {
                if (!Arrays.equals(expectedLeaseValues.get(index), arguments[offset + index])) {
                    return false;
                }
            }
            return true;
        }

        private List<String> controlValuesSnapshot() {
            return expectedControlValues.stream().map(FakeRedis::text).toList();
        }

        private List<String> appendScripts() {
            return scripts.stream().filter(script -> script.contains("APPEND_OK")).toList();
        }

        private List<String> manifestScripts() {
            return scripts.stream().filter(script -> script.contains("MANIFEST_WRITTEN")).toList();
        }

        private int manifestCount() {
            return (int) hashes.keySet().stream().filter(key -> key.endsWith(":manifest")).count();
        }

        private int hashSize(String key) {
            return hashes.getOrDefault(key, Map.of()).size();
        }

        private int indexSize(String key) {
            return indexes.getOrDefault(key, Map.of()).size();
        }

        private boolean hasHashField(String key, byte[] field) {
            return hashes.getOrDefault(key, Map.of()).containsKey(new Bytes(field));
        }

        private boolean hasIndexMember(String key, byte[] member) {
            return indexes.getOrDefault(key, Map.of()).containsKey(new Bytes(member));
        }

        private void putHashOnly(String dataKey, RedisCanonicalEntry entry) {
            hashes.computeIfAbsent(dataKey, ignored -> new LinkedHashMap<>())
                .put(new Bytes(entry.canonicalKey()), entry.canonicalPayload());
        }

        private void putPair(
            String dataKey,
            String indexKey,
            RedisCanonicalEntry entry,
            double score
        ) {
            putHashOnly(dataKey, entry);
            indexes.computeIfAbsent(indexKey, ignored -> new LinkedHashMap<>())
                .put(new Bytes(entry.canonicalKey()), score);
        }

        private void removeEmpty(String dataKey, String indexKey) {
            if (hashes.getOrDefault(dataKey, Map.of()).isEmpty()) {
                hashes.remove(dataKey);
            }
            if (indexes.getOrDefault(indexKey, Map.of()).isEmpty()) {
                indexes.remove(indexKey);
            }
        }

        private static String text(byte[] value) {
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    private static final class Bytes {

        private final byte[] value;

        private Bytes(byte[] value) {
            this.value = value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }
}
