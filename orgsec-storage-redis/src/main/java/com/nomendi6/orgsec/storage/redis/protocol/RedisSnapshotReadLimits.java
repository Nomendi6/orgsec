package com.nomendi6.orgsec.storage.redis.protocol;

/**
 * Immutable admission and response limits for strict snapshot reads.
 *
 * <p>The snapshot-wide limits are explicit operator-controlled deployment inputs and must never
 * be inferred from untrusted manifest values. Page limits are also enforced inside Lua before
 * Redis returns keys or payloads, and are checked again by the Java response decoder.</p>
 */
final class RedisSnapshotReadLimits {

    /** Keeps {@code unpack(keys)} and one EVAL response below a deliberately small fixed fan-out. */
    private static final int MAX_PAGE_ENTRIES = 1024;
    /** Absolute implementation ceiling for one bounded EVAL response. */
    private static final int MAX_PAGE_TOTAL_BYTES = 64 * 1024 * 1024;
    /** Largest integer represented exactly by the Redis Lua 5.1 number type. */
    private static final long MAX_LUA_EXACT_INTEGER = 9_007_199_254_740_991L;

    private final long maxEntriesPerFamily;
    private final long maxTotalEntries;
    private final long maxAccountedBytes;
    private final int pageEntries;
    private final int pageKeyBytes;
    private final int pagePayloadBytes;
    private final int pageTotalBytes;

    RedisSnapshotReadLimits(
        long maxEntriesPerFamily,
        long maxTotalEntries,
        long maxAccountedBytes,
        int pageEntries,
        int pageKeyBytes,
        int pagePayloadBytes,
        int pageTotalBytes
    ) {
        this.maxEntriesPerFamily = requireRange(
            maxEntriesPerFamily,
            1,
            MAX_LUA_EXACT_INTEGER,
            "maxEntriesPerFamily"
        );
        this.maxTotalEntries = requireRange(
            maxTotalEntries,
            1,
            MAX_LUA_EXACT_INTEGER,
            "maxTotalEntries"
        );
        this.maxAccountedBytes = requirePositive(maxAccountedBytes, "maxAccountedBytes");
        this.pageEntries = requireRange(
            pageEntries,
            1,
            MAX_PAGE_ENTRIES,
            "pageEntries"
        );
        this.pageKeyBytes = requireRange(
            pageKeyBytes,
            1,
            MAX_PAGE_TOTAL_BYTES,
            "pageKeyBytes"
        );
        this.pagePayloadBytes = requireRange(
            pagePayloadBytes,
            1,
            MAX_PAGE_TOTAL_BYTES,
            "pagePayloadBytes"
        );
        this.pageTotalBytes = requireRange(
            pageTotalBytes,
            1,
            MAX_PAGE_TOTAL_BYTES,
            "pageTotalBytes"
        );
        if (pageKeyBytes > pageTotalBytes) {
            throw new IllegalArgumentException("pageKeyBytes must not exceed pageTotalBytes");
        }
        if (pagePayloadBytes > pageTotalBytes) {
            throw new IllegalArgumentException("pagePayloadBytes must not exceed pageTotalBytes");
        }
    }

    /**
     * Conservative values intended only for an unwired scaffold and tests.
     *
     * <p>Production configuration must replace the three snapshot-wide values with
     * operator-approved dataset limits. Payload size matches the largest canonical person payload
     * currently supported by the protocol codec.</p>
     */
    static RedisSnapshotReadLimits scaffoldDefaults() {
        return new RedisSnapshotReadLimits(
            1_000_000L,
            5_000_000L,
            8L * 1024L * 1024L * 1024L,
            128,
            4096,
            RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES,
            8 * 1024 * 1024
        );
    }

    long maxEntriesPerFamily() {
        return maxEntriesPerFamily;
    }

    long maxTotalEntries() {
        return maxTotalEntries;
    }

    long maxAccountedBytes() {
        return maxAccountedBytes;
    }

    int pageEntries() {
        return pageEntries;
    }

    int pageKeyBytes() {
        return pageKeyBytes;
    }

    int pagePayloadBytes() {
        return pagePayloadBytes;
    }

    int pageTotalBytes() {
        return pageTotalBytes;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long requireRange(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum
            );
        }
        return value;
    }

    private static int requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum
            );
        }
        return value;
    }
}
