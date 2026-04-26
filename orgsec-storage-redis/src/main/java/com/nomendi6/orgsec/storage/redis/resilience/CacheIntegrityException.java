package com.nomendi6.orgsec.storage.redis.resilience;

/**
 * Exception thrown when cache integrity check fails (hash mismatch).
 * <p>
 * This exception indicates potential cache poisoning or data corruption.
 * When this exception is thrown, the corrupted cache entry should be
 * evicted and data should be reloaded from the database.
 * </p>
 */
public class CacheIntegrityException extends RedisStorageException {

    private final String key;
    private final String expectedHash;
    private final String actualHash;

    /**
     * Constructs a new cache integrity exception.
     *
     * @param message      the detail message
     * @param key          the cache key that failed integrity check
     * @param expectedHash the expected hash value
     * @param actualHash   the actual hash value found
     */
    public CacheIntegrityException(String message, String key, String expectedHash, String actualHash) {
        super(message);
        this.key = key;
        this.expectedHash = expectedHash;
        this.actualHash = actualHash;
    }

    /**
     * Returns the cache key that failed integrity check.
     *
     * @return the cache key
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the expected hash value.
     *
     * @return the expected hash
     */
    public String getExpectedHash() {
        return expectedHash;
    }

    /**
     * Returns the actual hash value found.
     *
     * @return the actual hash
     */
    public String getActualHash() {
        return actualHash;
    }

    @Override
    public String toString() {
        return String.format("CacheIntegrityException: %s [key=%s, expected=%s, actual=%s]",
            getMessage(), key, expectedHash, actualHash);
    }
}
