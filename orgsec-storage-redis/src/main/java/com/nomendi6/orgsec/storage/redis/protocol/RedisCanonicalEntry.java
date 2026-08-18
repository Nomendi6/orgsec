package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable canonical field key and payload bytes for one snapshot entry.
 */
final class RedisCanonicalEntry {

    private final byte[] canonicalKey;
    private final byte[] canonicalPayload;

    RedisCanonicalEntry(byte[] canonicalKey, byte[] canonicalPayload) {
        Objects.requireNonNull(canonicalKey, "canonicalKey must not be null");
        Objects.requireNonNull(canonicalPayload, "canonicalPayload must not be null");
        if (canonicalKey.length == 0) {
            throw new IllegalArgumentException("canonicalKey must not be empty");
        }
        if (canonicalPayload.length == 0) {
            throw new IllegalArgumentException("canonicalPayload must not be empty");
        }
        this.canonicalKey = canonicalKey.clone();
        this.canonicalPayload = canonicalPayload.clone();
    }

    byte[] canonicalKey() {
        return canonicalKey.clone();
    }

    byte[] canonicalPayload() {
        return canonicalPayload.clone();
    }

    int canonicalKeyLength() {
        return canonicalKey.length;
    }

    int canonicalPayloadLength() {
        return canonicalPayload.length;
    }

    int compareCanonicalKeyTo(RedisCanonicalEntry other) {
        return Arrays.compareUnsigned(canonicalKey, other.canonicalKey);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisCanonicalEntry)) {
            return false;
        }
        RedisCanonicalEntry that = (RedisCanonicalEntry) other;
        return Arrays.equals(canonicalKey, that.canonicalKey)
            && Arrays.equals(canonicalPayload, that.canonicalPayload);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(canonicalKey) + Arrays.hashCode(canonicalPayload);
    }
}
