package com.nomendi6.orgsec.storage.redis.protocol;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * Streaming digest and logical-byte accumulator for one already sorted family.
 *
 * <p>An empty family accounts zero bytes because Redis does not retain empty HASH or ZSET keys.
 * Exact data and index key-name costs are added once, with the first entry.</p>
 */
final class RedisSnapshotFamilyAccumulator {

    private static final String ENTRY_DOMAIN = "orgsec:redis:snapshot:v1:entry";
    private static final String FAMILY_DOMAIN = "orgsec:redis:snapshot:v1:family";

    private final RedisSnapshotFamilyCode family;
    private final MessageDigest familyDigest;
    private final long familyBaseLogicalBytes;

    private byte[] previousKey;
    private long entryCount;
    private long logicalBytes;
    private boolean finished;

    RedisSnapshotFamilyAccumulator(
        RedisSnapshotFamilyCode family,
        String dataKey,
        String indexKey
    ) {
        this.family = Objects.requireNonNull(family, "family must not be null");
        this.familyBaseLogicalBytes = RedisSnapshotLogicalBytes.familyBase(dataKey, indexKey);
        this.familyDigest = RedisSnapshotDigestSupport.sha256(FAMILY_DOMAIN);
        RedisSnapshotDigestSupport.updateFamilyCode(familyDigest, family);
    }

    void add(RedisCanonicalEntry entry) {
        requireOpen();
        Objects.requireNonNull(entry, "entry must not be null");
        byte[] key = entry.canonicalKey();
        if (previousKey != null && Arrays.compareUnsigned(previousKey, key) >= 0) {
            throw new IllegalArgumentException(
                "canonical entry keys must be strictly increasing in unsigned byte order"
            );
        }

        long nextLogicalBytes = logicalBytes;
        if (entryCount == 0) {
            nextLogicalBytes = RedisSnapshotLogicalBytes.addExact(
                nextLogicalBytes,
                familyBaseLogicalBytes
            );
        }
        nextLogicalBytes = RedisSnapshotLogicalBytes.addExact(
            nextLogicalBytes,
            RedisSnapshotLogicalBytes.entry(entry)
        );
        long nextEntryCount = Math.addExact(entryCount, 1L);
        RedisSnapshotDigestSupport.updateFrame(familyDigest, entryDigest(family, entry));
        logicalBytes = nextLogicalBytes;
        entryCount = nextEntryCount;
        previousKey = key;
    }

    RedisSnapshotFamilyDigest finish() {
        requireOpen();
        finished = true;
        RedisSnapshotDigestSupport.updateLong(familyDigest, entryCount);
        return new RedisSnapshotFamilyDigest(
            family,
            entryCount,
            logicalBytes,
            familyDigest.digest()
        );
    }

    long currentLogicalBytes() {
        return logicalBytes;
    }

    static byte[] entryDigest(
        RedisSnapshotFamilyCode family,
        RedisCanonicalEntry entry
    ) {
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        MessageDigest digest = RedisSnapshotDigestSupport.sha256(ENTRY_DOMAIN);
        RedisSnapshotDigestSupport.updateFamilyCode(digest, family);
        RedisSnapshotDigestSupport.updateFrame(digest, entry.canonicalKey());
        RedisSnapshotDigestSupport.updateFrame(digest, entry.canonicalPayload());
        return digest.digest();
    }

    private void requireOpen() {
        if (finished) {
            throw new IllegalStateException("family accumulator is already finished");
        }
    }
}
