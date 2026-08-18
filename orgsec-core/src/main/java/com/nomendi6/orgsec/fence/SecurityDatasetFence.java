package com.nomendi6.orgsec.fence;

import java.util.Objects;

/**
 * Immutable source-database fence value for a security dataset.
 */
public final class SecurityDatasetFence {

    private final SecurityDatasetIdentity identity;
    private final long securityContentVersion;

    /**
     * Creates a source-database fence value.
     *
     * @param identity exact dataset and protocol identity stored with the source data
     * @param securityContentVersion monotonic source-data content version
     */
    public SecurityDatasetFence(
        SecurityDatasetIdentity identity,
        long securityContentVersion
    ) {
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        if (securityContentVersion < 0) {
            throw new IllegalArgumentException("securityContentVersion must not be negative");
        }
        this.securityContentVersion = securityContentVersion;
    }

    public SecurityDatasetIdentity getIdentity() {
        return identity;
    }

    public long getSecurityContentVersion() {
        return securityContentVersion;
    }

    /**
     * Returns the next monotonic content fence for the same dataset identity.
     *
     * @return a fence whose content version is exactly one greater
     * @throws ArithmeticException when the current version cannot be incremented
     */
    public SecurityDatasetFence nextContentVersion() {
        if (securityContentVersion == Long.MAX_VALUE) {
            throw new ArithmeticException("securityContentVersion overflow");
        }
        return new SecurityDatasetFence(identity, securityContentVersion + 1);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SecurityDatasetFence)) {
            return false;
        }
        SecurityDatasetFence that = (SecurityDatasetFence) other;
        return securityContentVersion == that.securityContentVersion
            && identity.equals(that.identity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, securityContentVersion);
    }

    @Override
    public String toString() {
        return "SecurityDatasetFence{" +
            "identity=" + identity +
            ", securityContentVersion=" + securityContentVersion +
            '}';
    }
}
