package com.nomendi6.orgsec.fence;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable compatibility identity of one security dataset.
 *
 * <p>The identity contains only release/schema compatibility data. Runtime publication data such
 * as the Redis incarnation, active snapshot and publication counter deliberately do not belong
 * here.</p>
 */
public final class SecurityDatasetIdentity {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final String securityDatasetId;
    private final int protocolVersion;
    private final long compatibilityEpoch;
    private final String compatibilityFingerprint;
    private final long privilegeCatalogVersion;
    private final String privilegeCatalogDigest;

    /**
     * Creates a security dataset identity.
     *
     * @param securityDatasetId stable, deployment-unique dataset identifier
     * @param protocolVersion storage protocol version, greater than zero
     * @param compatibilityEpoch monotonic compatibility epoch
     * @param compatibilityFingerprint SHA-256 of the canonical compatibility model
     * @param privilegeCatalogVersion monotonic privilege catalog version
     * @param privilegeCatalogDigest SHA-256 of the canonical privilege catalog
     */
    public SecurityDatasetIdentity(
        String securityDatasetId,
        int protocolVersion,
        long compatibilityEpoch,
        String compatibilityFingerprint,
        long privilegeCatalogVersion,
        String privilegeCatalogDigest
    ) {
        this.securityDatasetId = requireText("securityDatasetId", securityDatasetId);
        this.protocolVersion = requirePositive("protocolVersion", protocolVersion);
        this.compatibilityEpoch = requireNonNegative("compatibilityEpoch", compatibilityEpoch);
        this.compatibilityFingerprint = requireSha256(
            "compatibilityFingerprint",
            compatibilityFingerprint
        );
        this.privilegeCatalogVersion = requireNonNegative(
            "privilegeCatalogVersion",
            privilegeCatalogVersion
        );
        this.privilegeCatalogDigest = requireSha256(
            "privilegeCatalogDigest",
            privilegeCatalogDigest
        );
    }

    public String getSecurityDatasetId() {
        return securityDatasetId;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public long getCompatibilityEpoch() {
        return compatibilityEpoch;
    }

    public String getCompatibilityFingerprint() {
        return compatibilityFingerprint;
    }

    public long getPrivilegeCatalogVersion() {
        return privilegeCatalogVersion;
    }

    public String getPrivilegeCatalogDigest() {
        return privilegeCatalogDigest;
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
            && compatibilityEpoch == that.compatibilityEpoch
            && privilegeCatalogVersion == that.privilegeCatalogVersion
            && securityDatasetId.equals(that.securityDatasetId)
            && compatibilityFingerprint.equals(that.compatibilityFingerprint)
            && privilegeCatalogDigest.equals(that.privilegeCatalogDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            securityDatasetId,
            protocolVersion,
            compatibilityEpoch,
            compatibilityFingerprint,
            privilegeCatalogVersion,
            privilegeCatalogDigest
        );
    }

    @Override
    public String toString() {
        return "SecurityDatasetIdentity{" +
            "securityDatasetId='" + securityDatasetId + '\'' +
            ", protocolVersion=" + protocolVersion +
            ", compatibilityEpoch=" + compatibilityEpoch +
            ", privilegeCatalogVersion=" + privilegeCatalogVersion +
            '}';
    }

    static String requireText(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String requireSha256(String name, String value) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a 64-character hexadecimal SHA-256");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static int requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static long requireNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
