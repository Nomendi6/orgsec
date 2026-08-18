package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;

/** Raised when coordinator lease wire fields are malformed or non-canonical. */
final class RedisCoordinatorLeaseCorruptionException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_LEASE_CORRUPT";

    enum Reason {
        NULL_MAP,
        FIELD_SET_MISMATCH,
        FIELD_VALUE_EMPTY,
        FIELD_UTF8_INVALID,
        FIELD_VALUE_TOO_LARGE,
        SCHEMA_VERSION_INVALID,
        DATASET_ID_INVALID,
        PROTOCOL_VERSION_INVALID,
        NUMBER_INVALID,
        DIGEST_INVALID,
        PRIMARY_RUN_ID_INVALID,
        UUID_INVALID,
        STATE_INVALID,
        STATE_FIELDS_INVALID,
        TIME_RANGE_INVALID,
        IDENTITY_INVALID
    }

    private final Reason reason;

    RedisCoordinatorLeaseCorruptionException(Reason reason) {
        super(message(reason));
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return DIAGNOSTIC_CODE + ":" + Objects.requireNonNull(
            reason,
            "reason must not be null"
        ).name();
    }
}

/** Raised when a trusted coordinator lease cannot be represented by the wire contract. */
final class RedisCoordinatorLeaseEncodingException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_LEASE_ENCODING_INVALID";

    enum Reason {
        FIELD_VALUE_EMPTY,
        FIELD_UTF8_INVALID,
        FIELD_VALUE_TOO_LARGE
    }

    private final Reason reason;

    RedisCoordinatorLeaseEncodingException(Reason reason) {
        super(message(reason));
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return DIAGNOSTIC_CODE + ":" + Objects.requireNonNull(
            reason,
            "reason must not be null"
        ).name();
    }
}

/** Raised when canonical lease fields do not match authoritative runtime context. */
final class RedisCoordinatorLeaseVerificationException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_LEASE_UNVERIFIED";

    enum Reason {
        DATASET_ID_MISMATCH,
        DATASET_HASH_MISMATCH,
        PROTOCOL_VERSION_MISMATCH,
        PRIMARY_RUN_ID_MISMATCH,
        STORAGE_UUID_MISMATCH,
        BOUND_CONTROL_COUNTER_MISMATCH,
        STATE_MISMATCH,
        REVISION_MISMATCH,
        OWNER_SESSION_ID_MISMATCH,
        ACQUISITION_ID_MISMATCH,
        FENCING_SEQUENCE_MISMATCH,
        ISSUED_AT_REDIS_MILLIS_MISMATCH,
        EXPIRES_AT_REDIS_MILLIS_MISMATCH,
        LEASE_NOT_YET_VALID,
        LEASE_EXPIRED
    }

    private final Reason reason;

    RedisCoordinatorLeaseVerificationException(Reason reason) {
        super(message(reason));
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    Reason reason() {
        return reason;
    }

    private static String message(Reason reason) {
        return DIAGNOSTIC_CODE + ":" + Objects.requireNonNull(
            reason,
            "reason must not be null"
        ).name();
    }
}
