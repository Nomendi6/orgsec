package com.nomendi6.orgsec.fence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityFenceValueTest {

    @Test
    void sourceFenceIsImmutableValueWithNonNegativeContentVersion() {
        SecurityDatasetIdentity identity = identity();
        SecurityDatasetFence fence = new SecurityDatasetFence(identity, 7);

        assertThat(fence.getIdentity()).isSameAs(identity);
        assertThat(fence.getSecurityContentVersion()).isEqualTo(7);
        assertThat(fence).isEqualTo(new SecurityDatasetFence(identity(), 7));
        assertThat(fence.toString()).contains("securityContentVersion=7");
        assertThat(fence.nextContentVersion())
            .isEqualTo(new SecurityDatasetFence(identity(), 8));

        assertThatThrownBy(() -> new SecurityDatasetFence(identity, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("securityContentVersion");
        assertThatNullPointerException()
            .isThrownBy(() -> new SecurityDatasetFence(null, 0))
            .withMessageContaining("identity");
        assertThatThrownBy(() -> new SecurityDatasetFence(identity, Long.MAX_VALUE).nextContentVersion())
            .isInstanceOf(ArithmeticException.class)
            .hasMessageContaining("overflow");
    }

    private SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity("dataset", 1);
    }
}
