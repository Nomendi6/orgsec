package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;

/**
 * Sanitized failure of the canonical snapshot entry identity boundary.
 *
 * <p>The message contains only a stable diagnostic code and an allow-listed reason. Canonical
 * keys, payload bytes, decoded identities, and codec diagnostics are deliberately excluded.</p>
 */
final class RedisSnapshotEntryCorruptionException extends RedisProtocolException {

    static final String DIAGNOSTIC_CODE =
        "ORGSEC_STORAGE_REDIS_SNAPSHOT_ENTRY_CORRUPT";

    enum Reason {
        UNSUPPORTED_FAMILY,
        ENTRY_MISSING,
        KEY_INVALID,
        PAYLOAD_INVALID,
        PAYLOAD_IDENTITY_INVALID,
        KEY_PAYLOAD_IDENTITY_MISMATCH
    }

    private final Reason reason;

    RedisSnapshotEntryCorruptionException(Reason reason) {
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
