package com.nomendi6.orgsec.storage.redis.protocol;

import io.lettuce.core.ScriptOutputType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Internal command/response boundary for standalone coordinator-lease transitions. */
final class RedisStandaloneCoordinatorLeaseProtocol {

    private static final int KEY_COUNT = 3;
    private static final Pattern CANONICAL_NON_NEGATIVE =
        Pattern.compile("0|[1-9][0-9]*");

    private final RedisControlEnvelopeCodec controlCodec;
    private final RedisCoordinatorLeaseCodec leaseCodec;

    RedisStandaloneCoordinatorLeaseProtocol() {
        this(new RedisControlEnvelopeCodec(), new RedisCoordinatorLeaseCodec());
    }

    RedisStandaloneCoordinatorLeaseProtocol(
        RedisControlEnvelopeCodec controlCodec,
        RedisCoordinatorLeaseCodec leaseCodec
    ) {
        this.controlCodec = Objects.requireNonNull(
            controlCodec,
            "controlCodec must not be null"
        );
        this.leaseCodec = Objects.requireNonNull(
            leaseCodec,
            "leaseCodec must not be null"
        );
    }

    Context context(RedisPrimarySnapshot expectedPrimary) {
        Objects.requireNonNull(expectedPrimary, "expectedPrimary must not be null");
        RedisDatasetKeyspace keyspace = expectedPrimary.requestedKeyspace().orElseThrow(
            () -> new IllegalArgumentException(
                "expectedPrimary must be bound to its trusted requested keyspace"
            )
        );
        RedisControlEnvelope control = expectedPrimary.controlEnvelope().orElseThrow(
            () -> new IllegalArgumentException(
                "expectedPrimary must contain an initialized control envelope"
            )
        );
        String controlDatasetId = control.getIdentity().getSecurityDatasetId();
        if (!keyspace.matchesSecurityDatasetId(controlDatasetId)) {
            throw failure(
                RedisCoordinatorLeaseTransitionException.Reason.CONTROL_CHANGED
            );
        }
        RedisPrimaryObservation observation = expectedPrimary.observation();
        if (!observation.runId().equals(control.getIncarnation().getPrimaryRunId())) {
            throw failure(
                RedisCoordinatorLeaseTransitionException.Reason.TOPOLOGY_CHANGED
            );
        }
        Map<String, String> controlFields = controlCodec.encode(control);
        List<byte[]> baseArguments = new ArrayList<>(
            2 + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
        );
        baseArguments.add(ascii(observation.runId()));
        baseArguments.add(ascii(keyspace.datasetHash()));
        for (String field : RedisControlEnvelopeCodec.orderedFields()) {
            baseArguments.add(utf8(controlFields.get(field)));
        }
        return new Context(
            expectedPrimary,
            control,
            keyspace,
            List.copyOf(baseArguments)
        );
    }

    long durationMillis(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        final long millis;
        try {
            millis = duration.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("duration is too large", exception);
        }
        if (millis <= 0
            || millis > RedisStandaloneCoordinatorLeaseScripts.MAX_LEASE_DURATION_MILLIS
            || !duration.equals(Duration.ofMillis(millis))) {
            throw new IllegalArgumentException(
                "duration must be a whole number of milliseconds between 1 and " +
                    RedisStandaloneCoordinatorLeaseScripts.MAX_LEASE_DURATION_MILLIS
            );
        }
        return millis;
    }

