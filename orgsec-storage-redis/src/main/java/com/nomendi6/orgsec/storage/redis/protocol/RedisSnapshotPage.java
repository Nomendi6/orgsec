package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;

import java.util.List;
import java.util.Objects;

/**
 * One immutable bounded ordinal page from an exact snapshot family.
 */
final class RedisSnapshotPage {

    private final RedisSnapshotFamily family;
    private final long offset;
    private final long nextOffset;
    private final List<RedisCanonicalEntry> entries;
    private final boolean done;

    RedisSnapshotPage(
        RedisSnapshotFamily family,
        long offset,
        long nextOffset,
        List<RedisCanonicalEntry> entries,
        boolean done
    ) {
        this.family = Objects.requireNonNull(family, "family must not be null");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (nextOffset < 0) {
            throw new IllegalArgumentException("nextOffset must not be negative");
        }
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));

        final long expectedNextOffset;
        try {
            expectedNextOffset = Math.addExact(offset, this.entries.size());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "offset plus page size must not overflow",
                exception
            );
        }
        if (nextOffset != expectedNextOffset) {
            throw new IllegalArgumentException(
                "nextOffset must equal offset plus the number of entries"
            );
        }
        if (this.entries.isEmpty() && !done) {
            throw new IllegalArgumentException("an empty page must be done");
        }

        this.offset = offset;
        this.nextOffset = nextOffset;
        this.done = done;
    }

    RedisSnapshotFamily family() {
        return family;
    }

    long offset() {
        return offset;
    }

    long nextOffset() {
        return nextOffset;
    }

    List<RedisCanonicalEntry> entries() {
        return entries;
    }

    boolean done() {
        return done;
    }
}
