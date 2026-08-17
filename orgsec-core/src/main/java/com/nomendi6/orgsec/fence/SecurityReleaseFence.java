package com.nomendi6.orgsec.fence;

import java.util.Objects;

/**
 * A cryptographically verified external release fence.
 *
 * <p>Instances are returned by {@link SecurityReleaseFenceProvider} only after the provider has
 * verified the external record and its signature against deployment trust material. The signature
 * remains opaque to the core module so providers can support an explicit algorithm policy without
 * leaking cryptography or resource-loading concerns into this API.</p>
 */
public final class SecurityReleaseFence {

    private final SecurityDatasetIdentity identity;
    private final String verificationKeyId;
    private final String signature;
    private final String recordSha256;

    /**
     * Creates a verified external release fence value.
     *
     * @param identity identity bound by the signed external record
     * @param verificationKeyId stable identifier of the verification key
     * @param signature provider-specific encoded signature
     * @param recordSha256 SHA-256 of the exact signed external record
     */
    public SecurityReleaseFence(
        SecurityDatasetIdentity identity,
        String verificationKeyId,
        String signature,
        String recordSha256
    ) {
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        this.verificationKeyId = SecurityDatasetIdentity.requireText(
            "verificationKeyId",
            verificationKeyId
        );
        this.signature = SecurityDatasetIdentity.requireText("signature", signature);
        this.recordSha256 = SecurityDatasetIdentity.requireSha256("recordSha256", recordSha256);
    }

    public SecurityDatasetIdentity getIdentity() {
        return identity;
    }

    public String getVerificationKeyId() {
        return verificationKeyId;
    }

    public String getSignature() {
        return signature;
    }

    public String getRecordSha256() {
        return recordSha256;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SecurityReleaseFence)) {
            return false;
        }
        SecurityReleaseFence that = (SecurityReleaseFence) other;
        return identity.equals(that.identity)
            && verificationKeyId.equals(that.verificationKeyId)
            && signature.equals(that.signature)
            && recordSha256.equals(that.recordSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identity, verificationKeyId, signature, recordSha256);
    }

    @Override
    public String toString() {
        return "SecurityReleaseFence{" +
            "identity=" + identity +
            ", verificationKeyId='" + verificationKeyId + '\'' +
            '}';
    }
}
