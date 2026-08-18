package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;

/**
 * Sanitized failure of a tokenized strict snapshot read.
 *
 * <p>The message contains only a stable diagnostic code and an allow-listed reason. Redis keys,
 * hash fields, payloads and peer-provided status text are never copied into diagnostics.</p>
 */
final class RedisSnapshotReadException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_SNAPSHOT_READ";

    enum Reason {
        GENERATION_CHANGED,
        CONTROL_CORRUPT,
        MANIFEST_CORRUPT,
        MANIFEST_CHANGED,
        SNAPSHOT_LIMIT_EXCEEDED,
        FAMILY_CARDINALITY_MISMATCH,
        INDEX_ENTRY_INVALID,
        PAGE_ENTRY_INVALID,
        PAGE_LIMIT_EXCEEDED,
        PRIMARY_CONFIGURATION_INVALID,
        RESPONSE_CORRUPT
    }

    private final Reason reason;

    RedisSnapshotReadException(Reason reason) {
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
