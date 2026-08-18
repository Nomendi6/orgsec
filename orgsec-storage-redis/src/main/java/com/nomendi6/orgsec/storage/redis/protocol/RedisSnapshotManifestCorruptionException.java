package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;

/**
 * Raised when snapshot manifest wire fields are malformed, incomplete, or non-canonical.
 *
 * <p>Diagnostics contain only stable allow-listed reason codes. Raw wire field names and values
 * are never copied into the message.</p>
 */
final class RedisSnapshotManifestCorruptionException extends RedisProtocolException {

    /** Stable diagnostic code for health, logs, and operational automation. */
    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_MANIFEST_CORRUPT";

    enum Reason {
        NULL_MAP,
        FIELD_SET_MISMATCH,
        FIELD_VALUE_EMPTY,
        FIELD_UTF8_INVALID,
        FIELD_VALUE_TOO_LARGE,
        DATASET_ID_INVALID,
        PROTOCOL_VERSION_INVALID,
        NUMBER_INVALID,
        DIGEST_INVALID,
        SNAPSHOT_ID_INVALID,
        IDENTITY_INVALID
    }

    private final Reason reason;

    RedisSnapshotManifestCorruptionException(Reason reason) {
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

/**
 * Raised when a trusted local manifest cannot be represented by the bounded wire contract.
 */
final class RedisSnapshotManifestEncodingException extends RedisProtocolException {

    /** Stable diagnostic code for health, logs, and operational automation. */
    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_MANIFEST_ENCODING_INVALID";

    enum Reason {
        FIELD_VALUE_EMPTY,
        FIELD_UTF8_INVALID,
        FIELD_VALUE_TOO_LARGE
    }

    private final Reason reason;

    RedisSnapshotManifestEncodingException(Reason reason) {
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

/**
 * Raised when syntactically valid manifest fields do not match trusted contextual evidence.
 */
final class RedisSnapshotManifestVerificationException extends RedisProtocolException {

    /** Stable diagnostic code for health, logs, and operational automation. */
    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_MANIFEST_UNVERIFIED";

    enum Reason {
        REQUESTED_SNAPSHOT_ID_INVALID,
        DATASET_ID_MISMATCH,
        PROTOCOL_VERSION_MISMATCH,
        SECURITY_CONTENT_VERSION_MISMATCH,
        SNAPSHOT_ID_MISMATCH,
        CONTENT_DIGEST_MISMATCH,
        PERSONS_COUNT_MISMATCH,
        ORGANIZATIONS_COUNT_MISMATCH,
        PARTY_ROLES_COUNT_MISMATCH,
        POSITION_ROLES_COUNT_MISMATCH,
        ROLES_COUNT_MISMATCH,
        PRIVILEGES_COUNT_MISMATCH,
        ACCOUNTED_BYTES_MISMATCH
    }

    private final Reason reason;

    RedisSnapshotManifestVerificationException(Reason reason) {
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