    AcquireOutcome acquire(
        RedisPrimaryCommandStream stream,
        Context context,
        UUID ownerSessionId,
        UUID acquisitionId,
        long durationMillis
    ) {
        Objects.requireNonNull(stream, "stream must not be null");
        Objects.requireNonNull(context, "context must not be null");
        requireUuidV4(ownerSessionId, "ownerSessionId");
        requireUuidV4(acquisitionId, "acquisitionId");
        requireDurationMillis(durationMillis);

        List<byte[]> arguments = context.baseArguments();
        arguments.add(ascii(Long.toString(durationMillis)));
        arguments.add(ascii(ownerSessionId.toString()));
        arguments.add(ascii(acquisitionId.toString()));
        Reply reply = evaluate(
            stream,
            RedisStandaloneCoordinatorLeaseScripts.acquire(),
            context.keys(),
            arguments,
            "acquire"
        );
        if ("BUSY".equals(reply.status())) {
            reply.requireSize(1);
            return AcquireOutcome.busy();
        }
        requireSuccessStatus(reply, "ACQUIRED", 5);

        long observedRedisMillis = reply.number(1);
        long revision = reply.number(2);
        long fencingSequence = reply.number(3);
        long expiresAtRedisMillis = reply.number(4);
        if (revision == 0
            || fencingSequence == 0
            || expiresAtRedisMillis != expectedExpiry(
                observedRedisMillis,
                durationMillis
            )) {
            throw invalidResponse();
        }
        RedisCoordinatorLease.Verified acquired = verifyActive(
            context,
            revision,
            fencingSequence,
            ownerSessionId,
            acquisitionId,
            observedRedisMillis,
            expiresAtRedisMillis,
            observedRedisMillis
        );
        exactPostCheck(stream, context, acquired);
        return AcquireOutcome.acquired(acquired);
    }

    RedisCoordinatorLease.Verified renew(
        RedisPrimaryCommandStream stream,
        Context context,
        RedisCoordinatorLease.Verified current,
        long durationMillis
    ) {
        Objects.requireNonNull(stream, "stream must not be null");
        requireActiveContext(current, context);
        requireDurationMillis(durationMillis);
        List<byte[]> arguments = transitionArguments(context, current);
        arguments.add(ascii(Long.toString(durationMillis)));
        Reply reply = evaluate(
            stream,
            RedisStandaloneCoordinatorLeaseScripts.renew(),
            context.keys(),
            arguments,
            "renew"
        );
        requireSuccessStatus(reply, "RENEWED", 4);
        long observedRedisMillis = reply.number(1);
        long revision = reply.number(2);
        long expiresAtRedisMillis = reply.number(3);
        if (current.revision() == RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER
            || revision != current.revision() + 1
            || expiresAtRedisMillis != expectedExpiry(
                observedRedisMillis,
                durationMillis
            )
            || expiresAtRedisMillis < current.expiresAtRedisMillis()) {
            throw invalidResponse();
        }
        RedisCoordinatorLease.Verified renewed = verifyActive(
            context,
            revision,
            current.fencingSequence(),
            current.ownerSessionId(),
            current.acquisitionId(),
            current.issuedAtRedisMillis(),
            expiresAtRedisMillis,
            observedRedisMillis
        );
        exactPostCheck(stream, context, renewed);
        return renewed;
    }

