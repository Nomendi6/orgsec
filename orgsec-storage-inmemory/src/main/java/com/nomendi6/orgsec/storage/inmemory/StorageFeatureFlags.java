package com.nomendi6.orgsec.storage.inmemory;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Feature flags for controlling storage strategies at runtime.
 * Allows dynamic switching between different storage providers without application restart.
 */
@Component
@ConfigurationProperties(prefix = "orgsec.storage")
public class StorageFeatureFlags {

    private static final Logger log = LoggerFactory.getLogger(StorageFeatureFlags.class);

    private String primary = "memory";
    private String fallback = "memory";
    private Features features = new Features();
    private Map<String, String> dataSources = new HashMap<>();

    public StorageFeatureFlags() {
        // Initialize default data sources
        dataSources.put("person", "primary");
        dataSources.put("organization", "primary");
        dataSources.put("role", "primary");
        dataSources.put("privilege", "memory"); // Privileges always from memory
    }

    // ========== RUNTIME CONTROL METHODS ==========

    /**
     * Enable JWT storage at runtime
     */
    public void enableJwtStorage() {
        this.features.jwtEnabled = true;
        log.info("JWT storage enabled");
    }

    /**
     * Disable JWT storage at runtime
     */
    public void disableJwtStorage() {
        this.features.jwtEnabled = false;
        log.info("JWT storage disabled");
    }

    /**
     * Enable Redis cache at runtime
     */
    public void enableRedisCache() {
        this.features.redisEnabled = true;
        log.info("Redis cache enabled");
    }

    /**
     * Disable Redis cache at runtime
     */
    public void disableRedisCache() {
        this.features.redisEnabled = false;
        log.info("Redis cache disabled");
    }

    /**
     * Enable hybrid mode at runtime
     */
    public void enableHybridMode() {
        this.features.hybridModeEnabled = true;
        log.info("Hybrid mode enabled");
    }

    /**
     * Disable hybrid mode at runtime
     */
    public void disableHybridMode() {
        this.features.hybridModeEnabled = false;
        log.info("Hybrid mode disabled");
    }

    /**
     * Change data source for specific data type
     * @param dataType the type of data (person, organization, role, privilege)
     * @param source the storage source (primary, jwt, redis, memory)
     */
    public void setDataSource(String dataType, String source) {
        this.dataSources.put(dataType, source);
        log.info("{} data source changed to: {}", dataType, source);
    }

    /**
     * Set person data source
     * @param source storage source
     */
    public void setPersonDataSource(String source) {
        setDataSource("person", source);
    }

    /**
     * Set organization data source
     * @param source storage source
     */
    public void setOrganizationDataSource(String source) {
        setDataSource("organization", source);
    }

    /**
     * Set role data source
     * @param source storage source
     */
    public void setRoleDataSource(String source) {
        setDataSource("role", source);
    }

    // ========== QUERY METHODS ==========

    /**
     * Check if JWT storage is enabled
     */
    public boolean isJwtStorageEnabled() {
        return features.jwtEnabled;
    }

    /**
     * Check if Redis cache is enabled
     */
    public boolean isRedisCacheEnabled() {
        return features.redisEnabled;
    }

    /**
     * Check if hybrid mode is enabled
     */
    public boolean isHybridModeEnabled() {
        return features.hybridModeEnabled;
    }

    /**
     * Check if memory storage is enabled
     */
    public boolean isMemoryStorageEnabled() {
        return features.memoryEnabled;
    }

    /**
     * Get data source for specific data type
     * @param dataType the type of data
     * @return the configured source
     */
    public String getDataSource(String dataType) {
        return dataSources.getOrDefault(dataType, "primary");
    }

    /**
     * Get person data source
     */
    public String getPersonDataSource() {
        return getDataSource("person");
    }

    /**
     * Get organization data source
     */
    public String getOrganizationDataSource() {
        return getDataSource("organization");
    }

    /**
     * Get role data source
     */
    public String getRoleDataSource() {
        return getDataSource("role");
    }

    /**
     * Get privilege data source
     */
    public String getPrivilegeDataSource() {
        return getDataSource("privilege");
    }

    // ========== GETTERS AND SETTERS ==========

    public String getPrimary() {
        return primary;
    }

    public void setPrimary(String primary) {
        this.primary = primary;
    }

    public String getFallback() {
        return fallback;
    }

    public void setFallback(String fallback) {
        this.fallback = fallback;
    }

    public Features getFeatures() {
        return features;
    }

    public void setFeatures(Features features) {
        this.features = features;
    }

    public Map<String, String> getDataSources() {
        return dataSources;
    }

    public void setDataSources(Map<String, String> dataSources) {
        this.dataSources = dataSources;
    }

    /**
     * Feature flags for different storage capabilities
     */
    public static class Features {

        private boolean jwtEnabled = false;
        private boolean redisEnabled = false;
        private boolean hybridModeEnabled = false;
        private boolean memoryEnabled = true;

        // Getters and setters
        public boolean isJwtEnabled() {
            return jwtEnabled;
        }

        public void setJwtEnabled(boolean jwtEnabled) {
            this.jwtEnabled = jwtEnabled;
        }

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }

        public boolean isHybridModeEnabled() {
            return hybridModeEnabled;
        }

        public void setHybridModeEnabled(boolean hybridModeEnabled) {
            this.hybridModeEnabled = hybridModeEnabled;
        }

        public boolean isMemoryEnabled() {
            return memoryEnabled;
        }

        public void setMemoryEnabled(boolean memoryEnabled) {
            this.memoryEnabled = memoryEnabled;
        }
    }
}
