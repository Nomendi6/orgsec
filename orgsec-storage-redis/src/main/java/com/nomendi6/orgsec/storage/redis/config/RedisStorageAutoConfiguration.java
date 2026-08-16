package com.nomendi6.orgsec.storage.redis.config;

import com.nomendi6.orgsec.audit.DefaultSecurityAuditLogger;
import com.nomendi6.orgsec.audit.NoOpSecurityAuditLogger;
import com.nomendi6.orgsec.audit.SecurityAuditLogger;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.health.RedisStorageHealthIndicator;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventListener;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.preload.CacheWarmer;
import com.nomendi6.orgsec.storage.redis.resilience.RedisCircuitBreakerService;
import com.nomendi6.orgsec.storage.redis.serialization.JsonSerializer;
import com.nomendi6.orgsec.storage.redis.serialization.OrgsecObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.UUID;

/**
 * Spring Boot Auto-configuration for Redis-based SecurityDataStorage.
 * <p>
 * Automatically configures all necessary beans when:
 * - Redis is available on classpath
 * - Property orgsec.storage.redis.enabled=true
 * </p>
 * <p>
 * This auto-configuration is loaded AFTER JacksonAutoConfiguration to ensure
 * that the application's primary ObjectMapper is created first. OrgSec uses
 * an internal ObjectMapper factory to avoid conflicts with the application's
 * ObjectMapper - no ObjectMapper beans are exposed by this configuration.
 * </p>
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass(RedisConnectionFactory.class)
@ConditionalOnProperty(prefix = "orgsec.storage.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RedisStorageProperties.class)
public class RedisStorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisStorageAutoConfiguration.class);

    /**
     * Unique instance ID for this application instance.
     * Used to avoid processing own invalidation events.
     */
    @Bean
    @ConditionalOnMissingBean
    public String instanceId() {
        String id = UUID.randomUUID().toString();
        log.info("Generated instance ID: {}", id);
        return id;
    }

    // ==================== ObjectMapper Configuration ====================

    /**
     * Factory for creating configured ObjectMapper instances.
     * <p>
     * The factory manages ObjectMapper instances internally and does not expose
     * them as Spring beans to avoid conflicts with the application's ObjectMapper.
     * </p>
     */
    @Bean
    @ConditionalOnMissingBean
    public OrgsecObjectMapperFactory orgsecObjectMapperFactory(RedisStorageProperties properties) {
        log.info("Creating OrgsecObjectMapperFactory with serialization config: failOnUnknownProperties={}, strictMode={}",
                properties.getSerialization().isFailOnUnknownProperties(),
                properties.getSerialization().isStrictMode());
        return new OrgsecObjectMapperFactory(properties.getSerialization());
    }

    /**
     * Cache key builder for generating Redis keys.
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheKeyBuilder cacheKeyBuilder(RedisStorageProperties properties) {
        boolean obfuscate = properties.getCache().isObfuscateKeys();
        log.info("Creating CacheKeyBuilder with obfuscation: {}", obfuscate);
        return new CacheKeyBuilder(obfuscate);
    }

    /**
     * Circuit breaker service for Redis operations resilience.
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisCircuitBreakerService redisCircuitBreakerService(RedisStorageProperties properties) {
        log.info("Creating RedisCircuitBreakerService");
        return new RedisCircuitBreakerService(properties);
    }

    /**
     * Security audit logger for tracking security events.
     * <p>
     * Returns NoOpSecurityAuditLogger when disabled, DefaultSecurityAuditLogger otherwise.
     * </p>
     */
    @Bean
    @ConditionalOnMissingBean
    public SecurityAuditLogger securityAuditLogger(RedisStorageProperties properties) {
        RedisStorageProperties.AuditConfig auditConfig = properties.getAudit();
        if (!auditConfig.isEnabled()) {
            log.info("Security audit logging is disabled");
            return new NoOpSecurityAuditLogger();
        }
        log.info("Creating SecurityAuditLogger with settings: logCacheAccess={}, logPrivilegeChecks={}, logConfigChanges={}",
                auditConfig.isLogCacheAccess(),
                auditConfig.isLogPrivilegeChecks(),
                auditConfig.isLogConfigChanges());
        return new DefaultSecurityAuditLogger(true, auditConfig.isLogCacheAccess());
    }

    // ==================== Serializers ====================

    @Bean
    @ConditionalOnMissingBean(name = "personSerializer")
    public JsonSerializer<PersonDef> personSerializer(
            OrgsecObjectMapperFactory objectMapperFactory) {
        log.debug("Creating PersonDef serializer with factory-managed ObjectMapper");
        return new JsonSerializer<>(PersonDef.class, objectMapperFactory.getDomainObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean(name = "organizationSerializer")
    public JsonSerializer<OrganizationDef> organizationSerializer(
            OrgsecObjectMapperFactory objectMapperFactory) {
        log.debug("Creating OrganizationDef serializer with factory-managed ObjectMapper");
        return new JsonSerializer<>(OrganizationDef.class, objectMapperFactory.getDomainObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean(name = "roleSerializer")
    public JsonSerializer<RoleDef> roleSerializer(
            OrgsecObjectMapperFactory objectMapperFactory) {
        log.debug("Creating RoleDef serializer with factory-managed ObjectMapper");
        return new JsonSerializer<>(RoleDef.class, objectMapperFactory.getDomainObjectMapper());
    }

    @Bean
    @ConditionalOnMissingBean(name = "privilegeSerializer")
    public JsonSerializer<PrivilegeDef> privilegeSerializer(
            OrgsecObjectMapperFactory objectMapperFactory) {
        log.debug("Creating PrivilegeDef serializer with factory-managed ObjectMapper");
        return new JsonSerializer<>(PrivilegeDef.class, objectMapperFactory.getDomainObjectMapper());
    }

    // ==================== Redis Template ====================

    /**
     * RedisTemplate configured for String keys and values.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    @ConditionalOnMissingBean(name = "orgsecRedisTemplate")
    public RedisTemplate<String, String> orgsecRedisTemplate(RedisConnectionFactory connectionFactory) {
        log.info("Creating RedisTemplate for OrgSec storage");
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for both keys and values
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();
        return template;
    }

    // ==================== L1 Caches (in-memory) ====================

    @Bean
    @ConditionalOnMissingBean(name = "personL1Cache")
    public L1Cache<Long, PersonDef> personL1Cache(RedisStorageProperties properties) {
        int maxSize = properties.getCache().getL1MaxSize();
        log.info("Creating PersonDef L1 cache with max size: {}", maxSize);
        return new L1Cache<>(maxSize);
    }

    @Bean
    @ConditionalOnMissingBean(name = "organizationL1Cache")
    public L1Cache<Long, OrganizationDef> organizationL1Cache(RedisStorageProperties properties) {
        int maxSize = properties.getCache().getL1MaxSize();
        log.info("Creating OrganizationDef L1 cache with max size: {}", maxSize);
        return new L1Cache<>(maxSize);
    }

    @Bean
    @ConditionalOnMissingBean(name = "roleL1Cache")
    public L1Cache<Long, RoleDef> roleL1Cache(RedisStorageProperties properties) {
        int maxSize = properties.getCache().getL1MaxSize();
        log.info("Creating party RoleDef L1 cache with max size: {}", maxSize);
        return new L1Cache<>(maxSize);
    }

    @Bean
    @ConditionalOnMissingBean(name = "positionRoleL1Cache")
    public L1Cache<Long, RoleDef> positionRoleL1Cache(RedisStorageProperties properties) {
        int maxSize = properties.getCache().getL1MaxSize();
        log.info("Creating position RoleDef L1 cache with max size: {}", maxSize);
        return new L1Cache<>(maxSize);
    }

    @Bean
    @ConditionalOnMissingBean(name = "privilegeL1Cache")
    public L1Cache<String, PrivilegeDef> privilegeL1Cache(RedisStorageProperties properties) {
        int maxSize = properties.getCache().getL1MaxSize();
        log.info("Creating PrivilegeDef L1 cache with max size: {}", maxSize);
        return new L1Cache<>(maxSize);
    }

    // ==================== L2 Caches (Redis) ====================

    @Bean
    @ConditionalOnMissingBean(name = "personL2Cache")
    public L2RedisCache<PersonDef> personL2Cache(
            RedisTemplate<String, String> orgsecRedisTemplate,
            JsonSerializer<PersonDef> personSerializer,
            CacheKeyBuilder cacheKeyBuilder,
            RedisCircuitBreakerService circuitBreakerService) {

        log.info("Creating PersonDef L2 cache with circuit breaker");
        return new L2RedisCache<>(orgsecRedisTemplate, personSerializer, cacheKeyBuilder, circuitBreakerService);
    }

    @Bean
    @ConditionalOnMissingBean(name = "organizationL2Cache")
    public L2RedisCache<OrganizationDef> organizationL2Cache(
            RedisTemplate<String, String> orgsecRedisTemplate,
            JsonSerializer<OrganizationDef> organizationSerializer,
            CacheKeyBuilder cacheKeyBuilder,
            RedisCircuitBreakerService circuitBreakerService) {

        log.info("Creating OrganizationDef L2 cache with circuit breaker");
        return new L2RedisCache<>(orgsecRedisTemplate, organizationSerializer, cacheKeyBuilder, circuitBreakerService);
    }

    @Bean
    @ConditionalOnMissingBean(name = "roleL2Cache")
    public L2RedisCache<RoleDef> roleL2Cache(
            RedisTemplate<String, String> orgsecRedisTemplate,
            JsonSerializer<RoleDef> roleSerializer,
            CacheKeyBuilder cacheKeyBuilder,
            RedisCircuitBreakerService circuitBreakerService) {

        log.info("Creating RoleDef L2 cache with circuit breaker");
        return new L2RedisCache<>(orgsecRedisTemplate, roleSerializer, cacheKeyBuilder, circuitBreakerService);
    }

    @Bean
    @ConditionalOnMissingBean(name = "privilegeL2Cache")
    public L2RedisCache<PrivilegeDef> privilegeL2Cache(
            RedisTemplate<String, String> orgsecRedisTemplate,
            JsonSerializer<PrivilegeDef> privilegeSerializer,
            CacheKeyBuilder cacheKeyBuilder,
            RedisCircuitBreakerService circuitBreakerService) {

        log.info("Creating PrivilegeDef L2 cache with circuit breaker");
        return new L2RedisCache<>(orgsecRedisTemplate, privilegeSerializer, cacheKeyBuilder, circuitBreakerService);
    }

    // ==================== Invalidation ====================

    /**
     * Channel topic for cache invalidation events.
     */
    @Bean
    @ConditionalOnMissingBean
    public ChannelTopic invalidationTopic(RedisStorageProperties properties) {
        String channel = properties.getInvalidation().getChannel();
        log.info("Creating invalidation channel topic: {}", channel);
        return new ChannelTopic(channel);
    }

    /**
     * Publisher for broadcasting cache invalidation events.
     */
    @Bean
    @ConditionalOnMissingBean
    public InvalidationEventPublisher invalidationEventPublisher(
            RedisTemplate<String, String> orgsecRedisTemplate,
            ChannelTopic invalidationTopic,
            String instanceId,
            RedisStorageProperties properties,
            OrgsecObjectMapperFactory objectMapperFactory) {

        boolean async = properties.getInvalidation().isAsync();
        log.info("Creating InvalidationEventPublisher with async: {} and factory-managed ObjectMapper", async);
        return new InvalidationEventPublisher(
            orgsecRedisTemplate,
            invalidationTopic.getTopic(),
            async,
            instanceId,
            objectMapperFactory.getEventObjectMapper()
        );
    }

    /**
     * Listener for receiving cache invalidation events.
     */
    public InvalidationEventListener invalidationEventListener(
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            L1Cache<Long, RoleDef> roleL1Cache,
            String instanceId,
            OrgsecObjectMapperFactory objectMapperFactory) {

        log.info("Creating InvalidationEventListener for instance: {} with factory-managed ObjectMapper", instanceId);
        return new InvalidationEventListener(
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            instanceId,
            objectMapperFactory.getEventObjectMapper()
        );
    }

    /**
     * Listener bean with all typed L1 caches. The legacy public factory method above remains
     * available for applications compiled against 1.0.4.
     */
    @Bean("invalidationEventListener")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "orgsec.storage.redis.invalidation", name = "enabled", havingValue = "true")
    public InvalidationEventListener typedInvalidationEventListener(
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            @org.springframework.beans.factory.annotation.Qualifier("roleL1Cache")
            L1Cache<Long, RoleDef> roleL1Cache,
            @org.springframework.beans.factory.annotation.Qualifier("positionRoleL1Cache")
            L1Cache<Long, RoleDef> positionRoleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            String instanceId,
            OrgsecObjectMapperFactory objectMapperFactory) {

        log.info("Creating typed InvalidationEventListener for instance: {}", instanceId);
        return new InvalidationEventListener(
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            positionRoleL1Cache,
            privilegeL1Cache,
            instanceId,
            objectMapperFactory.getEventObjectMapper()
        );
    }

    /**
     * Redis message listener container for Pub/Sub.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "orgsec.storage.redis.invalidation", name = "enabled", havingValue = "true")
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            InvalidationEventListener invalidationEventListener,
            ChannelTopic invalidationTopic) {

        log.info("Creating RedisMessageListenerContainer");
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(invalidationEventListener, invalidationTopic);
        return container;
    }

    // ==================== Preload ====================

    /**
     * Cache warmer for preloading data on startup.
     */
    @Bean
    @ConditionalOnMissingBean
    public CacheWarmer cacheWarmer(RedisStorageProperties properties) {
        log.info("Creating CacheWarmer with strategy: {}", properties.getPreload().getStrategy());
        return new CacheWarmer(properties.getPreload());
    }

    // ==================== Health ====================

    /**
     * Health indicator for Redis storage.
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisStorageHealthIndicator redisStorageHealthIndicator(
            RedisTemplate<String, String> orgsecRedisTemplate) {

        log.info("Creating RedisStorageHealthIndicator");
        return new RedisStorageHealthIndicator(orgsecRedisTemplate);
    }

    // ==================== Main Storage ====================

    /**
     * Main RedisSecurityDataStorage bean.
     *
     * <p>Deliberately not {@code @Primary}. When JWT storage is also active it is
     * {@code JwtSecurityDataStorage} that must be primary, and a {@code @Primary} marker on this
     * implementation made that a race between two auto-configurations. The primary designation
     * now lives on {@link #orgsecPrimaryStorage(RedisSecurityDataStorage)}, which is registered
     * only when JWT is off.
     */
    public RedisSecurityDataStorage redisSecurityDataStorage(
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

        log.info("Creating RedisSecurityDataStorage");
        RedisSecurityDataStorage storage = new RedisSecurityDataStorage(
            properties,
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            privilegeL1Cache,
            personL2Cache,
            organizationL2Cache,
            roleL2Cache,
            privilegeL2Cache,
            cacheKeyBuilder,
            invalidationPublisher,
            cacheWarmer
        );

        // Initialize on bean creation
        storage.initialize();

        return storage;
    }

    /**
     * Main storage bean with independent role caches. The legacy public factory method above is
     * retained so applications compiled against 1.0.4 keep linking.
     */
    @Bean("redisSecurityDataStorage")
    public RedisSecurityDataStorage typedRedisSecurityDataStorage(
            RedisStorageProperties properties,
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            @org.springframework.beans.factory.annotation.Qualifier("roleL1Cache")
            L1Cache<Long, RoleDef> roleL1Cache,
            @org.springframework.beans.factory.annotation.Qualifier("positionRoleL1Cache")
            L1Cache<Long, RoleDef> positionRoleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            L2RedisCache<PersonDef> personL2Cache,
            L2RedisCache<OrganizationDef> organizationL2Cache,
            L2RedisCache<RoleDef> roleL2Cache,
            L2RedisCache<PrivilegeDef> privilegeL2Cache,
            CacheKeyBuilder cacheKeyBuilder,
            InvalidationEventPublisher invalidationPublisher,
            CacheWarmer cacheWarmer) {

        log.info("Creating RedisSecurityDataStorage with typed role caches");
        RedisSecurityDataStorage storage = new RedisSecurityDataStorage(
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

    /**
     * Marks the Redis storage as the primary {@link com.nomendi6.orgsec.storage.SecurityDataStorage}
     * for Redis-only deployments.
     *
     * <p>Strictly an alias: it returns the very same instance, undecorated, so
     * {@code orgsecPrimaryStorage == redisSecurityDataStorage} holds. Anything else would give
     * invalidation callbacks and the health indicator a different object than the one serving
     * lookups.
     */
    @Bean("orgsecPrimaryStorage")
    @org.springframework.context.annotation.Primary
    @ConditionalOnMissingBean(name = "orgsecPrimaryStorage")
    @ConditionalOnProperty(
        name = "orgsec.storage.features.jwt-enabled",
        havingValue = "false",
        matchIfMissing = true
    )
    public com.nomendi6.orgsec.storage.SecurityDataStorage orgsecPrimaryStorage(
            RedisSecurityDataStorage redisSecurityDataStorage) {

        log.info("Configuring RedisSecurityDataStorage as PRIMARY storage (JWT storage not active)");
        return redisSecurityDataStorage;
    }
}
