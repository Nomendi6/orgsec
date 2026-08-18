package com.nomendi6.orgsec.storage.redis.resilience;

import com.nomendi6.orgsec.storage.StorageNotReadyException;

/**
 * Raised when a write or invalidation is attempted before a verified Redis snapshot is READY.
 */
public class RedisStorageNotReadyException extends StorageNotReadyException {

    /** Stable diagnostic code for operational checks and generated applications. */
    public static final String DIAGNOSTIC_CODE = "ORGSEC_STORAGE_REDIS_NOT_READY";

    /**
     * Creates a fail-closed not-ready error for one operation.
     *
     * @param operation attempted storage operation
     */
    public RedisStorageNotReadyException(String operation) {
        super(DIAGNOSTIC_CODE + ": Redis authorization snapshot is not READY; rejected " + operation + ".");
    }
}
