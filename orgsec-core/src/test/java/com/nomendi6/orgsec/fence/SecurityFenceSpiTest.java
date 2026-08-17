package com.nomendi6.orgsec.fence;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityFenceSpiTest {

    private static final String SHA = "abcdef0123456789".repeat(4);

    @Test
    void releaseProviderIsUsableAsAFunctionalInterface() {
        SecurityReleaseFence expected = new SecurityReleaseFence(
            identity(),
            "key-id",
            "signature",
            SHA
        );
        SecurityReleaseFenceProvider provider = () -> expected;

        assertThat(provider.loadVerifiedFence()).isSameAs(expected);
    }

    @Test
    void storeContractCanHoldTheLockForTheWholeCallback() {
        AtomicBoolean lockHeld = new AtomicBoolean();
        SecurityDatasetFence initial = new SecurityDatasetFence(identity(), 10);
        SecurityDatasetFenceStore store = fakeStore(initial, lockHeld);

        String result = store.withLockedFence(identity(), lockedFence -> {
            assertThat(lockHeld).isTrue();
            assertThat(lockedFence.current()).isEqualTo(initial);
            assertThat(lockedFence.incrementContentVersion().getSecurityContentVersion()).isEqualTo(11);
            return "published";
        });

        assertThat(result).isEqualTo("published");
        assertThat(lockHeld).isFalse();
    }

    @Test
    void storeImplementationCanRollBackAndPropagateCallbackFailure() {
        AtomicBoolean lockHeld = new AtomicBoolean();
        SecurityDatasetFenceStore store = fakeStore(
            new SecurityDatasetFence(identity(), 10),
            lockHeld
        );

        assertThatThrownBy(() -> store.withLockedFence(identity(), lockedFence -> {
            assertThat(lockHeld).isTrue();
            throw new IllegalStateException("mutation failed");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("mutation failed");
        assertThat(lockHeld).isFalse();
    }

    private SecurityDatasetFenceStore fakeStore(
        SecurityDatasetFence initial,
        AtomicBoolean lockHeld
    ) {
        return new SecurityDatasetFenceStore() {
            @Override
            public <T> T withLockedFence(
                SecurityDatasetIdentity expectedIdentity,
                LockedFenceWork<T> work
            ) {
                assertThat(expectedIdentity).isEqualTo(initial.getIdentity());
                assertThat(lockHeld.compareAndSet(false, true)).isTrue();
                try {
                    return work.execute(new LockedFence() {
                        private SecurityDatasetFence current = initial;

                        @Override
                        public SecurityDatasetFence current() {
                            return current;
                        }

                        @Override
                        public SecurityDatasetFence incrementContentVersion() {
                            current = new SecurityDatasetFence(
                                current.getIdentity(),
                                current.getSecurityContentVersion() + 1
                            );
                            return current;
                        }
                    });
                } finally {
                    lockHeld.set(false);
                }
            }
        };
    }

    private SecurityDatasetIdentity identity() {
        return new SecurityDatasetIdentity("dataset", 1, 2, SHA, 3, SHA);
    }
}
