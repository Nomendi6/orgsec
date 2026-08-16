package com.nomendi6.orgsec.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.inmemory.InMemorySecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.StorageConfiguration;
import com.nomendi6.orgsec.storage.inmemory.loader.OrganizationLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.RoleLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPrivilegesStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import com.nomendi6.orgsec.storage.jwt.config.JwtStorageAutoConfiguration;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * The bean graph across all three storage modules: which bean is {@code @Primary}, and which one the
 * JWT storage forwards to.
 *
 * <p>The names matter as much as the wiring - {@code JwtSecurityDataStorage} resolves its delegate by
 * qualifier, and applications substitute their own store by declaring a bean under one of these
 * names. Identity matters too: the primary bean in a Redis deployment is an <em>alias</em>, and it
 * must hand back the very same instance, or invalidation callbacks and the health indicator would
 * hold a different object than the one serving lookups.
 *
 * <p>JWT+Redis is deliberately absent: the environment post-processor rejects that hybrid before
 * this bean graph is created, and its stable diagnostic is covered separately.
 */
class StorageBeanGraphTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            StorageConfiguration.class,
            RedisStorageAutoConfiguration.class,
            JwtStorageAutoConfiguration.class
        ))
        .withUserConfiguration(StorageCollaborators.class);

    @Test
    void memoryOnly() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("primaryInMemoryStorage");
            assertThat(context).doesNotHaveBean("jwtDelegateStorage");
            assertThat(context).doesNotHaveBean("redisSecurityDataStorage");
            assertThat(context).doesNotHaveBean("orgsecPrimaryStorage");
        });
    }

    @Test
    void jwtWithMemoryDelegate() {
        contextRunner
            .withPropertyValues("orgsec.storage.features.jwt-enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("jwtSecurityDataStorage");
                assertThat(context).doesNotHaveBean("primaryInMemoryStorage");

                assertThat(context.getBean("jwtDelegateStorage"))
                    .isSameAs(context.getBean("delegateSecurityDataStorage"))
                    .isSameAs(context.getBean("inMemorySecurityDataStorage"));
            });
    }

    @Test
    void redisOnlyIsPrimaryThroughAnAliasThatKeepsIdentity() {
        contextRunner
            .withPropertyValues(
                "orgsec.storage.redis.enabled=true",
                "orgsec.storage.features.redis-enabled=true"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("orgsecPrimaryStorage");
                assertThat(context.getBean("orgsecPrimaryStorage"))
                    .as("an alias, never a decorator")
                    .isSameAs(context.getBean("redisSecurityDataStorage"));

                assertThat(context).doesNotHaveBean("primaryInMemoryStorage");
                assertThat(context).hasBean("delegateSecurityDataStorage");
            });
    }

    // --- fixture ------------------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class StorageCollaborators {

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

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            // The graph is what is under test; nothing here opens a connection.
            return mock(RedisConnectionFactory.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

}
