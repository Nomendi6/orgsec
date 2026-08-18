package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Trust boundary for persistent protocol-v1 dataset coordinator lease metadata.
 *
 * <p>The record is logical state, not a Redis TTL lock. Both the record and its separate monotonic
 * counter key must be retained without TTL and excluded from GC. {@link State#FREE} retains the
 * last fencing sequence while clearing grant-specific fields. {@link State#ACTIVE} binds a grant
 * to the composite fencing identity {@code (primaryRunId, storageUuid, fencingSequence,
 * acquisitionId)} as well as the process owner session and exact control generation.</p>
 *
 * <p>The sequence is monotonic only inside one storage incarnation. It may reset solely when a
 * full bootstrap creates a new {@link RedisIncarnation}; this structural model cannot authorize or
 * prove that reset. A decoded {@link Unverified} value conveys no authority. Exact trusted context
 * and Redis server time are required to obtain {@link Verified} state.</p>
 *
 * <p>This class deliberately defines no acquire, renewal, release, takeover, or Redis command.
 * Those transitions require future atomic scripts that update the lease record, counter and
 * {@code boundControlCounter} consistently. Every future mutating script must recheck the complete
 * active fencing identity; an owner UUID or acquisition UUID alone is only a bearer value.</p>
 */
final class RedisCoordinatorLease {

    static final long SCHEMA_VERSION = 1;
    static final long MAX_LUA_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final Pattern LOWER_SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final SecurityDatasetIdentity identity;
    private final String datasetHash;
    private final RedisIncarnation incarnation;
    private final long boundControlCounter;
    private final State state;
    private final long revision;
    private final long fencingSequence;
    private final UUID ownerSessionId;
    private final UUID acquisitionId;
    private final long issuedAtRedisMillis;
    private final long expiresAtRedisMillis;

    private RedisCoordinatorLease(
        SecurityDatasetIdentity identity,
        String datasetHash,
        RedisIncarnation incarnation,
        long boundControlCounter,
        State state,
        long revision,
        long fencingSequence,
        UUID ownerSessionId,
        UUID acquisitionId,
        long issuedAtRedisMillis,
        long expiresAtRedisMillis
    ) {
        this.identity = requireIdentity(identity);
        this.datasetHash = requireDatasetHash(datasetHash);
        this.incarnation = Objects.requireNonNull(incarnation, "incarnation must not be null");
        this.boundControlCounter = requireLuaSafeNonNegative(
            boundControlCounter,
            "boundControlCounter"
        );
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.revision = requireLuaSafeNonNegative(revision, "revision");
        this.fencingSequence = requireLuaSafeNonNegative(
            fencingSequence,
            "fencingSequence"
        );
        if (state == State.FREE) {
            if (ownerSessionId != null
                || acquisitionId != null
                || issuedAtRedisMillis != 0
                || expiresAtRedisMillis != 0) {
                throw new IllegalArgumentException(
                    "FREE lease must clear owner, acquisition and Redis-time fields"
                );
            }
        } else {
            requireUuidV4(ownerSessionId, "ownerSessionId");
            requireUuidV4(acquisitionId, "acquisitionId");
            requireLuaSafePositive(fencingSequence, "fencingSequence");
            requireLuaSafePositive(issuedAtRedisMillis, "issuedAtRedisMillis");
            requireLuaSafePositive(expiresAtRedisMillis, "expiresAtRedisMillis");
            if (expiresAtRedisMillis <= issuedAtRedisMillis) {
                throw new IllegalArgumentException(
                    "expiresAtRedisMillis must be greater than issuedAtRedisMillis"
                );
            }
        }
        this.ownerSessionId = ownerSessionId;
        this.acquisitionId = acquisitionId;
        this.issuedAtRedisMillis = issuedAtRedisMillis;
        this.expiresAtRedisMillis = expiresAtRedisMillis;
    }

    static Unverified unverified(
        SecurityDatasetIdentity identity,
        String datasetHash,
        RedisIncarnation incarnation,
        long boundControlCounter,
        State state,
        long revision,
        long fencingSequence,
        UUID ownerSessionId,
        UUID acquisitionId,
        long issuedAtRedisMillis,
        long expiresAtRedisMillis
    ) {
        return new Unverified(new RedisCoordinatorLease(
            identity,
            datasetHash,
            incarnation,
            boundControlCounter,
            state,
            revision,
            fencingSequence,
            ownerSessionId,
            acquisitionId,
            issuedAtRedisMillis,
            expiresAtRedisMillis
        ));
    }

    /** Verifies persistent FREE state against exact authoritative context. */
    static Verified verifyFreeForContext(
        Unverified candidate,
        SecurityDatasetIdentity expectedIdentity,
        String expectedDatasetHash,
        RedisIncarnation expectedIncarnation,
        long expectedBoundControlCounter,
        long expectedRevision,
        long expectedFencingSequence
    ) {
        VerificationContext context = VerificationContext.free(
            expectedIdentity,
            expectedDatasetHash,
            expectedIncarnation,
            expectedBoundControlCounter,
            expectedRevision,
            expectedFencingSequence
        );
        return verify(candidate, context);
    }

    /**
     * Verifies ACTIVE state against exact authoritative context and Redis server time.
     *
     * <p>The expected sequence and acquisition ID must come from the future atomic allocator
     * result; copying them from {@code candidate} would not cross a trust boundary. JVM time is
     * not an admissible substitute for {@code observedRedisTimeMillis}. The expected issued and
     * expiry times likewise come from the exact acquire/renew result and are matched before the
     * separate half-open validity check.</p>
     */
    static Verified verifyActiveForContext(
        Unverified candidate,
        SecurityDatasetIdentity expectedIdentity,
        String expectedDatasetHash,
        RedisIncarnation expectedIncarnation,
        long expectedBoundControlCounter,
        long expectedRevision,
        long expectedFencingSequence,
        UUID expectedOwnerSessionId,
        UUID expectedAcquisitionId,
        long expectedIssuedAtRedisMillis,
        long expectedExpiresAtRedisMillis,
        long observedRedisTimeMillis
    ) {
        VerificationContext context = VerificationContext.active(
            expectedIdentity,
            expectedDatasetHash,
            expectedIncarnation,
            expectedBoundControlCounter,
            expectedRevision,
            expectedFencingSequence,
            expectedOwnerSessionId,
            expectedAcquisitionId,
            expectedIssuedAtRedisMillis,
            expectedExpiresAtRedisMillis,
            observedRedisTimeMillis
        );
        return verify(candidate, context);
    }

    private static Verified verify(Unverified candidate, VerificationContext context) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        RedisCoordinatorLease actual = candidate.lease;
        SecurityDatasetIdentity actualIdentity = actual.identity;
        SecurityDatasetIdentity expectedIdentity = context.identity;

        requireEqual(
            actualIdentity.getSecurityDatasetId(),
            expectedIdentity.getSecurityDatasetId(),
            RedisCoordinatorLeaseVerificationException.Reason.DATASET_ID_MISMATCH
        );
        requireEqual(
            actual.datasetHash,
            context.datasetHash,
            RedisCoordinatorLeaseVerificationException.Reason.DATASET_HASH_MISMATCH
        );
        requireEqual(
            actualIdentity.getProtocolVersion(),
            expectedIdentity.getProtocolVersion(),
            RedisCoordinatorLeaseVerificationException.Reason.PROTOCOL_VERSION_MISMATCH
        );
        requireEqual(
            actual.incarnation.getPrimaryRunId(),
            context.incarnation.getPrimaryRunId(),
            RedisCoordinatorLeaseVerificationException.Reason.PRIMARY_RUN_ID_MISMATCH
        );
        requireEqual(
            actual.incarnation.getStorageUuid(),
            context.incarnation.getStorageUuid(),
            RedisCoordinatorLeaseVerificationException.Reason.STORAGE_UUID_MISMATCH
        );
        requireEqual(
            actual.boundControlCounter,
            context.boundControlCounter,
            RedisCoordinatorLeaseVerificationException.Reason.BOUND_CONTROL_COUNTER_MISMATCH
        );
        requireEqual(
            actual.state,
            context.state,
            RedisCoordinatorLeaseVerificationException.Reason.STATE_MISMATCH
        );
        requireEqual(
            actual.revision,
            context.revision,
            RedisCoordinatorLeaseVerificationException.Reason.REVISION_MISMATCH
        );
        requireEqual(
            actual.fencingSequence,
            context.fencingSequence,
            RedisCoordinatorLeaseVerificationException.Reason.FENCING_SEQUENCE_MISMATCH
        );
        requireEqual(
            actual.ownerSessionId,
            context.ownerSessionId,
            RedisCoordinatorLeaseVerificationException.Reason.OWNER_SESSION_ID_MISMATCH
        );
        requireEqual(
            actual.acquisitionId,
            context.acquisitionId,
            RedisCoordinatorLeaseVerificationException.Reason.ACQUISITION_ID_MISMATCH
        );
        if (actual.state == State.ACTIVE) {
            requireEqual(
                actual.issuedAtRedisMillis,
                context.issuedAtRedisMillis,
                RedisCoordinatorLeaseVerificationException.Reason
                    .ISSUED_AT_REDIS_MILLIS_MISMATCH
            );
            requireEqual(
                actual.expiresAtRedisMillis,
                context.expiresAtRedisMillis,
                RedisCoordinatorLeaseVerificationException.Reason
                    .EXPIRES_AT_REDIS_MILLIS_MISMATCH
            );
            if (context.observedRedisTimeMillis < actual.issuedAtRedisMillis) {
                throw verification(
                    RedisCoordinatorLeaseVerificationException.Reason.LEASE_NOT_YET_VALID
                );
            }
            if (context.observedRedisTimeMillis >= actual.expiresAtRedisMillis) {
                throw verification(
                    RedisCoordinatorLeaseVerificationException.Reason.LEASE_EXPIRED
                );
            }
        }

        return new Verified(new RedisCoordinatorLease(
            context.identity,
            context.datasetHash,
            context.incarnation,
            context.boundControlCounter,
            context.state,
            context.revision,
            context.fencingSequence,
            context.ownerSessionId,
            context.acquisitionId,
            context.issuedAtRedisMillis,
            context.expiresAtRedisMillis
        ));
    }

    private static SecurityDatasetIdentity requireIdentity(SecurityDatasetIdentity identity) {
        return RedisWireProtocol.requireVersionOne(identity);
    }

    private static String requireDatasetHash(String value) {
        if (value == null || !LOWER_SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "datasetHash must be a canonical lower-case SHA-256 digest"
            );
        }
        return value;
    }

    private static void requireExpectedDatasetHash(
        SecurityDatasetIdentity expectedIdentity,
        String expectedDatasetHash
    ) {
        String canonicalHash = new RedisDatasetKeyspace(
            expectedIdentity.getSecurityDatasetId()
        ).datasetHash();
        if (!canonicalHash.equals(expectedDatasetHash)) {
            throw new IllegalArgumentException(
                "expectedDatasetHash must match expectedIdentity.securityDatasetId"
            );
        }
    }

    private static UUID requireUuidV4(UUID value, String name) {
        if (value == null || value.version() != 4 || value.variant() != 2) {
            throw new IllegalArgumentException(name + " must be an RFC 4122 UUIDv4");
        }
        return value;
    }

    private static long requireLuaSafeNonNegative(long value, String name) {
        if (value < 0 || value > MAX_LUA_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                name + " must be between 0 and " + MAX_LUA_SAFE_INTEGER
            );
        }
        return value;
    }

    private static long requireLuaSafePositive(long value, String name) {
        if (value <= 0 || value > MAX_LUA_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                name + " must be between 1 and " + MAX_LUA_SAFE_INTEGER
            );
        }
        return value;
    }

    private static void requireEqual(
        Object actual,
        Object expected,
        RedisCoordinatorLeaseVerificationException.Reason reason
    ) {
        if (!Objects.equals(actual, expected)) {
            throw verification(reason);
        }
    }

    private static void requireEqual(
        long actual,
        long expected,
        RedisCoordinatorLeaseVerificationException.Reason reason
    ) {
        if (actual != expected) {
            throw verification(reason);
        }
    }

    private static RedisCoordinatorLeaseVerificationException verification(
        RedisCoordinatorLeaseVerificationException.Reason reason
    ) {
        return new RedisCoordinatorLeaseVerificationException(reason);
    }

    enum State {
        FREE,
        ACTIVE
    }

    /** Syntactically valid persistent wire state that conveys no lease authority. */
    static final class Unverified {

        private final RedisCoordinatorLease lease;

        private Unverified(RedisCoordinatorLease lease) {
            this.lease = Objects.requireNonNull(lease, "lease must not be null");
        }

        SecurityDatasetIdentity identity() {
            return lease.identity;
        }

        String datasetHash() {
            return lease.datasetHash;
        }

        RedisIncarnation incarnation() {
            return lease.incarnation;
        }

        long boundControlCounter() {
            return lease.boundControlCounter;
        }

        State state() {
            return lease.state;
        }

        long revision() {
            return lease.revision;
        }

        long fencingSequence() {
            return lease.fencingSequence;
        }

        UUID ownerSessionId() {
            return lease.ownerSessionId;
        }

        UUID acquisitionId() {
            return lease.acquisitionId;
        }

        long issuedAtRedisMillis() {
            return lease.issuedAtRedisMillis;
        }

        long expiresAtRedisMillis() {
            return lease.expiresAtRedisMillis;
        }
    }

    /** Exact contextually trusted persistent lease state eligible for canonical encoding. */
    static final class Verified {

        private final RedisCoordinatorLease lease;

        private Verified(RedisCoordinatorLease lease) {
            this.lease = Objects.requireNonNull(lease, "lease must not be null");
        }

        SecurityDatasetIdentity identity() {
            return lease.identity;
        }

        String datasetHash() {
            return lease.datasetHash;
        }

        RedisIncarnation incarnation() {
            return lease.incarnation;
        }

        long boundControlCounter() {
            return lease.boundControlCounter;
        }

        State state() {
            return lease.state;
        }

        long revision() {
            return lease.revision;
        }

        long fencingSequence() {
            return lease.fencingSequence;
        }

        UUID ownerSessionId() {
            return lease.ownerSessionId;
        }

        UUID acquisitionId() {
            return lease.acquisitionId;
        }

        long issuedAtRedisMillis() {
            return lease.issuedAtRedisMillis;
        }

        long expiresAtRedisMillis() {
            return lease.expiresAtRedisMillis;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Verified)) {
                return false;
            }
            Verified that = (Verified) other;
            return lease.equals(that.lease);
        }

        @Override
        public int hashCode() {
            return lease.hashCode();
        }
    }

    private static final class VerificationContext {

        private final SecurityDatasetIdentity identity;
        private final String datasetHash;
        private final RedisIncarnation incarnation;
        private final long boundControlCounter;
        private final State state;
        private final long revision;
        private final long fencingSequence;
        private final UUID ownerSessionId;
        private final UUID acquisitionId;
        private final long issuedAtRedisMillis;
        private final long expiresAtRedisMillis;
        private final long observedRedisTimeMillis;

        private VerificationContext(
            SecurityDatasetIdentity identity,
            String datasetHash,
            RedisIncarnation incarnation,
            long boundControlCounter,
            State state,
            long revision,
            long fencingSequence,
            UUID ownerSessionId,
            UUID acquisitionId,
            long issuedAtRedisMillis,
            long expiresAtRedisMillis,
            long observedRedisTimeMillis
        ) {
            this.identity = requireIdentity(identity);
            this.datasetHash = requireDatasetHash(datasetHash);
            requireExpectedDatasetHash(this.identity, this.datasetHash);
            this.incarnation = Objects.requireNonNull(
                incarnation,
                "expectedIncarnation must not be null"
            );
            this.boundControlCounter = requireLuaSafeNonNegative(
                boundControlCounter,
                "expectedBoundControlCounter"
            );
            this.state = Objects.requireNonNull(state, "expectedState must not be null");
            this.revision = requireLuaSafeNonNegative(revision, "expectedRevision");
            this.fencingSequence = requireLuaSafeNonNegative(
                fencingSequence,
                "expectedFencingSequence"
            );
            if (state == State.FREE) {
                if (ownerSessionId != null
                    || acquisitionId != null
                    || issuedAtRedisMillis != 0
                    || expiresAtRedisMillis != 0
                    || observedRedisTimeMillis != 0) {
                    throw new IllegalArgumentException(
                        "FREE verification context must clear active grant fields"
                    );
                }
            } else {
                requireUuidV4(
                    ownerSessionId,
                    "expectedOwnerSessionId"
                );
                requireUuidV4(
                    acquisitionId,
                    "expectedAcquisitionId"
                );
                requireLuaSafePositive(fencingSequence, "expectedFencingSequence");
                requireLuaSafePositive(
                    issuedAtRedisMillis,
                    "expectedIssuedAtRedisMillis"
                );
                requireLuaSafePositive(
                    expiresAtRedisMillis,
                    "expectedExpiresAtRedisMillis"
                );
                if (expiresAtRedisMillis <= issuedAtRedisMillis) {
                    throw new IllegalArgumentException(
                        "expectedExpiresAtRedisMillis must be greater than " +
                            "expectedIssuedAtRedisMillis"
                    );
                }
                requireLuaSafePositive(
                    observedRedisTimeMillis,
                    "observedRedisTimeMillis"
                );
            }
            this.ownerSessionId = ownerSessionId;
            this.acquisitionId = acquisitionId;
            this.issuedAtRedisMillis = issuedAtRedisMillis;
            this.expiresAtRedisMillis = expiresAtRedisMillis;
            this.observedRedisTimeMillis = observedRedisTimeMillis;
        }

        private static VerificationContext free(
            SecurityDatasetIdentity identity,
            String datasetHash,
            RedisIncarnation incarnation,
            long boundControlCounter,
            long revision,
            long fencingSequence
        ) {
            return new VerificationContext(
                identity,
                datasetHash,
                incarnation,
                boundControlCounter,
                State.FREE,
                revision,
                fencingSequence,
                null,
                null,
                0,
                0,
                0
            );
        }

        private static VerificationContext active(
            SecurityDatasetIdentity identity,
            String datasetHash,
            RedisIncarnation incarnation,
            long boundControlCounter,
            long revision,
            long fencingSequence,
            UUID ownerSessionId,
            UUID acquisitionId,
            long issuedAtRedisMillis,
            long expiresAtRedisMillis,
            long observedRedisTimeMillis
        ) {
            return new VerificationContext(
                identity,
                datasetHash,
                incarnation,
                boundControlCounter,
                State.ACTIVE,
                revision,
                fencingSequence,
                ownerSessionId,
                acquisitionId,
                issuedAtRedisMillis,
                expiresAtRedisMillis,
                observedRedisTimeMillis
            );
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisCoordinatorLease)) {
            return false;
        }
        RedisCoordinatorLease that = (RedisCoordinatorLease) other;
        return boundControlCounter == that.boundControlCounter
            && revision == that.revision
            && fencingSequence == that.fencingSequence
            && issuedAtRedisMillis == that.issuedAtRedisMillis
            && expiresAtRedisMillis == that.expiresAtRedisMillis
            && identity.equals(that.identity)
            && datasetHash.equals(that.datasetHash)
            && incarnation.equals(that.incarnation)
            && state == that.state
            && Objects.equals(ownerSessionId, that.ownerSessionId)
            && Objects.equals(acquisitionId, that.acquisitionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            identity,
            datasetHash,
            incarnation,
            boundControlCounter,
            state,
            revision,
            fencingSequence,
            ownerSessionId,
            acquisitionId,
            issuedAtRedisMillis,
            expiresAtRedisMillis
        );
    }
}
