package com.nomendi6.orgsec.storage.redis;

import com.nomendi6.orgsec.fence.SecurityDatasetFenceStore;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotLoader;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.preload.CacheWarmer;
import com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotCoordinator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Internal bean factory for the managed 1.1 Redis data plane.
 *
 * <p>This class deliberately has no component stereotype. It is loaded only through
 * {@code RedisStorageAutoConfiguration}'s explicit import, so component scanning cannot bypass
 * the Redis activation condition or migration gate.</p>
 */
final class RedisManagedStorageConfiguration {

    @Bean("redisSecurityDataStorage")
    RedisSecurityDataStorage redisSecurityDataStorage(
            RedisStorageProperties properties,
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            @Qualifier("roleL1Cache") L1Cache<Long, RoleDef> roleL1Cache,
            @Qualifier("positionRoleL1Cache") L1Cache<Long, RoleDef> positionRoleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            L2RedisCache<PersonDef> personL2Cache,
            L2RedisCache<OrganizationDef> organizationL2Cache,
            L2RedisCache<RoleDef> roleL2Cache,
            L2RedisCache<PrivilegeDef> privilegeL2Cache,
            CacheKeyBuilder cacheKeyBuilder,
            InvalidationEventPublisher invalidationPublisher,
            CacheWarmer cacheWarmer) {

        RedisSecurityDataStorage storage = RedisSecurityDataStorage.managedWaiting(
            properties,
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            positionRoleL1Cache,
            privilegeL1Cache,
            personL2Cache,
            organizationL2Cache,
            roleL2Cache,
            privilegeL2Cache,
            cacheKeyBuilder,
            invalidationPublisher,
            cacheWarmer
        );
        storage.initialize();
        return storage;
    }

    @Bean
    RedisSnapshotCoordinator redisSnapshotCoordinator(
            RedisStorageProperties properties,
            RedisConnectionFactory redisConnectionFactory,
            SecurityDatasetFenceStore securityDatasetFenceStore,
            RedisSnapshotLoader redisSnapshotLoader,
            RedisSecurityDataStorage redisSecurityDataStorage) {
        RedisSnapshotCoordinator coordinator = new RedisSnapshotCoordinator(
            properties,
            redisConnectionFactory,
            securityDatasetFenceStore,
            redisSnapshotLoader
        );
        redisSecurityDataStorage.attachSnapshotCoordinator(coordinator);
        return coordinator;
    }

    @Bean
    ApplicationRunner redisSnapshotBootstrap(RedisSnapshotCoordinator redisSnapshotCoordinator) {
        return args -> redisSnapshotCoordinator.bootstrap();
    }
}
