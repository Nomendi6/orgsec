package com.nomendi6.orgsec.fence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityFenceValueTest {

    private static final String SHA = "0123456789abcdef".repeat(4);

    @Test
    void sourceFenceIsImmutableValueWithNonNegativeContentVersion() {
        SecurityDatasetIdentity identity = identity();
        SecurityDatasetFence fence = new SecurityDatasetFence(identity, 7);

        assertThat(fence.getIdentity()).isSameAs(identity);
        assertThat(fence.getSecurityContentVersion()).isEqualTo(7);
        assertThat(fence).isEqualTo(new SecurityDatasetFence(identity(), 7));
        assertThat(fence.toString()).contains("securityContentVersion=7");

        assertThatThrownBy(() -> new SecurityDatasetFence(identity, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("securityContentVersion");
        assertThatNullPointerException()
            .isThrownBy(() -> new SecurityDatasetFence(null, 0))
            .withMessageContaining("identity");
    }

    @Test
    void releaseFenceKeepsReceiptButRedactsSignatureAndDigestsFromDiagnostics() {
        SecurityReleaseFence fence = new SecurityReleaseFence(
            identity(),
            "release-key-2026",
            "opaque-signature",
            SHA.toUpperCase()
        );

        assertThat(fence.getIdentity()).isEqualTo(identity());
        assertThat(fence.getVerificationKeyId()).isEqualTo("release-key-2026");
        assertThat(fence.getSignature()).isEqualTo("opaque-signature");
        assertThat(fence.getRecordSha256()).isEqualTo(SHA);
        assertThat(fence.toString())
            .contains("release-key-2026")
            .doesNotContain("opaque-signature", SHA);

        assertThatThrownBy(() -> new SecurityReleaseFence(identity(), "key", " ", SHA))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("signature");
        assertThatThrownBy(() -> new SecurityReleaseFence(identity(), "key", "signature", "bad"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recordSha256");
    }

    private SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity("dataset", 1, 2, SHA, 3, SHA);
    }
}
