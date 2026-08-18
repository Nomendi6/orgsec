package com.nomendi6.orgsec.storage;

/**
 * Base exception for a storage mutation rejected because the authoritative storage view is not
 * ready.
 *
 * <p>Reads may map unavailability to a fail-closed missing result. Mutations must propagate this
 * exception so callers never mistake a rejected write or invalidation for success.</p>
 */
public class StorageNotReadyException extends RuntimeException {

    public StorageNotReadyException(String message) {
        super(message);
    }

    public StorageNotReadyException(String message, Throwable cause) {
        super(message, cause);
    }
}
