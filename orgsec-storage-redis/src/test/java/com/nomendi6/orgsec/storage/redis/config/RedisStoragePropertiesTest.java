package com.nomendi6.orgsec.storage.redis.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStoragePropertiesTest {

    @Test
    void shouldExposeDefaultValues() {
        RedisStorageProperties properties = new RedisStorageProperties();

        assertThat(properties.getHost()).isEqualTo("localhost");
        assertThat(properties.getPort()).isEqualTo(6379);
        assertThat(properties.getTimeout()).isEqualTo(2000);
        assertThat(properties.getTtl().getPerson()).isEqualTo(3600);
        assertThat(properties.getTtl().getOrganization()).isEqualTo(7200);
        assertThat(properties.getTtl().getRole()).isEqualTo(7200);
        assertThat(properties.getTtl().getPrivilege()).isEqualTo(7200);
        assertThat(properties.getTtl().getOnSecurityChange()).isEqualTo(300);
        assertThat(properties.getCache().isL1Enabled()).isTrue();
        assertThat(properties.getCache().getL1MaxSize()).isEqualTo(1000);
        assertThat(properties.getInvalidation().getChannel()).isEqualTo("orgsec:invalidation");
        assertThat(properties.getPreload().getStrategy()).isEqualTo("all");
        assertThat(properties.getPreload().getMode()).isEqualTo("eager");
        assertThat(properties.getCircuitBreaker().getFailureThreshold()).isEqualTo(50);
        assertThat(properties.getMonitoring().isMetricsEnabled()).isTrue();
        assertThat(properties.getPool().getMaxActive()).isEqualTo(20);
        assertThat(properties.getSerialization().isStrictMode()).isFalse();
        assertThat(properties.getAudit().isEnabled()).isFalse();
    }

    @Test
    void shouldSetTopLevelNestedConfigs() {
        RedisStorageProperties properties = new RedisStorageProperties();
        RedisStorageProperties.TtlConfig ttl = new RedisStorageProperties.TtlConfig();
        RedisStorageProperties.CacheConfig cache = new RedisStorageProperties.CacheConfig();
        RedisStorageProperties.InvalidationConfig invalidation = new RedisStorageProperties.InvalidationConfig();
        RedisStorageProperties.PreloadConfig preload = new RedisStorageProperties.PreloadConfig();
        RedisStorageProperties.CircuitBreakerConfig circuitBreaker = new RedisStorageProperties.CircuitBreakerConfig();
        RedisStorageProperties.MonitoringConfig monitoring = new RedisStorageProperties.MonitoringConfig();
        RedisStorageProperties.PoolConfig pool = new RedisStorageProperties.PoolConfig();
        RedisStorageProperties.SerializationConfig serialization = new RedisStorageProperties.SerializationConfig();
        RedisStorageProperties.AuditConfig audit = new RedisStorageProperties.AuditConfig();

        properties.setHost("redis");
        properties.setPort(6380);
        properties.setPassword("secret");
        properties.setTimeout(999);
        properties.setTtl(ttl);
        properties.setCache(cache);
        properties.setInvalidation(invalidation);
        properties.setPreload(preload);
        properties.setCircuitBreaker(circuitBreaker);
        properties.setMonitoring(monitoring);
        properties.setPool(pool);
        properties.setSerialization(serialization);
        properties.setAudit(audit);

        assertThat(properties.getHost()).isEqualTo("redis");
        assertThat(properties.getPort()).isEqualTo(6380);
        assertThat(properties.getPassword()).isEqualTo("secret");
        assertThat(properties.getTimeout()).isEqualTo(999);
        assertThat(properties.getTtl()).isSameAs(ttl);
        assertThat(properties.getCache()).isSameAs(cache);
        assertThat(properties.getInvalidation()).isSameAs(invalidation);
        assertThat(properties.getPreload()).isSameAs(preload);
        assertThat(properties.getCircuitBreaker()).isSameAs(circuitBreaker);
        assertThat(properties.getMonitoring()).isSameAs(monitoring);
        assertThat(properties.getPool()).isSameAs(pool);
        assertThat(properties.getSerialization()).isSameAs(serialization);
        assertThat(properties.getAudit()).isSameAs(audit);
    }

    @Test
    void shouldSetAllNestedConfigValues() {
        RedisStorageProperties.TtlConfig ttl = new RedisStorageProperties.TtlConfig();
        ttl.setPerson(1);
        ttl.setOrganization(2);
        ttl.setRole(3);
        ttl.setPrivilege(4);
        ttl.setOnSecurityChange(5);

        RedisStorageProperties.CacheConfig cache = new RedisStorageProperties.CacheConfig();
        cache.setL1Enabled(false);
        cache.setL1MaxSize(10);
        cache.setObfuscateKeys(true);

        RedisStorageProperties.InvalidationConfig invalidation = new RedisStorageProperties.InvalidationConfig();
        invalidation.setEnabled(false);
        invalidation.setAsync(false);
        invalidation.setChannel("channel");

        RedisStorageProperties.PreloadConfig preload = new RedisStorageProperties.PreloadConfig();
        preload.setEnabled(false);
        preload.setOnStartup(false);
        preload.setStrategy("roles");
        preload.setMode("progressive");
        preload.setBatchSize(11);
        preload.setBatchDelayMs(12);
        preload.setAsync(true);
        preload.setParallelism(3);

        RedisStorageProperties.CircuitBreakerConfig circuitBreaker = new RedisStorageProperties.CircuitBreakerConfig();
        circuitBreaker.setEnabled(false);
        circuitBreaker.setFailureThreshold(60);
        circuitBreaker.setWaitDuration(2000);
        circuitBreaker.setSlidingWindowSize(12);
        circuitBreaker.setMinimumCalls(4);
        circuitBreaker.setPermittedCallsInHalfOpen(2);

        RedisStorageProperties.MonitoringConfig monitoring = new RedisStorageProperties.MonitoringConfig();
        monitoring.setMetricsEnabled(false);
        monitoring.setHealthCheckEnabled(false);

        RedisStorageProperties.PoolConfig pool = new RedisStorageProperties.PoolConfig();
        pool.setEnabled(false);
        pool.setMinIdle(1);
        pool.setMaxIdle(2);
        pool.setMaxActive(3);
        pool.setMaxWait(4);
        pool.setTestWhileIdle(false);
        pool.setTimeBetweenEvictionRuns(5000);
        pool.setMinEvictableIdleTime(6000);

        RedisStorageProperties.SerializationConfig serialization = new RedisStorageProperties.SerializationConfig();
        serialization.setFailOnUnknownProperties(true);
        serialization.setStrictMode(true);

        RedisStorageProperties.AuditConfig audit = new RedisStorageProperties.AuditConfig();
        audit.setEnabled(true);
        audit.setLogCacheAccess(true);
        audit.setLogPrivilegeChecks(false);
        audit.setLogConfigChanges(false);

        assertThat(ttl.getPerson()).isEqualTo(1);
        assertThat(ttl.getOrganization()).isEqualTo(2);
        assertThat(ttl.getRole()).isEqualTo(3);
        assertThat(ttl.getPrivilege()).isEqualTo(4);
        assertThat(ttl.getOnSecurityChange()).isEqualTo(5);
        assertThat(cache.isL1Enabled()).isFalse();
        assertThat(cache.getL1MaxSize()).isEqualTo(10);
        assertThat(cache.isObfuscateKeys()).isTrue();
        assertThat(invalidation.isEnabled()).isFalse();
        assertThat(invalidation.isAsync()).isFalse();
        assertThat(invalidation.getChannel()).isEqualTo("channel");
        assertThat(preload.isEnabled()).isFalse();
        assertThat(preload.isOnStartup()).isFalse();
        assertThat(preload.getStrategy()).isEqualTo("roles");
        assertThat(preload.getMode()).isEqualTo("progressive");
        assertThat(preload.getBatchSize()).isEqualTo(11);
        assertThat(preload.getBatchDelayMs()).isEqualTo(12);
        assertThat(preload.isAsync()).isTrue();
        assertThat(preload.getParallelism()).isEqualTo(3);
        assertThat(circuitBreaker.isEnabled()).isFalse();
        assertThat(circuitBreaker.getFailureThreshold()).isEqualTo(60);
        assertThat(circuitBreaker.getWaitDuration()).isEqualTo(2000);
        assertThat(circuitBreaker.getSlidingWindowSize()).isEqualTo(12);
        assertThat(circuitBreaker.getMinimumCalls()).isEqualTo(4);
        assertThat(circuitBreaker.getPermittedCallsInHalfOpen()).isEqualTo(2);
        assertThat(monitoring.isMetricsEnabled()).isFalse();
        assertThat(monitoring.isHealthCheckEnabled()).isFalse();
        assertThat(pool.isEnabled()).isFalse();
        assertThat(pool.getMinIdle()).isEqualTo(1);
        assertThat(pool.getMaxIdle()).isEqualTo(2);
        assertThat(pool.getMaxActive()).isEqualTo(3);
        assertThat(pool.getMaxWait()).isEqualTo(4);
        assertThat(pool.isTestWhileIdle()).isFalse();
        assertThat(pool.getTimeBetweenEvictionRuns()).isEqualTo(5000);
        assertThat(pool.getMinEvictableIdleTime()).isEqualTo(6000);
        assertThat(serialization.isFailOnUnknownProperties()).isTrue();
        assertThat(serialization.isStrictMode()).isTrue();
        assertThat(audit.isEnabled()).isTrue();
        assertThat(audit.isLogCacheAccess()).isTrue();
        assertThat(audit.isLogPrivilegeChecks()).isFalse();
        assertThat(audit.isLogConfigChanges()).isFalse();
    }
}
