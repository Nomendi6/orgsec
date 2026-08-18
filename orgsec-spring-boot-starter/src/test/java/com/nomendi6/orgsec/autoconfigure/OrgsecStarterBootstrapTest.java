package com.nomendi6.orgsec.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import com.nomendi6.orgsec.provider.PersonDataProvider;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.provider.UserDataProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.InMemorySecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.StorageConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

class OrgsecStarterBootstrapTest {

    private static final String PERSON_API_DISABLED = "--orgsec.api.person.enabled=false";
    private static final String EXCLUDE_DATA_REDIS =
        "--spring.autoconfigure.exclude=org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration";

    @Test
    void springFactoriesRegistersTheActivationValidator() throws IOException {
        Properties factories = new Properties();
        try (InputStream in = OrgsecStarterBootstrapTest.class.getClassLoader()
            .getResourceAsStream("META-INF/spring.factories")) {
            assertThat(in).as("META-INF/spring.factories must be on the starter classpath").isNotNull();
            factories.load(in);
        }
        assertThat(factories.getProperty("org.springframework.boot.EnvironmentPostProcessor"))
            .contains(OrgsecStorageActivationValidator.class.getName());
    }

    @Test
    void autoConfigurationImportsListsTheStarterEntries() throws IOException {
        try (InputStream in = OrgsecStarterBootstrapTest.class.getClassLoader()
            .getResourceAsStream("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).isNotNull();
            String imports = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(imports).contains(OrgsecAutoConfiguration.class.getName());
            assertThat(imports).contains(PersonApiServiceConfiguration.class.getName());
        }
    }

    @Test
    void springApplicationStartsWithInMemoryPrimaryAndNamedDelegate() {
        SpringApplication application = new SpringApplication(BootstrapApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(
            "--spring.main.banner-mode=off",
            "--spring.main.log-startup-info=false",
            PERSON_API_DISABLED,
            EXCLUDE_DATA_REDIS
        )) {
            SecurityDataStorage primary = context.getBean(SecurityDataStorage.class);
            assertThat(primary).isInstanceOf(InMemorySecurityDataStorage.class);
            assertThat(context.getBean("primaryInMemoryStorage")).isSameAs(primary);
            assertThat(context.getBean("delegateSecurityDataStorage")).isSameAs(primary);
            assertThat(context.getBean("inMemorySecurityDataStorage")).isSameAs(primary);
        }
    }

    @Test
    void springApplicationRefusesJwtTogetherWithRedis() {
        SpringApplication application = new SpringApplication(BootstrapApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> application.run(
            "--spring.main.banner-mode=off",
            PERSON_API_DISABLED,
            "--orgsec.storage.redis.enabled=true",
            "--orgsec.storage.features.redis-enabled=true",
            "--orgsec.storage.features.jwt-enabled=true"
        ))
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageStartingWith(OrgsecStorageActivationValidator.JWT_REDIS_UNSUPPORTED + ":");
    }

    @Test
    void springApplicationRefusesMismatchedRedisFlags() {
        SpringApplication application = new SpringApplication(BootstrapApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> application.run(
            "--spring.main.banner-mode=off",
            PERSON_API_DISABLED,
            "--orgsec.storage.redis.enabled=true",
            "--orgsec.storage.features.redis-enabled=false"
        ))
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageStartingWith(OrgsecStorageActivationValidator.REDIS_ACTIVATION_MISMATCH + ":");
    }

    /**
     * Real {@link SpringApplication} source. {@code @SpringBootApplication} is deliberately
     * avoided: that annotation would component-scan this test package and pick up
     * {@link TestProviderConfiguration}'s {@code @Primary} mock storage.
     */
    @Configuration
    @EnableAutoConfiguration
    @ComponentScan(
        basePackages = {
            "com.nomendi6.orgsec.storage.inmemory",
            "com.nomendi6.orgsec.common"
        },
        excludeFilters = @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = StorageConfiguration.class
        )
    )
    static class BootstrapApp {

        @Bean
        SecurityQueryProvider securityQueryProvider() {
            return mock(SecurityQueryProvider.class);
        }

        @Bean
        PersonDataProvider personDataProvider() {
            return mock(PersonDataProvider.class);
        }

        @Bean
        UserDataProvider userDataProvider() {
            return mock(UserDataProvider.class);
        }
    }
}
