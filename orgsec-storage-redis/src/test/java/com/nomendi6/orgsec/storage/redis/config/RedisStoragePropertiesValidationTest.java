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
        assertThatCode(() -> new RedisStorageProperties().afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    @Test
    void requiresAnExplicitSecurityDatasetId() {
        RedisStorageProperties missing = new RedisStorageProperties();
        missing.setEnabled(true);

        assertThatThrownBy(missing::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageStartingWith(RedisStorageProperties.INVALID_PROPERTY + ":")
            .hasMessageContaining("orgsec.storage.redis.security-dataset-id")
            .hasMessageContaining("must not be blank");

        RedisStorageProperties properties = validProperties();
        properties.setSecurityDatasetId("  ");

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.security-dataset-id")
            .hasMessageNotContaining("=  ");
    }

    @Test
    void doesNotRequireSecurityDatasetIdWhenRedisIsDisabled() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setEnabled(false);

        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void enforcesTheProtocolUtf8BoundaryForSecurityDatasetId() {
        RedisStorageProperties maximum = validProperties();
        maximum.setSecurityDatasetId("a".repeat(254) + "ž");
        assertThatCode(maximum::afterPropertiesSet).doesNotThrowAnyException();

        RedisStorageProperties oversized = validProperties();
        oversized.setSecurityDatasetId("a".repeat(255) + "ž");
        assertThatThrownBy(oversized::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.security-dataset-id")
            .hasMessageContaining("at most 256 UTF-8 bytes")
            .hasMessageNotContaining("a".repeat(32));
    }

    @Test
    void rejectsMalformedUnicodeInSecurityDatasetIdWithoutEchoingIt() {
        RedisStorageProperties properties = validProperties();
        properties.setSecurityDatasetId("raw-secret\uD800");

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.security-dataset-id")
            .hasMessageContaining("well-formed Unicode")
            .hasMessageNotContaining("raw-secret");
    }

    @Test
    void rejectsABlankHost() {
        RedisStorageProperties properties = validProperties();
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
        RedisStorageProperties properties = validProperties();
        properties.setTimeout(99);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.timeout")
            .hasMessageContaining("at least 100");
    }

    @Test
    void rejectsANonPositiveTtl() {
        RedisStorageProperties properties = validProperties();
        properties.getTtl().setPerson(0);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.ttl.person");
    }

    @Test
    void rejectsAnL1CacheSizeOutsideTheRange() {
        RedisStorageProperties properties = validProperties();
        properties.getCache().setL1MaxSize(0);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.cache.l1-max-size");
    }

    @Test
    void rejectsABlankInvalidationChannel() {
        RedisStorageProperties properties = validProperties();
        properties.getInvalidation().setChannel("");

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.invalidation.channel");
    }

    @Test
    void rejectsAFailureThresholdAbove100Percent() {
        RedisStorageProperties properties = validProperties();
        properties.getCircuitBreaker().setFailureThreshold(101);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.circuit-breaker.failure-threshold");
    }

    @Test
    void rejectsAnEmptyPool() {
        RedisStorageProperties properties = validProperties();
        properties.getPool().setMaxActive(0);

        assertThatThrownBy(properties::afterPropertiesSet)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("orgsec.storage.redis.pool.max-active");
    }

    @Test
    void acceptsABatchDelayOfZeroBecauseZeroMeansNoDelay() {
        RedisStorageProperties properties = validProperties();
        properties.getPreload().setBatchDelayMs(0);

        assertThatCode(properties::afterPropertiesSet).doesNotThrowAnyException();
    }

    private static RedisStorageProperties withPort(int port) {
        RedisStorageProperties properties = validProperties();
        properties.setPort(port);
        return properties;
    }

    private static RedisStorageProperties validProperties() {
        RedisStorageProperties properties = new RedisStorageProperties();
        properties.setEnabled(true);
        properties.setSecurityDatasetId("orgsec-test");
        return properties;
    }
}
