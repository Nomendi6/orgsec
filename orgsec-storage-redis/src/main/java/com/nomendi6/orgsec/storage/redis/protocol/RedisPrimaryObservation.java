package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identity and safety configuration observed atomically with a Redis control record.
 */
final class RedisPrimaryObservation {

    private static final Pattern CANONICAL_RUN_ID = Pattern.compile("[0-9a-f]{40}");

    private final String runId;
    private final String role;
    private final boolean clusterEnabled;
    private final String maxmemoryPolicy;

    RedisPrimaryObservation(
        String runId,
        String role,
        boolean clusterEnabled,
        String maxmemoryPolicy
    ) {
        if (runId == null || !CANONICAL_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("runId must be a canonical lower-case Redis run ID");
        }
        if (!"master".equals(role)) {
            throw new IllegalArgumentException("role must be master");
        }
        if (clusterEnabled) {
            throw new IllegalArgumentException("clusterEnabled must be false");
        }
        if (!"noeviction".equals(maxmemoryPolicy)) {
            throw new IllegalArgumentException("maxmemoryPolicy must be noeviction");
        }
        this.runId = runId;
        this.role = role;
        this.clusterEnabled = false;
        this.maxmemoryPolicy = maxmemoryPolicy;
    }

    String runId() {
        return runId;
    }

    String role() {
        return role;
    }

    boolean clusterEnabled() {
        return clusterEnabled;
    }

    String maxmemoryPolicy() {
        return maxmemoryPolicy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisPrimaryObservation)) {
            return false;
        }
        RedisPrimaryObservation that = (RedisPrimaryObservation) other;
        return clusterEnabled == that.clusterEnabled
            && runId.equals(that.runId)
            && role.equals(that.role)
            && maxmemoryPolicy.equals(that.maxmemoryPolicy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, role, clusterEnabled, maxmemoryPolicy);
    }

    @Override
    public String toString() {
        return "RedisPrimaryObservation{" +
            "runId='" + runId + '\'' +
            ", role='" + role + '\'' +
            ", clusterEnabled=" + clusterEnabled +
            ", maxmemoryPolicy='" + maxmemoryPolicy + '\'' +
            '}';
    }
}
