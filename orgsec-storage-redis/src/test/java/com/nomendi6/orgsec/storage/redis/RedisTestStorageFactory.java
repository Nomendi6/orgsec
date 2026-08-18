package com.nomendi6.orgsec.storage.redis;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.preload.CacheWarmer;

import java.lang.reflect.Constructor;

/** Test-only access to the pre-fence cache algorithms retained for compatibility coverage. */
public final class RedisTestStorageFactory {

    private RedisTestStorageFactory() {
    }

    public static RedisSecurityDataStorage createLegacyUnfenced(
            RedisStorageProperties properties,
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            L1Cache<Long, RoleDef> roleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            L2RedisCache<PersonDef> personL2Cache,
            L2RedisCache<OrganizationDef> organizationL2Cache,
            L2RedisCache<RoleDef> roleL2Cache,
            L2RedisCache<PrivilegeDef> privilegeL2Cache,
            CacheKeyBuilder cacheKeyBuilder,
            InvalidationEventPublisher invalidationPublisher,
            CacheWarmer cacheWarmer) {

        return createLegacyUnfenced(
            properties,
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            new L1Cache<>(Math.max(1, roleL1Cache.getMaxSize())),
            privilegeL1Cache,
            personL2Cache,
            organizationL2Cache,
            roleL2Cache,
            privilegeL2Cache,
            cacheKeyBuilder,
            invalidationPublisher,
            cacheWarmer
        );
    }

    public static RedisSecurityDataStorage createLegacyUnfenced(
            RedisStorageProperties properties,
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            L1Cache<Long, RoleDef> roleL1Cache,
            L1Cache<Long, RoleDef> positionRoleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            L2RedisCache<PersonDef> personL2Cache,
            L2RedisCache<OrganizationDef> organizationL2Cache,
            L2RedisCache<RoleDef> roleL2Cache,
            L2RedisCache<PrivilegeDef> privilegeL2Cache,
            CacheKeyBuilder cacheKeyBuilder,
            InvalidationEventPublisher invalidationPublisher,
            CacheWarmer cacheWarmer) {

        try {
            Constructor<RedisSecurityDataStorage> constructor =
                RedisSecurityDataStorage.class.getDeclaredConstructor(
                    RedisStorageProperties.class,
                    L1Cache.class,
                    L1Cache.class,
                    L1Cache.class,
                    L1Cache.class,
                    L1Cache.class,
                    L2RedisCache.class,
                    L2RedisCache.class,
                    L2RedisCache.class,
                    L2RedisCache.class,
                    CacheKeyBuilder.class,
                    InvalidationEventPublisher.class,
                    CacheWarmer.class,
                    boolean.class
                );
            constructor.setAccessible(true);
            return constructor.newInstance(
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
                cacheWarmer,
                false
            );
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot construct the test-only legacy cache fixture", exception);
        }
    }
}
