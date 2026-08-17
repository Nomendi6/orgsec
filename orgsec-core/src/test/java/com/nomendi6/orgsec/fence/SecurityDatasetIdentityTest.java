package com.nomendi6.orgsec.fence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityDatasetIdentityTest {

    private static final String UPPER_SHA = "ABCDEF0123456789".repeat(4);
    private static final String LOWER_SHA = UPPER_SHA.toLowerCase();

    @Test
    void canonicalizesDigestsAndUsesEveryIdentityComponentForEquality() {
        SecurityDatasetIdentity identity = new SecurityDatasetIdentity(
            "billing-security",
            1,
            2,
            UPPER_SHA,
            3,
            UPPER_SHA
        );
        SecurityDatasetIdentity equalIdentity = new SecurityDatasetIdentity(
            "billing-security",
            1,
            2,
            LOWER_SHA,
            3,
            LOWER_SHA
        );
        SecurityDatasetIdentity differentCatalog = new SecurityDatasetIdentity(
            "billing-security",
            1,
            2,
            LOWER_SHA,
            4,
            LOWER_SHA
        );

        assertThat(identity.getSecurityDatasetId()).isEqualTo("billing-security");
        assertThat(identity.getProtocolVersion()).isEqualTo(1);
        assertThat(identity.getCompatibilityEpoch()).isEqualTo(2);
        assertThat(identity.getCompatibilityFingerprint()).isEqualTo(LOWER_SHA);
        assertThat(identity.getPrivilegeCatalogVersion()).isEqualTo(3);
        assertThat(identity.getPrivilegeCatalogDigest()).isEqualTo(LOWER_SHA);
        assertThat(identity).isEqualTo(equalIdentity).hasSameHashCodeAs(equalIdentity);
        assertThat(identity).isNotEqualTo(differentCatalog);
    }

    @Test
    void rejectsInvalidIdentityComponents() {
        assertThatThrownBy(() -> identity(" ", 1, 0, UPPER_SHA, 0, UPPER_SHA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("securityDatasetId");
        assertThatThrownBy(() -> identity("dataset", 0, 0, UPPER_SHA, 0, UPPER_SHA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("protocolVersion");
        assertThatThrownBy(() -> identity("dataset", 1, -1, UPPER_SHA, 0, UPPER_SHA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compatibilityEpoch");
        assertThatThrownBy(() -> identity("dataset", 1, 0, "not-a-sha", 0, UPPER_SHA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compatibilityFingerprint");
        assertThatThrownBy(() -> identity("dataset", 1, 0, UPPER_SHA, -1, UPPER_SHA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("privilegeCatalogVersion");
        assertThatThrownBy(() -> identity("dataset", 1, 0, UPPER_SHA, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("privilegeCatalogDigest");
    }

    @Test
    void doesNotPutDigestsInDiagnosticString() {
        SecurityDatasetIdentity identity = identity("dataset", 1, 2, UPPER_SHA, 3, UPPER_SHA);

        assertThat(identity.toString())
            .contains("dataset", "protocolVersion=1", "compatibilityEpoch=2")
            .doesNotContain(LOWER_SHA, UPPER_SHA);
    }

    private SecurityDatasetIdentity identity(
        String datasetId,
        int protocolVersion,
        long compatibilityEpoch,
        String fingerprint,
        long catalogVersion,
        String catalogDigest
    ) {
        return new SecurityDatasetIdentity(
            datasetId,
            protocolVersion,
            compatibilityEpoch,
            fingerprint,
            catalogVersion,
            catalogDigest
        );
    }
}
