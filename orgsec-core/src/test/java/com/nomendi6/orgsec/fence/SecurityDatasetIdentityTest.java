package com.nomendi6.orgsec.fence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityDatasetIdentityTest {

    @Test
    void usesDatasetAndProtocolForEquality() {
        SecurityDatasetIdentity identity = identity("billing-security", 1);
        SecurityDatasetIdentity equalIdentity = identity("billing-security", 1);

        assertThat(identity.getSecurityDatasetId()).isEqualTo("billing-security");
        assertThat(identity.getProtocolVersion()).isEqualTo(1);
        assertThat(identity).isEqualTo(equalIdentity).hasSameHashCodeAs(equalIdentity);
        assertThat(identity).isNotEqualTo(identity("other-security", 1));
        assertThat(identity).isNotEqualTo(identity("billing-security", 2));
    }

    @Test
    void rejectsInvalidIdentityComponents() {
        assertThatThrownBy(() -> identity(" ", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("securityDatasetId");
        assertThatThrownBy(() -> identity("dataset", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("protocolVersion");
    }

    @Test
    void diagnosticStringContainsOnlyTheIdentityComponents() {
        SecurityDatasetIdentity identity = identity("dataset", 1);

        assertThat(identity.toString())
            .contains("dataset", "protocolVersion=1");
    }

    private SecurityDatasetIdentity identity(String datasetId, int protocolVersion) {
        return new SecurityDatasetIdentity(datasetId, protocolVersion);
    }
}
