package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable digest and accounting result for one canonical snapshot family.
 */
final class RedisSnapshotFamilyDigest {

    private static final int SHA_256_BYTES = 32;

    private final RedisSnapshotFamilyCode family;
    private final long entryCount;
    private final long logicalBytes;
    private final byte[] digest;

    RedisSnapshotFamilyDigest(
        RedisSnapshotFamilyCode family,
        long entryCount,
        long logicalBytes,
        byte[] digest
    ) {
        this.family = Objects.requireNonNull(family, "family must not be null");
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount must not be negative");
        }
        if (logicalBytes < 0) {
            throw new IllegalArgumentException("logicalBytes must not be negative");
        }
        if ((entryCount == 0) != (logicalBytes == 0)) {
            throw new IllegalArgumentException(
                "entryCount and logicalBytes must either both be zero or both be positive"
            );
        }
        Objects.requireNonNull(digest, "digest must not be null");
        if (digest.length != SHA_256_BYTES) {
            throw new IllegalArgumentException("digest must contain exactly 32 bytes");
        }
        this.entryCount = entryCount;
        this.logicalBytes = logicalBytes;
        this.digest = digest.clone();
    }

    RedisSnapshotFamilyCode family() {
        return family;
    }

    long entryCount() {
        return entryCount;
    }

    long logicalBytes() {
        return logicalBytes;
    }

    byte[] digest() {
        return digest.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisSnapshotFamilyDigest)) {
            return false;
        }
        RedisSnapshotFamilyDigest that = (RedisSnapshotFamilyDigest) other;
        return entryCount == that.entryCount
            && logicalBytes == that.logicalBytes
            && family == that.family
            && Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(family, entryCount, logicalBytes);
        return 31 * result + Arrays.hashCode(digest);
    }
}
