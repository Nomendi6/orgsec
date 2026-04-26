package com.nomendi6.orgsec.storage.redis.preload;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Cache warmer for preloading data into Redis cache on startup.
 * <p>
 * Supports both eager preload (on startup) and lazy preload (on demand).
 * The preload strategy (all, persons, organizations, roles) is configurable.
 * </p>
 */
public class CacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmer.class);

    private final RedisStorageProperties.PreloadConfig preloadConfig;
    private final CacheWarmingStrategy warmingStrategy;

    // Batch store callbacks (set by RedisSecurityDataStorage)
    private Consumer<Map<Long, PersonDef>> personBatchStore;
    private Consumer<Map<Long, OrganizationDef>> organizationBatchStore;
    private Consumer<Map<Long, RoleDef>> roleBatchStore;

    // Data loaders (to be set by application)
    private DataLoader<Long, PersonDef> personLoader;
    private DataLoader<Long, OrganizationDef> organizationLoader;
    private DataLoader<Long, RoleDef> roleLoader;

    /**
     * Functional interface for loading data from source.
     *
     * @param <K> the key type
     * @param <V> the value type
     */
    @FunctionalInterface
    public interface DataLoader<K, V> {
        /**
         * Loads all data from source.
         *
         * @return map of key to value
         */
        Map<K, V> loadAll();
    }

    /**
     * Constructs a new cache warmer.
     *
     * @param preloadConfig the preload configuration
     */
    public CacheWarmer(RedisStorageProperties.PreloadConfig preloadConfig) {
        this.preloadConfig = preloadConfig;
        this.warmingStrategy = createStrategy(preloadConfig);
        log.info("CacheWarmer initialized with {} strategy", warmingStrategy.getName());
    }

    /**
     * Creates the appropriate warming strategy based on configuration.
     */
    private CacheWarmingStrategy createStrategy(RedisStorageProperties.PreloadConfig config) {
        String mode = config.getMode().toLowerCase();
        switch (mode) {
            case "progressive":
                return new ProgressiveWarmingStrategy(config);
            case "lazy":
                // Lazy mode returns a no-op strategy - warming happens on first access
                return new CacheWarmingStrategy() {
                    @Override
                    public <K, V> int warm(DataLoader<K, V> loader, Consumer<Map<K, V>> store) {
                        log.debug("Lazy warming mode - data will be loaded on first access");
                        return 0;
                    }

                    @Override
                    public String getName() {
                        return "lazy";
                    }
                };
            case "eager":
            default:
                return new EagerWarmingStrategy();
        }
    }

    /**
     * Sets the batch store callback for persons.
     *
     * @param personBatchStore the callback
     */
    public void setPersonBatchStore(Consumer<Map<Long, PersonDef>> personBatchStore) {
        this.personBatchStore = personBatchStore;
    }

    /**
     * Sets the batch store callback for organizations.
     *
     * @param organizationBatchStore the callback
     */
    public void setOrganizationBatchStore(Consumer<Map<Long, OrganizationDef>> organizationBatchStore) {
        this.organizationBatchStore = organizationBatchStore;
    }

    /**
     * Sets the batch store callback for roles.
     *
     * @param roleBatchStore the callback
     */
    public void setRoleBatchStore(Consumer<Map<Long, RoleDef>> roleBatchStore) {
        this.roleBatchStore = roleBatchStore;
    }

    /**
     * Sets the data loader for persons.
     *
     * @param personLoader the loader
     */
    public void setPersonLoader(DataLoader<Long, PersonDef> personLoader) {
        this.personLoader = personLoader;
    }

    /**
     * Sets the data loader for organizations.
     *
     * @param organizationLoader the loader
     */
    public void setOrganizationLoader(DataLoader<Long, OrganizationDef> organizationLoader) {
        this.organizationLoader = organizationLoader;
    }

    /**
     * Sets the data loader for roles.
     *
     * @param roleLoader the loader
     */
    public void setRoleLoader(DataLoader<Long, RoleDef> roleLoader) {
        this.roleLoader = roleLoader;
    }

    /**
     * Performs full cache warmup based on configured strategy.
     * <p>
     * This method loads data from the database and stores it in Redis cache.
     * </p>
     *
     * @return warmup statistics
     */
    public WarmupStats warmup() {
        if (!preloadConfig.isEnabled()) {
            log.info("Cache preload is disabled");
            return new WarmupStats(0, 0, 0, 0, LocalDateTime.now());
        }

        long startTime = System.currentTimeMillis();
        log.info("Starting cache warmup with strategy: {}", preloadConfig.getStrategy());

        int personCount = 0;
        int organizationCount = 0;
        int roleCount = 0;

        try {
            String strategy = preloadConfig.getStrategy();

            switch (strategy.toLowerCase()) {
                case "all":
                    personCount = warmupPersons();
                    organizationCount = warmupOrganizations();
                    roleCount = warmupRoles();
                    break;

                case "persons":
                    personCount = warmupPersons();
                    break;

                case "organizations":
                    organizationCount = warmupOrganizations();
                    break;

                case "roles":
                    roleCount = warmupRoles();
                    break;

                default:
                    log.warn("Unknown warmup strategy: {}, using 'all'", strategy);
                    personCount = warmupPersons();
                    organizationCount = warmupOrganizations();
                    roleCount = warmupRoles();
            }

            long duration = System.currentTimeMillis() - startTime;
            WarmupStats stats = new WarmupStats(
                personCount,
                organizationCount,
                roleCount,
                duration,
                LocalDateTime.now()
            );

            log.info("Cache warmup complete: {}", stats);
            return stats;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Cache warmup failed after {}ms", duration, e);
            throw new RuntimeException("Cache warmup failed", e);
        }
    }

    /**
     * Warms up the persons cache using the configured strategy.
     *
     * @return number of persons loaded
     */
    public int warmupPersons() {
        log.debug("Warming up persons cache using {} strategy...", warmingStrategy.getName());

        int count = warmingStrategy.warm(personLoader, personBatchStore);
        if (count > 0) {
            log.info("Warmed up {} persons using {} strategy", count, warmingStrategy.getName());
        }
        return count;
    }

    /**
     * Warms up the organizations cache using the configured strategy.
     *
     * @return number of organizations loaded
     */
    public int warmupOrganizations() {
        log.debug("Warming up organizations cache using {} strategy...", warmingStrategy.getName());

        int count = warmingStrategy.warm(organizationLoader, organizationBatchStore);
        if (count > 0) {
            log.info("Warmed up {} organizations using {} strategy", count, warmingStrategy.getName());
        }
        return count;
    }

    /**
     * Warms up the roles cache using the configured strategy.
     *
     * @return number of roles loaded
     */
    public int warmupRoles() {
        log.debug("Warming up roles cache using {} strategy...", warmingStrategy.getName());

        int count = warmingStrategy.warm(roleLoader, roleBatchStore);
        if (count > 0) {
            log.info("Warmed up {} roles using {} strategy", count, warmingStrategy.getName());
        }
        return count;
    }

    /**
     * Returns the current warming strategy.
     *
     * @return the warming strategy
     */
    public CacheWarmingStrategy getWarmingStrategy() {
        return warmingStrategy;
    }

    /**
     * Returns the preload configuration.
     *
     * @return the preload config
     */
    public RedisStorageProperties.PreloadConfig getPreloadConfig() {
        return preloadConfig;
    }
}
