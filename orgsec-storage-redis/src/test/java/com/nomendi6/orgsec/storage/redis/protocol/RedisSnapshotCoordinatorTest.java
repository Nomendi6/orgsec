package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFenceStore;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotLoader;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisSnapshotCoordinatorTest {

    @Test
    void staysNotReadyAndDeniesReadsUntilAVerifiedSnapshotIsInstalled() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setSecurityDatasetId("orgsec-test");
        RedisSnapshotCoordinator coordinator = new RedisSnapshotCoordinator(
            properties,
            mock(RedisConnectionFactory.class),
            mock(SecurityDatasetFenceStore.class),
            mock(RedisSnapshotLoader.class)
        );

        coordinator.bootstrap();

        assertThat(coordinator.isReady()).isFalse();
        assertThat(coordinator.person(1L)).isNull();
        assertThat(coordinator.organization(2L)).isNull();
        assertThat(coordinator.partyRole(3L)).isNull();
        assertThat(coordinator.positionRole(4L)).isNull();
        assertThat(coordinator.privilege("invoice:read")).isNull();
        assertThat(coordinator.persons(java.util.List.of(1L))).isEmpty();
        assertThat(coordinator.organizations(java.util.List.of(2L))).isEmpty();
    }

    @Test
    void bootstrapFailureLeavesStorageFailClosed() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setSecurityDatasetId("orgsec-test");
        SecurityDatasetFenceStore fenceStore = mock(SecurityDatasetFenceStore.class);
        org.mockito.Mockito.when(fenceStore.withLockedFence(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        )).thenThrow(new IllegalStateException("fence unavailable"));

        RedisSnapshotCoordinator coordinator = new RedisSnapshotCoordinator(
            properties,
            mock(RedisConnectionFactory.class),
            fenceStore,
            mock(RedisSnapshotLoader.class)
        );
        coordinator.bootstrap();

        assertThat(coordinator.isReady()).isFalse();
        PersonDef person = coordinator.person(9L);
        assertThat(person).isNull();
    }
}
