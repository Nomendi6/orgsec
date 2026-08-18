package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Inert structural assessment of one atomic bootstrap metadata observation.
 *
 * <p>No result authorizes bootstrap, restore, rebind, publication or lease use. The assessment
 * deliberately contains no transition target or wire-ready protocol value.</p>
 */
final class RedisBootstrapTripletAssessment {

    enum Kind {
        TRIPLET_ABSENT,
        PARTIAL,
        COHERENT_SAME_RUN_ID,
        COHERENT_DIFFERENT_RUN_ID,
        INCOHERENT
    }

    enum Reason {
        PARTIAL_TRIPLET,
        CONTROL_DATASET_MISMATCH,
        LEASE_DATASET_MISMATCH,
        LEASE_DATASET_HASH_MISMATCH,
        INCARNATION_MISMATCH,
        BOUND_CONTROL_COUNTER_MISMATCH,
        COUNTER_BEHIND_FENCING_SEQUENCE
    }

    private final Kind kind;
    private final Reason reason;

    private RedisBootstrapTripletAssessment(Kind kind, Reason reason) {
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        if ((kind == Kind.PARTIAL || kind == Kind.INCOHERENT) != (reason != null)) {
            throw new IllegalArgumentException(
                "only partial and incoherent assessments carry a reason"
            );
        }
        this.reason = reason;
    }

    static RedisBootstrapTripletAssessment tripletAbsent() {
        return new RedisBootstrapTripletAssessment(Kind.TRIPLET_ABSENT, null);
    }

    static RedisBootstrapTripletAssessment partial() {
        return new RedisBootstrapTripletAssessment(Kind.PARTIAL, Reason.PARTIAL_TRIPLET);
    }

    static RedisBootstrapTripletAssessment coherentSameRunId() {
        return new RedisBootstrapTripletAssessment(Kind.COHERENT_SAME_RUN_ID, null);
    }

    static RedisBootstrapTripletAssessment coherentDifferentRunId() {
        return new RedisBootstrapTripletAssessment(Kind.COHERENT_DIFFERENT_RUN_ID, null);
    }

    static RedisBootstrapTripletAssessment incoherent(Reason reason) {
        return new RedisBootstrapTripletAssessment(
            Kind.INCOHERENT,
            Objects.requireNonNull(reason, "reason must not be null")
        );
    }

    Kind kind() {
        return kind;
    }

    Optional<Reason> reason() {
        return Optional.ofNullable(reason);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisBootstrapTripletAssessment)) {
            return false;
        }
        RedisBootstrapTripletAssessment that = (RedisBootstrapTripletAssessment) other;
        return kind == that.kind && reason == that.reason;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, reason);
    }

    /** Only stable allow-listed structural codes are rendered. */
    @Override
    public String toString() {
        return "RedisBootstrapTripletAssessment{" +
            "kind=" + kind +
            ", reason=" + reason +
            '}';
    }
}
