package com.nomendi6.orgsec.storage.redis.preload;

import java.time.LocalDateTime;

/**
 * Statistics for cache warmup operation.
 * <p>
 * Contains information about the number of entities loaded and
 * the time taken for the warmup process.
 * </p>
 */
public class WarmupStats {

    private final int personCount;
    private final int organizationCount;
    private final int roleCount;
    private final long durationMs;
    private final LocalDateTime timestamp;

    /**
     * Constructs warmup statistics.
     *
     * @param personCount       number of persons loaded
     * @param organizationCount number of organizations loaded
     * @param roleCount         number of roles loaded
     * @param durationMs        duration of warmup in milliseconds
     * @param timestamp         timestamp when warmup completed
     */
    public WarmupStats(
        int personCount,
        int organizationCount,
        int roleCount,
        long durationMs,
        LocalDateTime timestamp
    ) {
        this.personCount = personCount;
        this.organizationCount = organizationCount;
        this.roleCount = roleCount;
        this.durationMs = durationMs;
        this.timestamp = timestamp;
    }

    /**
     * Returns the number of persons loaded.
     *
     * @return person count
     */
    public int getPersonCount() {
        return personCount;
    }

    /**
     * Returns the number of organizations loaded.
     *
     * @return organization count
     */
    public int getOrganizationCount() {
        return organizationCount;
    }

    /**
     * Returns the number of roles loaded.
     *
     * @return role count
     */
    public int getRoleCount() {
        return roleCount;
    }

    /**
     * Returns the total number of entities loaded.
     *
     * @return total count
     */
    public int getTotalCount() {
        return personCount + organizationCount + roleCount;
    }

    /**
     * Returns the duration in milliseconds.
     *
     * @return duration in ms
     */
    public long getDurationMs() {
        return durationMs;
    }

    /**
     * Returns the duration in seconds.
     *
     * @return duration in seconds
     */
    public double getDurationSeconds() {
        return durationMs / 1000.0;
    }

    /**
     * Returns the timestamp when warmup completed.
     *
     * @return the timestamp
     */
    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format(
            "WarmupStats{persons=%d, organizations=%d, roles=%d, total=%d, duration=%dms (%.2fs), timestamp=%s}",
            personCount, organizationCount, roleCount, getTotalCount(),
            durationMs, getDurationSeconds(), timestamp
        );
    }
}
