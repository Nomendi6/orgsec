package com.nomendi6.orgsec.fence;

import java.util.Objects;

/**
 * Immutable identity of one security dataset and its storage protocol.
 *
 * <p>Security content, including the privilege catalog, belongs to the versioned snapshot. Runtime
 * publication data such as the Redis incarnation, active snapshot and publication counter also
 * deliberately do not belong here.</p>
 */
public final class SecurityDatasetIdentity {

    private final String securityDatasetId;
    private final int protocolVersion;

    /**
     * Creates a security dataset identity.
     *
     * @param securityDatasetId stable, deployment-unique dataset identifier
     * @param protocolVersion storage protocol version, greater than zero
     */
    public SecurityDatasetIdentity(
        String securityDatasetId,
        int protocolVersion
    ) {
        this.securityDatasetId = requireText("securityDatasetId", securityDatasetId);
        this.protocolVersion = requirePositive("protocolVersion", protocolVersion);
    }

    public String getSecurityDatasetId() {
        return securityDatasetId;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SecurityDatasetIdentity)) {
            return false;
        }
        SecurityDatasetIdentity that = (SecurityDatasetIdentity) other;
        return protocolVersion == that.protocolVersion
            && securityDatasetId.equals(that.securityDatasetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(securityDatasetId, protocolVersion);
    }

    @Override
    public String toString() {
        return "SecurityDatasetIdentity{" +
            "securityDatasetId='" + securityDatasetId + '\'' +
            ", protocolVersion=" + protocolVersion +
            '}';
    }

    static String requireText(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

}
