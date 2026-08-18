package com.nomendi6.orgsec.storage.redis.protocol;

/**
 * Explicit in-process bounds for one staged Redis snapshot append.
 *
 * <p>The operator supplies these limits explicitly for the deployed dataset. They are deliberately
 * not inferred from heap size, Redis configuration or untrusted snapshot values. The batch-byte
 * limit counts the exact canonical key and payload bytes submitted to one Lua invocation; Redis
 * allocator sizing and quota enforcement remain operator responsibilities.</p>
 */
final class RedisSnapshotWriteLimits {

    private final int maxEntries;
    private final int maxKeyBytes;
    private final int maxPayloadBytes;
    private final long maxBatchBytes;

    RedisSnapshotWriteLimits(
        int maxEntries,
        int maxKeyBytes,
        int maxPayloadBytes,
        long maxBatchBytes
    ) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxKeyBytes <= 0) {
            throw new IllegalArgumentException("maxKeyBytes must be positive");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        if (maxBatchBytes <= 0) {
            throw new IllegalArgumentException("maxBatchBytes must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxKeyBytes = maxKeyBytes;
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxBatchBytes = maxBatchBytes;
    }

    int maxEntries() {
        return maxEntries;
    }

    int maxKeyBytes() {
        return maxKeyBytes;
    }

    int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    long maxBatchBytes() {
        return maxBatchBytes;
    }
}
