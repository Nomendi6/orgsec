package com.nomendi6.orgsec.storage.redis.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import org.junit.jupiter.api.Test;

/**
 * Redis property validation, in code rather than through Bean Validation.
 *
 * <p>The annotations that used to do this required {@code jakarta.validation-api} on the classpath.
 * Spring Boot reads that as "JSR-303 is available" and tries to bootstrap a validator while binding
 * {@code @Validated} properties - but the module shipped the API without a provider, so an
 * application that enabled Redis and had no validation provider of its own failed to start with
 * {@code NoProviderFoundException}. No Redis test caught it because none of them built a Spring
 * context: they all constructed objects directly, so binding never ran.
 */
class RedisStoragePropertiesValidationTest {

    @Test
    void defaultsAreValid() {
        assertThatCode(() -> new RedisStorageProperties().afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void rejectsABlankHost() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setHost("  ");

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageStartingWith(RedisStorageProperties.INVALID_PROPERTY + ":")
            .hasMessageContaining("orgsec.storage.redis.host");
    }

    @Test
    void rejectsAPortOutsideTheValidRange() {
        assertThatThrownBy(() -> withPort(0).afterPropertiesSet())
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.port");
        assertThatThrownBy(() -> withPort(65_536).afterPropertiesSet())
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.port");
        assertThatCode(() -> withPort(65_535).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void rejectsATimeoutBelowTheFloor() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setTimeout(99);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.timeout")
            .hasMessageContaining("at least 100");
    }

    @Test
    void rejectsANonPositiveTtl() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getTtl().setPerson(0);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.ttl.person");
    }

    @Test
    void rejectsAnL1CacheSizeOutsideTheRange() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getCache().setL1MaxSize(0);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.cache.l1-max-size");
    }

    @Test
    void rejectsABlankInvalidationChannel() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getInvalidation().setChannel("");

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.invalidation.channel");
    }

    @Test
    void rejectsAFailureThresholdAbove100Percent() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getCircuitBreaker().setFailureThreshold(101);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.circuit-breaker.failure-threshold");
    }

    @Test
    void rejectsAnEmptyPool() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getPool().setMaxActive(0);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.pool.max-active");
    }

    @Test
    void acceptsABatchDelayOfZeroBecauseZeroMeansNoDelay() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.getPreload().setBatchDelayMs(0);

        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
    }

    private static RedisStorageProperties withPort(int port) {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setPort(port);
        return properties;
    }
}
