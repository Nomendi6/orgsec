package com.nomendi6.orgsec.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

class OrgsecStorageActivationValidatorTest {

    private OrgsecStorageActivationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OrgsecStorageActivationValidator(mock(DeferredLogFactory.class));
    }

    @Test
    void acceptsInMemoryConfigurationWhenBothRedisFlagsAreAbsent() {
        assertThatCode(() -> validate(new MockEnvironment(), new SpringApplication()))
            .doesNotThrowAnyException();
    }

    @Test
    void acceptsRedisConfigurationWhenBothFlagsAreTrueAndModuleIsPresent() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, "true")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED, "true");

        assertThatCode(() -> validate(environment, new SpringApplication()))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsRedisEnabledWithFeatureDisabledUsingStableDiagnostic() {
        assertRedisMismatch(true, false);
    }

    @Test
    void rejectsRedisDisabledWithFeatureEnabledUsingSameStableDiagnostic() {
        assertRedisMismatch(false, true);
    }

    @Test
    void rejectsJwtWhenJwtModuleIsMissing() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.FEATURES_JWT_ENABLED, "true");
        SpringApplication application = applicationWithout(
            "com.nomendi6.orgsec.storage.jwt.JwtSecurityDataStorage"
        );

        assertThatThrownBy(() -> validate(environment, application))
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageStartingWith(OrgsecStorageActivationValidator.JWT_MODULE_REQUIRED + ":")
            .hasMessageContaining("orgsec-storage-jwt");
    }

    @Test
    void rejectsRedisWhenRedisModuleIsMissing() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, "true")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED, "true");
        SpringApplication application = applicationWithout(
            "com.nomendi6.orgsec.storage.redis.RedisSecurityDataStorage"
        );

        assertThatThrownBy(() -> validate(environment, application))
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageStartingWith(OrgsecStorageActivationValidator.REDIS_MODULE_REQUIRED + ":")
            .hasMessageContaining("orgsec-storage-redis");
    }

    @Test
    void rejectsUnspecifiedJwtRedisHybridBeforeBeanCreation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, "true")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED, "true")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_JWT_ENABLED, "true");

        assertThatThrownBy(() -> validate(environment, new SpringApplication()))
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageStartingWith(OrgsecStorageActivationValidator.JWT_REDIS_UNSUPPORTED + ":");
    }

    private void assertRedisMismatch(boolean redisEnabled, boolean featureEnabled) {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, Boolean.toString(redisEnabled))
            .withProperty(
                OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED,
                Boolean.toString(featureEnabled)
            );

        assertThatThrownBy(() -> validate(environment, new SpringApplication()))
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageStartingWith(OrgsecStorageActivationValidator.REDIS_ACTIVATION_MISMATCH + ":")
            .hasMessageContaining(OrgsecStorageActivationValidator.REDIS_ENABLED)
            .hasMessageContaining(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED);
    }

    private void validate(MockEnvironment environment, SpringApplication application) {
        validator.postProcessEnvironment(environment, application);
    }

    private static SpringApplication applicationWithout(String className) {
        SpringApplication application = new SpringApplication();
        application.setResourceLoader(new DefaultResourceLoader(new FilteredClassLoader(className)));
        return application;
    }
}
