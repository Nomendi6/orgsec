package com.nomendi6.orgsec.storage.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

/**
 * Bean-name and identity contract of {@link StorageConfiguration}.
 *
 * <p>The names are load-bearing: {@code JwtSecurityDataStorage} injects its delegate by qualifier,
 * and applications substitute their own store by declaring a bean under one of these names. Two
 * things are new in 1.0.5 and covered here - an application-declared
 * {@code delegateSecurityDataStorage} now replaces the library's instead of colliding with it, and
 * the JWT delegate defaults to the in-memory storage rather than to whatever happens to be
 * {@code @Primary}.
 */
class StorageConfigurationBeanContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(StorageConfiguration.class))
        .withUserConfiguration(InMemoryStorageBeans.class);

    @Test
    void memoryOnlyExposesPrimaryAndDelegateAsTheSameInstance() {
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
    void jwtEnabledStandsDownFromPrimaryAndPublishesTheInMemoryDelegate() {
        contextRunner
            .withPropertyValues("orgsec.storage.features.jwt-enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean("primaryInMemoryStorage");
                assertThat(context).hasBean("jwtDelegateStorage");

                // Never a decorator: the JWT storage and any invalidation callback must see one object.
                assertThat(context.getBean("jwtDelegateStorage"))
                    .isSameAs(context.getBean("delegateSecurityDataStorage"));
            });
    }

    @Test
    void applicationSuppliedDelegateReplacesTheLibraryOne() {
        contextRunner
            .withUserConfiguration(CustomDelegate.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("delegateSecurityDataStorage", SecurityDataStorage.class).getProviderType())
                    .isEqualTo("application-delegate");
            });
    }

    @Test
    void jwtDelegateFollowsTheApplicationSuppliedDelegate() {
        contextRunner
            .withUserConfiguration(CustomDelegate.class)
            .withPropertyValues("orgsec.storage.features.jwt-enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("jwtDelegateStorage", SecurityDataStorage.class).getProviderType())
                    .isEqualTo("application-delegate");
            });
    }

    @Test
    void applicationSuppliedPrimaryReplacesTheLibraryOne() {
        contextRunner
            .withUserConfiguration(CustomPrimary.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("primaryInMemoryStorage", SecurityDataStorage.class).getProviderType())
                    .isEqualTo("application-primary");
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
            return new NamedStorage("application-delegate");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPrimary {

        @Bean("primaryInMemoryStorage")
        SecurityDataStorage primaryInMemoryStorage() {
            return new NamedStorage("application-primary");
        }
    }

    /**
     * Minimal storage that only has to be distinguishable by provider type.
     */
    static class NamedStorage implements SecurityDataStorage {

        private final String providerType;

        NamedStorage(String providerType) {
            this.providerType = providerType;
        }

        @Override
        public com.nomendi6.orgsec.model.PersonDef getPerson(Long personId) {
            return null;
        }

        @Override
        public com.nomendi6.orgsec.model.OrganizationDef getOrganization(Long orgId) {
            return null;
        }

        @Override
        public com.nomendi6.orgsec.model.RoleDef getPartyRole(Long roleId) {
            return null;
        }

        @Override
        public com.nomendi6.orgsec.model.RoleDef getPositionRole(Long roleId) {
            return null;
        }

        @Override
        public com.nomendi6.orgsec.model.PrivilegeDef getPrivilege(String privilegeIdentifier) {
            return null;
        }

        @Override
        public void initialize() {
            // nothing to load
        }

        @Override
        public void refresh() {
            // nothing to reload
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public String getProviderType() {
            return providerType;
        }
    }
}