    RedisCoordinatorLease.Verified release(
        RedisPrimaryCommandStream stream,
        Context context,
        RedisCoordinatorLease.Verified current
    ) {
        Objects.requireNonNull(stream, "stream must not be null");
        requireActiveContext(current, context);
        Reply reply = evaluate(
            stream,
            RedisStandaloneCoordinatorLeaseScripts.release(),
            context.keys(),
            transitionArguments(context, current),
            "release"
        );
        requireSuccessStatus(reply, "RELEASED", 3);
        reply.number(1);
        long revision = reply.number(2);
        if (current.revision() == RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER
            || revision != current.revision() + 1) {
            throw invalidResponse();
        }
        RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
            context.control().getIdentity(),
            context.keyspace().datasetHash(),
            context.control().getIncarnation(),
            context.control().getCounter(),
            RedisCoordinatorLease.State.FREE,
            revision,
            current.fencingSequence(),
            null,
            null,
            0,
            0
        );
        RedisCoordinatorLease.Verified released =
            RedisCoordinatorLease.verifyFreeForContext(
                candidate,
                context.control().getIdentity(),
                context.keyspace().datasetHash(),
                context.control().getIncarnation(),
                context.control().getCounter(),
                revision,
                current.fencingSequence()
            );
        exactPostCheck(stream, context, released);
        return released;
    }

    private void exactPostCheck(
        RedisPrimaryCommandStream stream,
        Context context,
        RedisCoordinatorLease.Verified expected
    ) {
        Reply reply = evaluate(
            stream,
            RedisStandaloneCoordinatorLeaseScripts.verify(),
            context.keys(),
            transitionArguments(context, expected),
            "post-check"
        );
        requireSuccessStatus(reply, "MATCH", 2);
        long observedRedisMillis = reply.number(1);
        if (expected.state() == RedisCoordinatorLease.State.ACTIVE) {
            verifyActive(
                context,
                expected.revision(),
                expected.fencingSequence(),
                expected.ownerSessionId(),
                expected.acquisitionId(),
                expected.issuedAtRedisMillis(),
                expected.expiresAtRedisMillis(),
                observedRedisMillis
            );
        }
    }

    private RedisCoordinatorLease.Verified verifyActive(
        Context context,
        long revision,
        long fencingSequence,
        UUID ownerSessionId,
        UUID acquisitionId,
        long issuedAtRedisMillis,
        long expiresAtRedisMillis,
        long observedRedisMillis
    ) {
        try {
            RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
                context.control().getIdentity(),
                context.keyspace().datasetHash(),
                context.control().getIncarnation(),
                context.control().getCounter(),
                RedisCoordinatorLease.State.ACTIVE,
                revision,
                fencingSequence,
                ownerSessionId,
                acquisitionId,
                issuedAtRedisMillis,
                expiresAtRedisMillis
            );
            return RedisCoordinatorLease.verifyActiveForContext(
                candidate,
                context.control().getIdentity(),
                context.keyspace().datasetHash(),
                context.control().getIncarnation(),
                context.control().getCounter(),
                revision,
                fencingSequence,
                ownerSessionId,
                acquisitionId,
                issuedAtRedisMillis,
                expiresAtRedisMillis,
                observedRedisMillis
            );
        } catch (
            IllegalArgumentException | RedisCoordinatorLeaseVerificationException failure
        ) {
            throw invalidResponse();
        }
    }

    private List<byte[]> transitionArguments(
        Context context,
        RedisCoordinatorLease.Verified lease
    ) {
        List<byte[]> arguments = context.baseArguments();
        Map<String, String> fields = leaseCodec.encode(lease);
        for (String field : RedisCoordinatorLeaseCodec.orderedFields()) {
            arguments.add(RedisCoordinatorLeaseCodec.encodeBoundedUtf8Value(
                field,
                fields.get(field)
            ));
        }
        return arguments;
    }

    private static Reply evaluate(
        RedisPrimaryCommandStream stream,
        byte[] script,
        byte[][] keys,
        List<byte[]> arguments,
        String operation
    ) {
        Object raw = stream.eval(
            script,
            ScriptOutputType.MULTI,
            keys,
            arguments.toArray(byte[][]::new)
        );
        if (raw == null) {
            throw new RedisProtocolUnavailableException(
                "Redis standalone lease " + operation + " script returned no result."
            );
        }
        return Reply.decode(raw);
    }

    private static void requireSuccessStatus(
        Reply reply,
        String expectedStatus,
        int expectedSize
    ) {
        if (expectedStatus.equals(reply.status())) {
            reply.requireSize(expectedSize);
            return;
        }
        throw statusFailure(reply);
    }

    private static RedisCoordinatorLeaseTransitionException statusFailure(Reply reply) {
        reply.requireSize(1);
        final RedisCoordinatorLeaseTransitionException.Reason reason;
        try {
            reason = RedisCoordinatorLeaseTransitionException.Reason.valueOf(reply.status());
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
        return failure(reason);
    }

    private void requireActiveContext(
        RedisCoordinatorLease.Verified lease,
        Context context
    ) {
        Objects.requireNonNull(lease, "lease must not be null");
        if (lease.state() != RedisCoordinatorLease.State.ACTIVE
            || !lease.identity().equals(context.control().getIdentity())
            || !lease.datasetHash().equals(context.keyspace().datasetHash())
            || !lease.incarnation().equals(context.control().getIncarnation())
            || lease.boundControlCounter() != context.control().getCounter()) {
            throw new IllegalArgumentException(
                "lease must be ACTIVE under the manager's exact control context"
            );
        }
    }

    private static void requireDurationMillis(long durationMillis) {
        if (durationMillis <= 0
            || durationMillis >
                RedisStandaloneCoordinatorLeaseScripts.MAX_LEASE_DURATION_MILLIS) {
            throw new IllegalArgumentException("durationMillis is outside the supported range");
        }
    }

    private static long expectedExpiry(long observedRedisMillis, long durationMillis) {
        if (observedRedisMillis < 0
            || observedRedisMillis > RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER
            || durationMillis >
                RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER - observedRedisMillis) {
            throw invalidResponse();
        }
        return observedRedisMillis + durationMillis;
    }

    private static UUID requireUuidV4(UUID value, String name) {
        if (value == null || value.version() != 4 || value.variant() != 2) {
            throw new IllegalArgumentException(name + " must be an RFC 4122 UUIDv4");
        }
        return value;
    }

    private static RedisCoordinatorLeaseTransitionException invalidResponse() {
        return failure(
            RedisCoordinatorLeaseTransitionException.Reason.INVALID_SCRIPT_RESPONSE
        );
    }

    private static RedisCoordinatorLeaseTransitionException failure(
        RedisCoordinatorLeaseTransitionException.Reason reason
    ) {
        return new RedisCoordinatorLeaseTransitionException(reason);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static final class Context {

        private final RedisPrimarySnapshot primary;
        private final RedisControlEnvelope control;
        private final RedisDatasetKeyspace keyspace;
        private final List<byte[]> baseArguments;

        private Context(
            RedisPrimarySnapshot primary,
            RedisControlEnvelope control,
            RedisDatasetKeyspace keyspace,
            List<byte[]> baseArguments
        ) {
            this.primary = primary;
            this.control = control;
            this.keyspace = keyspace;
            this.baseArguments = baseArguments;
        }

        RedisPrimarySnapshot primary() {
            return primary;
        }

        RedisControlEnvelope control() {
            return control;
        }

        RedisDatasetKeyspace keyspace() {
            return keyspace;
        }

        byte[][] keys() {
            byte[][] keys = new byte[KEY_COUNT][];
            keys[0] = utf8(keyspace.controlKey());
            keys[1] = utf8(keyspace.leaseKey());
            keys[2] = utf8(keyspace.leaseCounterKey());
            return keys;
        }

        List<byte[]> baseArguments() {
            List<byte[]> copy = new ArrayList<>(baseArguments.size());
            for (byte[] value : baseArguments) {
                copy.add(value.clone());
            }
            return copy;
        }
    }

    static final class AcquireOutcome {

        private final RedisCoordinatorLease.Verified lease;

        private AcquireOutcome(RedisCoordinatorLease.Verified lease) {
            this.lease = lease;
        }

        static AcquireOutcome acquired(RedisCoordinatorLease.Verified lease) {
            return new AcquireOutcome(Objects.requireNonNull(lease, "lease must not be null"));
        }

        static AcquireOutcome busy() {
            return new AcquireOutcome(null);
        }

        boolean isAcquired() {
            return lease != null;
        }

        RedisCoordinatorLease.Verified lease() {
            if (lease == null) {
                throw new IllegalStateException("busy acquisition has no lease");
            }
            return lease;
        }
    }

    private static final class Reply {

        private final List<String> elements;

        private Reply(List<String> elements) {
            this.elements = elements;
        }

        static Reply decode(Object raw) {
            if (!(raw instanceof List<?> values) || values.isEmpty()) {
                throw invalidResponse();
            }
            List<String> decoded = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof byte[] bytes) || bytes.length == 0 || bytes.length > 32) {
                    throw invalidResponse();
                }
                for (byte item : bytes) {
                    if (item < 0 || item > 0x7f) {
                        throw invalidResponse();
                    }
                }
                decoded.add(new String(bytes, StandardCharsets.US_ASCII));
            }
            return new Reply(List.copyOf(decoded));
        }

        String status() {
            return elements.get(0);
        }

        long number(int index) {
            if (index <= 0 || index >= elements.size()) {
                throw invalidResponse();
            }
            String value = elements.get(index);
            if (!CANONICAL_NON_NEGATIVE.matcher(value).matches()) {
                throw invalidResponse();
            }
            final long parsed;
            try {
                parsed = Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw invalidResponse();
            }
            if (parsed > RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER) {
                throw invalidResponse();
            }
            return parsed;
        }

        void requireSize(int expected) {
            if (elements.size() != expected) {
                throw invalidResponse();
            }
        }
    }
}
