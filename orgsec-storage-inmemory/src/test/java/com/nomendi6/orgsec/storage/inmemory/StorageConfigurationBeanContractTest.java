package com.nomendi6.orgsec.storage.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.OrganizationLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.RoleLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPrivilegesStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StorageConfigurationBeanContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StorageConfiguration.class))
        .withUserConfiguration(InMemoryStorageBeans.class);

    @Test
    void memoryPrimaryAndPublicDelegateAreTheSameObject() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("primaryInMemoryStorage");
            assertThat(context).hasBean("delegateSecurityDataStorage");
            assertThat(context).doesNotHaveBean("jwtDelegateStorage");
            assertThat(context.getBean("primaryInMemoryStorage"))
                .isSameAs(context.getBean("delegateSecurityDataStorage"))
                .isSameAs(context.getBean(InMemorySecurityDataStorage.class));
        });
    }

    @Test
    void jwtProfilePublishesAnExplicitDelegateWithoutAnInMemoryPrimary() {
        contextRunner
            .withPropertyValues("orgsec.storage.features.jwt-enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean("primaryInMemoryStorage");
                assertThat(context).hasBean("delegateSecurityDataStorage");
                assertThat(context).hasBean("jwtDelegateStorage");
                assertThat(context.getBean("jwtDelegateStorage"))
                    .isSameAs(context.getBean("delegateSecurityDataStorage"));
            });
    }

    @Test
    void applicationCanReplaceThePublicDelegateByName() {
        contextRunner
            .withUserConfiguration(CustomDelegate.class)
            .withPropertyValues("orgsec.storage.features.jwt-enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("delegateSecurityDataStorage", SecurityDataStorage.class).getProviderType())
                    .isEqualTo("application-delegate");
                assertThat(context.getBean("jwtDelegateStorage"))
                    .isSameAs(context.getBean("delegateSecurityDataStorage"));
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class InMemoryStorageBeans {

        @Bean
        InMemorySecurityDataStorage inMemorySecurityDataStorage() {
            AllPersonsStore persons = new AllPersonsStore();
            AllOrganizationsStore organizations = new AllOrganizationsStore();
            AllRolesStore roles = new AllRolesStore();
            AllPrivilegesStore privileges = new AllPrivilegesStore();
            return new InMemorySecurityDataStorage(
                persons,
                organizations,
                roles,
                privileges,
                new PersonLoader(roles, persons),
                new OrganizationLoader(roles, organizations),
                new RoleLoader(privileges, roles),
                new PrivilegeLoader(privileges),
                mock(SecurityQueryProvider.class)
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomDelegate {

        @Bean("delegateSecurityDataStorage")
        SecurityDataStorage delegateSecurityDataStorage() {
            SecurityDataStorage storage = mock(SecurityDataStorage.class);
            when(storage.getProviderType()).thenReturn("application-delegate");
            return storage;
        }
    }
}
