package com.nomendi6.orgsec.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.apache.commons.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * The storage flags are checked before the context exists, so this validator is exercised
 * directly rather than through an {@code ApplicationContextRunner} - a runner never invokes
 * {@code EnvironmentPostProcessor}s, which is precisely why the check used to be unreachable.
 */
class OrgsecStorageActivationValidatorTest {

    private Log log;
    private OrgsecStorageActivationValidator validator;

    @BeforeEach
    void setUp() {
        log = mock(Log.class);
        DeferredLogFactory logFactory = supplier -> log;
        validator = new OrgsecStorageActivationValidator(logFactory);
    }

    @Test
    void shouldStaySilentWhenBothRedisFlagsAgree() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, "true")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED, "true");

        assertThatCode(() -> validator.postProcessEnvironment(environment, new SpringApplication()))
            .doesNotThrowAnyException();
        verify(log, never()).warn(any());
    }

    @Test
    void shouldStaySilentWhenNeitherRedisFlagIsSet() {
        assertThatCode(() -> validator.postProcessEnvironment(new MockEnvironment(), new SpringApplication()))
            .doesNotThrowAnyException();
        verify(log, never()).warn(any());
    }

    @Test
    void shouldWarnAndKeepBootingOnMismatchByDefault() {
        // Exactly what applications generated against 1.0.4 emit. They must survive the upgrade.
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, "true")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED, "false");

        assertThatCode(() -> validator.postProcessEnvironment(environment, new SpringApplication()))
            .doesNotThrowAnyException();

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(log).warn(message.capture());
        assertThat(String.valueOf(message.getValue()))
            .contains(OrgsecStorageActivationValidator.REDIS_ENABLED)
            .contains(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED)
            .contains(OrgsecStorageActivationValidator.STRICT_ACTIVATION);
    }

    @Test
    void shouldWarnOnTheReverseMismatchToo() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, "false")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED, "true");

        assertThatCode(() -> validator.postProcessEnvironment(environment, new SpringApplication()))
            .doesNotThrowAnyException();
        verify(log).warn(any());
    }

    @Test
    void shouldRefuseToStartOnMismatchWhenStrictActivationIsOn() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, "true")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED, "false")
            .withProperty(OrgsecStorageActivationValidator.STRICT_ACTIVATION, "true");

        assertThatThrownBy(() -> validator.postProcessEnvironment(environment, new SpringApplication()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(OrgsecStorageActivationValidator.REDIS_ENABLED)
            .hasMessageContaining(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED);
    }

    @Test
    void shouldReadFlagsCaseInsensitivelyLikeConditionalOnProperty() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.REDIS_ENABLED, "TRUE")
            .withProperty(OrgsecStorageActivationValidator.FEATURES_REDIS_ENABLED, "True");

        assertThatCode(() -> validator.postProcessEnvironment(environment, new SpringApplication()))
            .doesNotThrowAnyException();
        verify(log, never()).warn(any());
    }

    @Test
    void shouldAcceptJwtEnabledWhenTheJwtModuleIsOnTheClasspath() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.FEATURES_JWT_ENABLED, "true");

        assertThatCode(() -> validator.postProcessEnvironment(environment, new SpringApplication()))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseToStartWhenJwtIsEnabledWithoutTheJwtModule() {
        // Without the module nothing is @Primary at all: the in-memory storage has already stood
        // down. A NoSuchBeanDefinition deep in the graph is not a diagnosis.
        MockEnvironment environment = new MockEnvironment()
            .withProperty(OrgsecStorageActivationValidator.FEATURES_JWT_ENABLED, "true");

        SpringApplication application = new SpringApplication();
        application.setResourceLoader(
            new DefaultResourceLoader(new FilteredClassLoader("com.nomendi6.orgsec.storage.jwt.JwtSecurityDataStorage"))
        );

        assertThatThrownBy(() -> validator.postProcessEnvironment(environment, application))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("orgsec-storage-jwt")
            .hasMessageContaining(OrgsecStorageActivationValidator.FEATURES_JWT_ENABLED);
    }
}
