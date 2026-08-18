package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.storage.redis.resilience.RedisStorageException;

/**
 * Base type for failures of the strict Redis snapshot protocol.
 */
public abstract class RedisProtocolException extends RedisStorageException {

    /**
     * Creates a protocol failure.
     *
     * @param message actionable failure detail
     */
    protected RedisProtocolException(String message) {
        super(message);
    }

    /**
     * Creates a protocol failure caused by a lower-level operation.
     *
     * @param message actionable failure detail
     * @param cause lower-level cause
     */
    protected RedisProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
