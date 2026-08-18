package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate digest accumulator requiring exactly the six protocol families in canonical order.
 */
final class RedisSnapshotContentAccumulator {

    private final List<RedisSnapshotFamilyDigest> familyDigests = new ArrayList<>(6);

    private int nextFamilyIndex;
    private long entryCount;
    private long logicalBytes;
    private boolean finished;

    void add(RedisSnapshotFamilyDigest familyDigest) {
        requireOpen();
        Objects.requireNonNull(familyDigest, "familyDigest must not be null");
        if (nextFamilyIndex == RedisSnapshotFamilyCode.familyCount()) {
            throw new IllegalStateException("all six snapshot families have already been added");
        }

        RedisSnapshotFamilyCode expected =
            RedisSnapshotFamilyCode.atCanonicalIndex(nextFamilyIndex);
        if (familyDigest.family() != expected) {
            throw new IllegalArgumentException(
                "expected family " + expected + " but received " + familyDigest.family()
            );
        }

        long nextEntryCount = Math.addExact(entryCount, familyDigest.entryCount());
        long nextLogicalBytes = RedisSnapshotLogicalBytes.addExact(
            logicalBytes,
            familyDigest.logicalBytes()
        );
        entryCount = nextEntryCount;
        logicalBytes = nextLogicalBytes;
        familyDigests.add(familyDigest);
        nextFamilyIndex++;
    }

    RedisSnapshotContentDigest finish() {
        requireOpen();
        if (nextFamilyIndex != RedisSnapshotFamilyCode.familyCount()) {
            RedisSnapshotFamilyCode missing =
                RedisSnapshotFamilyCode.atCanonicalIndex(nextFamilyIndex);
            throw new IllegalStateException("missing snapshot family " + missing);
        }
        finished = true;
        return new RedisSnapshotContentDigest(
            familyDigests.get(0),
            familyDigests.get(1),
            familyDigests.get(2),
            familyDigests.get(3),
            familyDigests.get(4),
            familyDigests.get(5)
        );
    }

    private void requireOpen() {
        if (finished) {
            throw new IllegalStateException("content accumulator is already finished");
        }
    }
}
