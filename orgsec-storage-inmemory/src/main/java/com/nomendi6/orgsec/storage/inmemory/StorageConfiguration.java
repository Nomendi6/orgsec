package com.nomendi6.orgsec.storage.inmemory;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.StorageFeatureFlags;

/**
 * Configuration class for Security Data Storage providers.
 * This class manages @Primary designation dynamically based on which storage modules are enabled.
 *
 * Priority order:
 * 1. JWT storage (if enabled) - becomes @Primary and uses InMemory as delegate
 * 2. Redis storage (if enabled) - becomes @Primary and uses InMemory as fallback
 * 3. InMemory storage - @Primary only when no other storage modules are active
 */
@Configuration
public class StorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StorageConfiguration.class);

    /**
     * Provides InMemorySecurityDataStorage as @Primary ONLY when JWT and Redis are disabled.
     * This is the default storage when no other storage modules are active.
     */
    @Bean
    @Primary
    @Qualifier("primaryInMemoryStorage")
    @ConditionalOnProperty(
        prefix = "orgsec.storage.features",
        name = {"jwt-enabled", "redis-enabled"},
        havingValue = "false",
        matchIfMissing = true // Default to InMemory if properties are not set
    )
    public SecurityDataStorage primaryInMemoryStorage(InMemorySecurityDataStorage inMemoryStorage) {
        log.info("Configuring InMemorySecurityDataStorage as PRIMARY storage (no JWT/Redis modules active)");
        return inMemoryStorage;
    }

    /**
     * Provides InMemorySecurityDataStorage as delegate storage for JWT/Redis hybrid implementations.
     * This bean is NEVER @Primary - it only serves as a delegate for other storage implementations.
     * Note: We don't use @ConditionalOnProperty here because this bean should always be available
     * as a delegate, regardless of which storage modules are enabled.
     */
    @Bean
    @Qualifier("delegateSecurityDataStorage")
    public SecurityDataStorage delegateSecurityDataStorage(InMemorySecurityDataStorage inMemoryStorage) {
        log.info("Registering InMemorySecurityDataStorage as delegate storage for hybrid implementations");
        return inMemoryStorage;
    }

    /**
     * JWT Security Data Storage (placeholder implementation)
     * This will be activated when JWT features are enabled AND real JWT module is NOT available
     */
    @Bean
    @ConditionalOnProperty(name = "orgsec.storage.features.jwt-enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass("com.nomendi6.orgsec.storage.jwt.JwtSecurityDataStorage")
    public JwtSecurityDataStorage jwtSecurityDataStorage(StorageFeatureFlags featureFlags) {
        log.info("JWT Security Data Storage enabled (placeholder - real JWT module not found)");
        return new JwtSecurityDataStorage();
    }

    /**
     * Redis Security Data Storage (placeholder implementation)
     * This will be activated when Redis features are enabled AND real Redis module is NOT available
     */
    @Bean
    @ConditionalOnProperty(name = "orgsec.storage.features.redis-enabled", havingValue = "true")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass("com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage")
    public RedisSecurityDataStorage redisSecurityDataStorage(
        StorageFeatureFlags featureFlags,
        Optional<SecurityDataStorage> fallbackStorage
    ) {
        log.info("Redis Security Data Storage enabled (placeholder - real Redis module not found)");
        return new RedisSecurityDataStorage(fallbackStorage.orElse(null));
    }

    /**
     * Placeholder JWT Security Data Storage implementation
     * TODO: Move this to separate class when implementing JWT storage
     */
    public static class JwtSecurityDataStorage implements SecurityDataStorage {

        private static final Logger log = LoggerFactory.getLogger(JwtSecurityDataStorage.class);

        @Override
        public com.nomendi6.orgsec.model.PersonDef getPerson(Long personId) {
            log.warn("JWT storage not yet implemented, returning null for person: {}", personId);
            return null;
        }

        @Override
        public com.nomendi6.orgsec.model.OrganizationDef getOrganization(Long orgId) {
            log.warn("JWT storage not yet implemented, returning null for organization: {}", orgId);
            return null;
        }

        @Override
        public com.nomendi6.orgsec.model.RoleDef getPartyRole(Long roleId) {
            log.warn("JWT storage not yet implemented, returning null for party role: {}", roleId);
            return null;
        }

        @Override
        public com.nomendi6.orgsec.model.RoleDef getPositionRole(Long roleId) {
            log.warn("JWT storage not yet implemented, returning null for position role: {}", roleId);
            return null;
        }

        @Override
        public com.nomendi6.orgsec.model.PrivilegeDef getPrivilege(String privilegeIdentifier) {
            log.warn("JWT storage not yet implemented, returning null for privilege: {}", privilegeIdentifier);
            return null;
        }

        @Override
        public void initialize() {
            log.info("JWT storage initialized (placeholder)");
        }

        @Override
        public void refresh() {
            log.info("JWT storage refreshed (placeholder)");
        }

        @Override
        public boolean isReady() {
            return false; // Not ready until properly implemented
        }

        @Override
        public String getProviderType() {
            return "jwt-placeholder";
        }
    }

    /**
     * Placeholder Redis Security Data Storage implementation
     * TODO: Move this to separate class when implementing Redis storage
     */
    public static class RedisSecurityDataStorage implements SecurityDataStorage {

        private static final Logger log = LoggerFactory.getLogger(RedisSecurityDataStorage.class);

        private final SecurityDataStorage fallbackStorage;

        public RedisSecurityDataStorage(SecurityDataStorage fallbackStorage) {
            this.fallbackStorage = fallbackStorage;
        }

        @Override
        public com.nomendi6.orgsec.model.PersonDef getPerson(Long personId) {
            log.warn("Redis storage not yet implemented, using fallback for person: {}", personId);
            return fallbackStorage != null ? fallbackStorage.getPerson(personId) : null;
        }

        @Override
        public com.nomendi6.orgsec.model.OrganizationDef getOrganization(Long orgId) {
            log.warn("Redis storage not yet implemented, using fallback for organization: {}", orgId);
            return fallbackStorage != null ? fallbackStorage.getOrganization(orgId) : null;
        }

        @Override
        public com.nomendi6.orgsec.model.RoleDef getPartyRole(Long roleId) {
            log.warn("Redis storage not yet implemented, using fallback for party role: {}", roleId);
            return fallbackStorage != null ? fallbackStorage.getPartyRole(roleId) : null;
        }

        @Override
        public com.nomendi6.orgsec.model.RoleDef getPositionRole(Long roleId) {
            log.warn("Redis storage not yet implemented, using fallback for position role: {}", roleId);
            return fallbackStorage != null ? fallbackStorage.getPositionRole(roleId) : null;
        }

        @Override
        public com.nomendi6.orgsec.model.PrivilegeDef getPrivilege(String privilegeIdentifier) {
            log.warn("Redis storage not yet implemented, using fallback for privilege: {}", privilegeIdentifier);
            return fallbackStorage != null ? fallbackStorage.getPrivilege(privilegeIdentifier) : null;
        }

        @Override
        public void initialize() {
            log.info("Redis storage initialized (placeholder)");
            if (fallbackStorage != null) {
                fallbackStorage.initialize();
            }
        }

        @Override
        public void refresh() {
            log.info("Redis storage refreshed (placeholder)");
            if (fallbackStorage != null) {
                fallbackStorage.refresh();
            }
        }

        @Override
        public boolean isReady() {
            return fallbackStorage != null && fallbackStorage.isReady();
        }

        @Override
        public String getProviderType() {
            return "redis-placeholder";
        }
    }
}
