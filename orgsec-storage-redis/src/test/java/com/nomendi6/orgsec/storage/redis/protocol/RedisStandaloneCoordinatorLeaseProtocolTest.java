package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import io.lettuce.core.ScriptOutputType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisStandaloneCoordinatorLeaseProtocolTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_RUN_ID =
        "89abcdef0123456789abcdef0123456789abcdef";
    private static final UUID STORAGE_UUID =
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID OWNER_SESSION_ID =
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID ACQUISITION_ID =
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final Duration LEASE_DURATION = Duration.ofSeconds(1);
    private static final int BASE_ARGUMENT_COUNT =
        2 + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;
    private static final Object NULL_RESPONSE = new Object();

    @Test
    void acquiresRenewsAndReleasesWithExactPostChecks() {
        FakeStream stream = new FakeStream(
            reply("ACQUIRED", "10000", "8", "9", "11000"),
            reply("MATCH", "10000"),
            reply("RENEWED", "10500", "9", "11500"),
            reply("MATCH", "10500"),
            reply("RELEASED", "10600", "10"),
            reply("MATCH", "10600")
        );
        FakeStreamProvider provider = new FakeStreamProvider(stream);
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);

        RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
            primary(),
            LEASE_DURATION
        ).orElseThrow();
        RedisCoordinatorLease.Verified acquired = session.lease();

        assertActiveLease(acquired, 8, 9, 10_000, 11_000);

        RedisCoordinatorLease.Verified renewed = session.renew();
        assertActiveLease(renewed, 9, 9, 10_000, 11_500);

        RedisCoordinatorLease.Verified released = session.release();
        assertThat(released.state()).isEqualTo(RedisCoordinatorLease.State.FREE);
        assertThat(released.revision()).isEqualTo(10);
        assertThat(released.fencingSequence()).isEqualTo(9);
        assertThat(released.ownerSessionId()).isNull();
        assertThat(released.acquisitionId()).isNull();
        assertThat(released.issuedAtRedisMillis()).isZero();
        assertThat(released.expiresAtRedisMillis()).isZero();

        assertThat(stream.events).containsExactly(
            "eval:acquire",
            "eval:verify",
            "eval:renew",
            "eval:verify",
            "eval:release",
            "eval:verify"
        );
        assertThat(stream.evalCalls).hasSize(6);
        assertThat(stream.closeCount).isOne();
        assertThat(stream.responses).isEmpty();

        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace("tenant-a");
        List<String> expectedKeys = List.of(
            keyspace.controlKey(),
            keyspace.leaseKey(),
            keyspace.leaseCounterKey()
        );
        assertThat(stream.evalCalls)
            .allSatisfy(call -> {
                assertThat(call.outputType()).isEqualTo(ScriptOutputType.MULTI);
                assertThat(text(call.keys())).containsExactlyElementsOf(expectedKeys);
                assertBaseArguments(call.arguments(), primary().controlEnvelope().orElseThrow());
            });

        EvalCall acquireCall = stream.evalCalls.get(0);
        assertThat(acquireCall.arguments().length).isEqualTo(BASE_ARGUMENT_COUNT + 3);
        assertThat(text(acquireCall.arguments(), BASE_ARGUMENT_COUNT))
            .containsExactly(
                "1000",
                OWNER_SESSION_ID.toString(),
                ACQUISITION_ID.toString()
            );
        assertLeaseArguments(stream.evalCalls.get(1), acquired, false);
        assertLeaseArguments(stream.evalCalls.get(2), acquired, true);
        assertLeaseArguments(stream.evalCalls.get(3), renewed, false);
        assertLeaseArguments(stream.evalCalls.get(4), renewed, false);
        assertLeaseArguments(stream.evalCalls.get(5), released, false);

        manager.close();
        assertThat(provider.closeCount).isOne();
    }

    @Test
    void busyAcquireClosesItsDedicatedStreamWithoutPostCheck() {
        FakeStream stream = new FakeStream(reply("BUSY"));
        FakeStreamProvider provider = new FakeStreamProvider(stream);
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);

        Optional<RedisStandaloneCoordinatorLeaseSession> result = manager.tryAcquire(
            primary(),
            LEASE_DURATION
        );

        assertThat(result).isEmpty();
        assertThat(provider.openCount).isOne();
        assertThat(stream.evalCalls).hasSize(1);
        assertThat(stream.closeCount).isOne();
        manager.close();
    }

    @Test
    void invalidMalformedNullAndImpossibleAcquireResponsesCloseTheStream() {
        assertAcquireResponseFailure(
            reply("UNKNOWN_STATUS"),
            RedisCoordinatorLeaseTransitionException.class
        );
        assertAcquireResponseFailure(
            "not-a-multi-bulk-response",
            RedisCoordinatorLeaseTransitionException.class
        );
        assertAcquireResponseFailure(
            NULL_RESPONSE,
            RedisProtocolUnavailableException.class
        );
        assertAcquireResponseFailure(
            reply("ACQUIRED", "10000", "8", "9", "10999"),
            RedisCoordinatorLeaseTransitionException.class
        );
        assertAcquireResponseFailure(
            reply("ACQUIRED", "10000", "8", "0", "11000"),
            RedisCoordinatorLeaseTransitionException.class
        );
    }

    @Test
    void leaseLostDuringRenewPostCheckPoisonsAndClosesTheSession() {
        FakeStream stream = new FakeStream(
            reply("ACQUIRED", "10000", "8", "9", "11000"),
            reply("MATCH", "10000"),
            reply("RENEWED", "10500", "9", "11500"),
            reply("LEASE_LOST")
        );
        FakeStreamProvider provider = new FakeStreamProvider(stream);
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);
        RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
            primary(),
            LEASE_DURATION
        ).orElseThrow();

        assertThatThrownBy(session::renew).isInstanceOfSatisfying(
            RedisCoordinatorLeaseTransitionException.class,
            failure -> assertThat(failure.reason()).isEqualTo(
                RedisCoordinatorLeaseTransitionException.Reason.LEASE_LOST
            )
        );

        assertThat(session.isPoisoned()).isTrue();
        assertThat(stream.closeCount).isOne();
        assertThat(stream.events).containsExactly(
            "eval:acquire",
            "eval:verify",
            "eval:renew",
            "eval:verify"
        );
        assertThatThrownBy(session::lease)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("POISONED");
        manager.close();
    }

    @Test
    void invalidDurationOrPrimaryContextNeverOpensAStream() {
        FakeStreamProvider provider = new FakeStreamProvider();
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);

        assertThatThrownBy(() -> manager.tryAcquire(primary(), null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manager.tryAcquire(primary(), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.tryAcquire(primary(), Duration.ofNanos(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.tryAcquire(
            primary(),
            Duration.ofMillis(
                RedisStandaloneCoordinatorLeaseScripts.MAX_LEASE_DURATION_MILLIS + 1
            )
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.tryAcquire(
            new RedisPrimarySnapshot(
                new RedisDatasetKeyspace("tenant-a"),
                observation(RUN_ID),
                null
            ),
            LEASE_DURATION
        )).isInstanceOf(IllegalArgumentException.class);
        RedisPrimarySnapshot bound = primary();
        assertThatThrownBy(() -> manager.tryAcquire(
            new RedisPrimarySnapshot(
                bound.observation(),
                bound.controlEnvelope().orElseThrow()
            ),
            LEASE_DURATION
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trusted requested keyspace");
        assertThatThrownBy(() -> manager.tryAcquire(
            primary(RUN_ID, OTHER_RUN_ID),
            LEASE_DURATION
        )).isInstanceOfSatisfying(
            RedisCoordinatorLeaseTransitionException.class,
            failure -> assertThat(failure.reason()).isEqualTo(
                RedisCoordinatorLeaseTransitionException.Reason.TOPOLOGY_CHANGED
            )
        );

        assertThat(provider.openCount).isZero();
        manager.close();
    }

    @Test
    void crossDatasetControlFailsBeforeOpeningAnyPrimaryStream() {
        FakeStreamProvider provider = new FakeStreamProvider();
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);
        RedisPrimarySnapshot mismatched = primary(
            new RedisDatasetKeyspace("tenant-a"),
            "tenant-b",
            RUN_ID,
            RUN_ID
        );

        assertThatThrownBy(() -> manager.tryAcquire(mismatched, LEASE_DURATION))
            .isInstanceOfSatisfying(
                RedisCoordinatorLeaseTransitionException.class,
                failure -> assertThat(failure.reason()).isEqualTo(
                    RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
                )
            );

        assertThat(provider.openCount).isZero();
        manager.close();
    }

    @Test
    void futureControlProtocolFailsBeforeOpeningAnyPrimaryStream() {
        FakeStreamProvider provider = new FakeStreamProvider();
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);
        SecurityDatasetIdentity futureIdentity = new SecurityDatasetIdentity("sensitive-dataset", 2);
        RedisIncarnation incarnation = new RedisIncarnation(RUN_ID, STORAGE_UUID);
        RedisControlEnvelope futureControl = mock(RedisControlEnvelope.class);
        when(futureControl.getIdentity()).thenReturn(futureIdentity);
        when(futureControl.getIncarnation()).thenReturn(incarnation);
        RedisPrimarySnapshot futurePrimary = mock(RedisPrimarySnapshot.class);
        when(futurePrimary.requestedKeyspace()).thenReturn(
            Optional.of(new RedisDatasetKeyspace("sensitive-dataset"))
        );
        when(futurePrimary.controlEnvelope()).thenReturn(Optional.of(futureControl));
        when(futurePrimary.observation()).thenReturn(observation(RUN_ID));

        assertThatThrownBy(() -> manager.tryAcquire(futurePrimary, LEASE_DURATION))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
        assertThat(provider.openCount).isZero();
        manager.close();
    }

    @Test
    void closeAbandonsWithoutExecutingReleaseAndIsIdempotent() {
        FakeStream stream = acquiredStream();
        FakeStreamProvider provider = new FakeStreamProvider(stream);
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);
        RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
            primary(),
            LEASE_DURATION
        ).orElseThrow();

        session.close();
        session.close();

        assertThat(stream.evalCalls).hasSize(2);
        assertThat(stream.events).doesNotContain("eval:release");
        assertThat(stream.closeCount).isOne();
        assertThatThrownBy(session::lease)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ABANDONED");
        manager.close();
    }

    @Test
    void sessionRejectsUseFromAnyThreadOtherThanItsOwner() throws InterruptedException {
        FakeStream stream = acquiredStream();
        FakeStreamProvider provider = new FakeStreamProvider(stream);
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);
        RedisStandaloneCoordinatorLeaseSession session = manager.tryAcquire(
            primary(),
            LEASE_DURATION
        ).orElseThrow();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread nonOwner = new Thread(() -> {
            try {
                session.lease();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "lease-non-owner");
        nonOwner.start();
        nonOwner.join();

        assertThat(failure.get()).isInstanceOfSatisfying(
            IllegalStateException.class,
            thrown -> assertThat(thrown)
                .hasMessageContaining("only be used by its owner thread")
        );
        assertThat(session.isPoisoned()).isFalse();
        assertThat(stream.closeCount).isZero();

        session.close();
        assertThat(stream.closeCount).isOne();
        manager.close();
    }

    @Test
    void managerCloseCanBeRetriedButNeverReopensAfterCleanupStarts() {
        FakeStreamProvider provider = new FakeStreamProvider();
        provider.closeFailuresRemaining = 1;
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);

        assertThatThrownBy(manager::close)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("simulated provider close failure");
        assertThatThrownBy(() -> manager.tryAcquire(primary(), LEASE_DURATION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("manager is closed");

        manager.close();
        manager.close();
        assertThat(provider.closeCount).isEqualTo(2);
        assertThat(provider.openCount).isZero();
    }

    private static void assertAcquireResponseFailure(
        Object response,
        Class<? extends Throwable> expectedType
    ) {
        FakeStream stream = new FakeStream(response);
        FakeStreamProvider provider = new FakeStreamProvider(stream);
        RedisStandaloneCoordinatorLeaseManager manager = manager(provider);

        Throwable failure = catchThrowable(() -> manager.tryAcquire(
            primary(),
            LEASE_DURATION
        ));

        assertThat(failure).isInstanceOf(expectedType);
        if (failure instanceof RedisCoordinatorLeaseTransitionException transition) {
            assertThat(transition.reason()).isEqualTo(
                RedisCoordinatorLeaseTransitionException.Reason.INVALID_SCRIPT_RESPONSE
            );
        }
        assertThat(provider.openCount).isOne();
        assertThat(stream.closeCount).isOne();
        manager.close();
    }

    private static void assertActiveLease(
        RedisCoordinatorLease.Verified lease,
        long revision,
        long fencingSequence,
        long issuedAtRedisMillis,
        long expiresAtRedisMillis
    ) {
        RedisControlEnvelope control = primary().controlEnvelope().orElseThrow();
        assertThat(lease.identity()).isEqualTo(control.getIdentity());
        assertThat(lease.incarnation()).isEqualTo(control.getIncarnation());
        assertThat(lease.boundControlCounter()).isEqualTo(control.getCounter());
        assertThat(lease.state()).isEqualTo(RedisCoordinatorLease.State.ACTIVE);
        assertThat(lease.revision()).isEqualTo(revision);
        assertThat(lease.fencingSequence()).isEqualTo(fencingSequence);
        assertThat(lease.ownerSessionId()).isEqualTo(OWNER_SESSION_ID);
        assertThat(lease.acquisitionId()).isEqualTo(ACQUISITION_ID);
        assertThat(lease.issuedAtRedisMillis()).isEqualTo(issuedAtRedisMillis);
        assertThat(lease.expiresAtRedisMillis()).isEqualTo(expiresAtRedisMillis);
    }

    private static void assertBaseArguments(
        byte[][] arguments,
        RedisControlEnvelope control
    ) {
        assertThat(arguments.length).isGreaterThanOrEqualTo(BASE_ARGUMENT_COUNT);
        assertThat(text(arguments[0])).isEqualTo(RUN_ID);
        assertThat(text(arguments[1])).isEqualTo(
            new RedisDatasetKeyspace("tenant-a").datasetHash()
        );
        Map<String, String> encoded = new RedisControlEnvelopeCodec().encode(control);
        List<String> expectedControl = new ArrayList<>();
        for (String field : RedisControlEnvelopeCodec.orderedFields()) {
            expectedControl.add(encoded.get(field));
        }
        assertThat(text(arguments, 2, BASE_ARGUMENT_COUNT))
            .containsExactlyElementsOf(expectedControl);
    }

    private static void assertLeaseArguments(
        EvalCall call,
        RedisCoordinatorLease.Verified lease,
        boolean hasDuration
    ) {
        Map<String, String> encoded = new RedisCoordinatorLeaseCodec().encode(lease);
        List<String> expectedLease = new ArrayList<>();
        for (String field : RedisCoordinatorLeaseCodec.orderedFields()) {
            expectedLease.add(encoded.get(field));
        }
        int leaseEnd = BASE_ARGUMENT_COUNT + RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT;
        assertThat(text(call.arguments(), BASE_ARGUMENT_COUNT, leaseEnd))
            .containsExactlyElementsOf(expectedLease);
        assertThat(call.arguments().length).isEqualTo(
            leaseEnd + (hasDuration ? 1 : 0)
        );
        if (hasDuration) {
            assertThat(text(call.arguments()[leaseEnd])).isEqualTo("1000");
        }
    }

    private static RedisStandaloneCoordinatorLeaseManager manager(
        FakeStreamProvider provider
    ) {
        return new RedisStandaloneCoordinatorLeaseManager(
            provider,
            new RedisStandaloneCoordinatorLeaseProtocol(),
            OWNER_SESSION_ID,
            () -> ACQUISITION_ID
        );
    }

    private static FakeStream acquiredStream() {
        return new FakeStream(
            reply("ACQUIRED", "10000", "8", "9", "11000"),
            reply("MATCH", "10000")
        );
    }

    private static RedisPrimarySnapshot primary() {
        return primary(RUN_ID, RUN_ID);
    }

    private static RedisPrimarySnapshot primary(
        String observedRunId,
        String controlRunId
    ) {
        return primary(
            new RedisDatasetKeyspace("tenant-a"),
            "tenant-a",
            observedRunId,
            controlRunId
        );
    }

    private static RedisPrimarySnapshot primary(
        RedisDatasetKeyspace requestedKeyspace,
        String controlDatasetId,
        String observedRunId,
        String controlRunId
    ) {
        return new RedisPrimarySnapshot(
            requestedKeyspace,
            observation(observedRunId),
            new RedisControlEnvelope(
                new SecurityDatasetIdentity(controlDatasetId, 1),
                new RedisIncarnation(controlRunId, STORAGE_UUID),
                5,
                null,
                RedisControlState.INITIALIZING
            )
        );
    }

    private static RedisPrimaryObservation observation(String runId) {
        return new RedisPrimaryObservation(runId, "master", false, "noeviction");
    }

    private static List<byte[]> reply(String... values) {
        List<byte[]> result = new ArrayList<>(values.length);
        for (String value : values) {
            result.add(ascii(value));
        }
        return result;
    }

    private static List<String> text(byte[][] values) {
        return text(values, 0, values.length);
    }

    private static List<String> text(byte[][] values, int start) {
        return text(values, start, values.length);
    }

    private static List<String> text(byte[][] values, int start, int end) {
        List<String> result = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            result.add(text(values[index]));
        }
        return result;
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private record EvalCall(
        byte[] script,
        ScriptOutputType outputType,
        byte[][] keys,
        byte[][] arguments
    ) { }

    private static final class FakeStreamProvider
        implements RedisStandaloneCoordinatorLeaseManager.StreamProvider {

        private final Deque<RedisPrimaryCommandStream> streams;
        private int openCount;
        private int closeCount;
        private int closeFailuresRemaining;

        private FakeStreamProvider(FakeStream... streams) {
            this.streams = new ArrayDeque<>(Arrays.asList(streams));
        }

        @Override
        public RedisPrimaryCommandStream open() {
            openCount++;
            if (streams.isEmpty()) {
                throw new AssertionError("No fake stream was configured");
            }
            return streams.removeFirst();
        }

        @Override
        public void close() {
            closeCount++;
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw new IllegalStateException("simulated provider close failure");
            }
        }
    }

    private static final class FakeStream implements RedisPrimaryCommandStream {

        private final Deque<Object> responses;
        private final List<EvalCall> evalCalls = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private int closeCount;

        private FakeStream(Object... responses) {
            this.responses = new ArrayDeque<>(Arrays.asList(responses));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T eval(
            byte[] script,
            ScriptOutputType outputType,
            byte[][] keys,
            byte[][] arguments
        ) {
            evalCalls.add(new EvalCall(
                script.clone(),
                outputType,
                deepCopy(keys),
                deepCopy(arguments)
            ));
            events.add("eval:" + scriptName(script));
            if (responses.isEmpty()) {
                throw new AssertionError("No fake script response was configured");
            }
            Object response = responses.removeFirst();
            if (response == NULL_RESPONSE) {
                return null;
            }
            return (T) response;
        }

        @Override
        public long clientId() {
            return 41;
        }

        @Override
        public boolean isPoisoned() {
            return false;
        }

        @Override
        public void close() {
            closeCount++;
        }

        private static String scriptName(byte[] script) {
            Map<String, byte[]> scripts = new LinkedHashMap<>();
            scripts.put("acquire", RedisStandaloneCoordinatorLeaseScripts.acquire());
            scripts.put("renew", RedisStandaloneCoordinatorLeaseScripts.renew());
            scripts.put("release", RedisStandaloneCoordinatorLeaseScripts.release());
            scripts.put("verify", RedisStandaloneCoordinatorLeaseScripts.verify());
            for (Map.Entry<String, byte[]> candidate : scripts.entrySet()) {
                if (Arrays.equals(script, candidate.getValue())) {
                    return candidate.getKey();
                }
            }
            return "unknown";
        }

        private static byte[][] deepCopy(byte[][] values) {
            byte[][] result = new byte[values.length][];
            for (int index = 0; index < values.length; index++) {
                result[index] = values[index].clone();
            }
            return result;
        }
    }
}
