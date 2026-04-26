package com.nomendi6.orgsec.storage.redis.resilience;

/**
 * Base exception for all Redis storage operations.
 * <p>
 * This is an unchecked exception that wraps lower-level exceptions
 * (Redis connection failures, serialization errors, etc.) into a
 * unified exception hierarchy for Redis storage operations.
 * </p>
 */
public class RedisStorageException extends RuntimeException {

    /**
     * Constructs a new Redis storage exception with the specified detail message.
     *
     * @param message the detail message
     */
    public RedisStorageException(String message) {
        super(message);
    }

    /**
     * Constructs a new Redis storage exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause of this exception
     */
    public RedisStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new Redis storage exception with the specified cause.
     *
     * @param cause the cause of this exception
     */
    public RedisStorageException(Throwable cause) {
        super(cause);
    }
}
