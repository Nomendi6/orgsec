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
import com.nomendi6.orgsec.storage.redis.resilience.RedisStorageMigrationRequiredException;
import com.nomendi6.orgsec.storage.redis.serialization.JsonSerializer;
import com.nomendi6.orgsec.storage.redis.serialization.OrgsecObjectMapperFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RedisStorageAutoConfigurationTest {

    private RedisStorageAutoConfiguration configuration;
    private RedisStorageProperties properties;
    private OrgsecObjectMapperFactory objectMapperFactory;

    @BeforeEach
    void setUp() {
        configuration = new RedisStorageAutoConfiguration();
        properties = new RedisStorageProperties();
        properties.getPreload().setEnabled(false);
        objectMapperFactory = new OrgsecObjectMapperFactory(properties.getSerialization());
    }

    @Test
    void shouldCreateInstanceIds() {
        String first = configuration.instanceId();
        String second = configuration.instanceId();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }

    @Test
    void shouldCreateCoreInfrastructureBeans() {
        properties.getCache().setObfuscateKeys(true);
        properties.getSerialization().setStrictMode(true);

        OrgsecObjectMapperFactory factory = configuration.orgsecObjectMapperFactory(properties);
        CacheKeyBuilder keyBuilder = configuration.cacheKeyBuilder(properties);
        RedisCircuitBreakerService circuitBreakerService = configuration.redisCircuitBreakerService(properties);

        assertThat(factory.getConfig()).isSameAs(properties.getSerialization());
        assertThat(keyBuilder.buildPersonKey(1L)).doesNotContain("person:1");
        assertThat(circuitBreakerService.isEnabled()).isTrue();
    }

    @Test
    void shouldCreateAuditLoggerVariants() {
        properties.getAudit().setEnabled(false);
        SecurityAuditLogger disabled = configuration.securityAuditLogger(properties);

        properties.getAudit().setEnabled(true);
        properties.getAudit().setLogCacheAccess(true);
        SecurityAuditLogger enabled = configuration.securityAuditLogger(properties);

        assertThat(disabled).isInstanceOf(NoOpSecurityAuditLogger.class);
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(enabled).isInstanceOf(DefaultSecurityAuditLogger.class);
        assertThat(enabled.isEnabled()).isTrue();
    }

    @Test
    void shouldCreateSerializersForDomainTypes() {
        JsonSerializer<PersonDef> personSerializer = configuration.personSerializer(objectMapperFactory);
        JsonSerializer<OrganizationDef> organizationSerializer = configuration.organizationSerializer(objectMapperFactory);
        JsonSerializer<RoleDef> roleSerializer = configuration.roleSerializer(objectMapperFactory);
        JsonSerializer<PrivilegeDef> privilegeSerializer = configuration.privilegeSerializer(objectMapperFactory);

        assertThat(personSerializer.getTargetType()).isEqualTo(PersonDef.class);
        assertThat(organizationSerializer.getTargetType()).isEqualTo(OrganizationDef.class);
        assertThat(roleSerializer.getTargetType()).isEqualTo(RoleDef.class);
        assertThat(privilegeSerializer.getTargetType()).isEqualTo(PrivilegeDef.class);
    }

    @Test
    void shouldCreateRedisTemplateWithStringSerializers() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        RedisTemplate<String, String> template = configuration.orgsecRedisTemplate(connectionFactory);

        assertThat(template.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(template.getValueSerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
        assertThat(template.getHashValueSerializer()).isInstanceOf(StringRedisSerializer.class);
    }

    @Test
    void shouldCreateL1CachesUsingConfiguredMaxSize() {
        properties.getCache().setL1MaxSize(7);

        assertThat(configuration.personL1Cache(properties).getMaxSize()).isEqualTo(7);
        assertThat(configuration.organizationL1Cache(properties).getMaxSize()).isEqualTo(7);
        assertThat(configuration.roleL1Cache(properties).getMaxSize()).isEqualTo(7);
        assertThat(configuration.positionRoleL1Cache(properties).getMaxSize()).isEqualTo(7);
        assertThat(configuration.privilegeL1Cache(properties).getMaxSize()).isEqualTo(7);
    }

    @Test
    void shouldCreateL2Caches() {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        CacheKeyBuilder keyBuilder = new CacheKeyBuilder(false);
        RedisCircuitBreakerService circuitBreakerService = configuration.redisCircuitBreakerService(properties);

        L2RedisCache<PersonDef> personCache = configuration.personL2Cache(
                template, configuration.personSerializer(objectMapperFactory), keyBuilder, circuitBreakerService);
        L2RedisCache<OrganizationDef> organizationCache = configuration.organizationL2Cache(
                template, configuration.organizationSerializer(objectMapperFactory), keyBuilder, circuitBreakerService);
        L2RedisCache<RoleDef> roleCache = configuration.roleL2Cache(
                template, configuration.roleSerializer(objectMapperFactory), keyBuilder, circuitBreakerService);
        L2RedisCache<PrivilegeDef> privilegeCache = configuration.privilegeL2Cache(
                template, configuration.privilegeSerializer(objectMapperFactory), keyBuilder, circuitBreakerService);

        assertThat(personCache.getRedisTemplate()).isSameAs(template);
        assertThat(organizationCache.getRedisTemplate()).isSameAs(template);
        assertThat(roleCache.getRedisTemplate()).isSameAs(template);
        assertThat(privilegeCache.getRedisTemplate()).isSameAs(template);
    }

    @Test
    void shouldCreateInvalidationBeans() {
        properties.getInvalidation().setChannel("test:invalidations");
        properties.getInvalidation().setAsync(false);
        RedisTemplate<String, String> template = new RedisTemplate<>();
        String instanceId = "instance-1";
        ChannelTopic topic = configuration.invalidationTopic(properties);

        InvalidationEventPublisher publisher = configuration.invalidationEventPublisher(
                template, topic, instanceId, properties, objectMapperFactory);
        InvalidationEventListener listener = configuration.invalidationEventListener(
                new L1Cache<>(2), new L1Cache<>(2), new L1Cache<>(2), instanceId, objectMapperFactory);
        RedisMessageListenerContainer container = configuration.redisMessageListenerContainer(
                mock(RedisConnectionFactory.class), listener, topic);

        assertThat(topic.getTopic()).isEqualTo("test:invalidations");
        assertThat(publisher.getChannel()).isEqualTo("test:invalidations");
        assertThat(publisher.isAsync()).isFalse();
        assertThat(publisher.getInstanceId()).isEqualTo(instanceId);
        assertThat(listener.getInstanceId()).isEqualTo(instanceId);
        assertThat(container).isNotNull();
    }

    @Test
    void shouldCreatePreloadAndReadinessAwareHealthBeans() {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        CacheWarmer warmer = configuration.cacheWarmer(properties);
        RedisStorageHealthIndicator healthIndicator = configuration.redisStorageHealthIndicator(template);
        RedisSecurityDataStorage storage = mock(RedisSecurityDataStorage.class);
        RedisStorageHealthIndicator managedHealth =
            configuration.fencedRedisStorageHealthIndicator(template, storage);

        assertThat(warmer).isNotNull();
        assertThat(healthIndicator).isNotNull();
        assertThat(managedHealth).isNotNull();
    }

    @Test
    void bothRetainedLegacyStorageFactoriesRejectDependencyOnlyMigration() {
        CacheWarmer warmer = configuration.cacheWarmer(properties);
        InvalidationEventPublisher publisher = mock(InvalidationEventPublisher.class);
        L1Cache<Long, PersonDef> persons = new L1Cache<>(10);
        L1Cache<Long, OrganizationDef> organizations = new L1Cache<>(10);
        L1Cache<Long, RoleDef> partyRoles = new L1Cache<>(10);
        L1Cache<Long, RoleDef> positionRoles = new L1Cache<>(10);
        L1Cache<String, PrivilegeDef> privileges = new L1Cache<>(10);
        L2RedisCache<PersonDef> personL2 = mock(L2RedisCache.class);
        L2RedisCache<OrganizationDef> organizationL2 = mock(L2RedisCache.class);
        L2RedisCache<RoleDef> roleL2 = mock(L2RedisCache.class);
        L2RedisCache<PrivilegeDef> privilegeL2 = mock(L2RedisCache.class);

        assertLegacyMigrationFailure(() -> configuration.redisSecurityDataStorage(
                properties,
                persons,
                organizations,
                partyRoles,
                privileges,
                personL2,
                organizationL2,
                roleL2,
                privilegeL2,
                new CacheKeyBuilder(false),
                publisher,
                warmer));

        assertLegacyMigrationFailure(() -> configuration.typedRedisSecurityDataStorage(
            properties,
            persons,
            organizations,
            partyRoles,
            positionRoles,
            privileges,
            personL2,
            organizationL2,
            roleL2,
            privilegeL2,
            new CacheKeyBuilder(false),
            publisher,
            warmer));
    }

    private static void assertLegacyMigrationFailure(Runnable factoryCall) {
        assertThatThrownBy(factoryCall::run)
            .isInstanceOf(RedisStorageMigrationRequiredException.class)
            .hasMessageContaining(RedisStorageMigrationRequiredException.DIAGNOSTIC_CODE);
    }
}
