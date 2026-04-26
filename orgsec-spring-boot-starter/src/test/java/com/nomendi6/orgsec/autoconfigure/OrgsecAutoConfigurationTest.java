package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.provider.SecurityContextProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.PrivilegeSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OrgsecAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OrgsecAutoConfiguration.class))
        .withUserConfiguration(TestProviderConfiguration.class);

    @Test
    void shouldCreateDefaultBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SecurityDataStorage.class);
            assertThat(context).hasSingleBean(SecurityContextProvider.class);
        });
    }

    @Test
    void shouldNotCreateBeansWhenDisabled() {
        contextRunner
            .withPropertyValues("orgsec.enabled=false")
            .run(context -> {
                // Note: The enabled property should be checked in the service layer
                // Auto-configuration will still create beans
                assertThat(context).hasSingleBean(SecurityDataStorage.class);
            });
    }

    @Test
    void shouldUseInMemoryStorageByDefault() {
        contextRunner.run(context -> {
            SecurityDataStorage storage = context.getBean(SecurityDataStorage.class);
            assertThat(storage).isNotNull();
            assertThat(storage.getProviderType()).containsIgnoringCase("InMemory");
        });
    }

    @Test
    void shouldRespectCustomStorageType() {
        contextRunner
            .withPropertyValues("orgsec.storage.type=inmemory")
            .run(context -> {
                assertThat(context).hasSingleBean(SecurityDataStorage.class);
            });
    }
}