package com.nomendi6.orgsec.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nomendi6.orgsec.api.service.PersonApiService;
import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Activation contract of the Person API auto-configuration.
 *
 * <p>The HTTP behaviour of the chain itself lives in {@code PersonApiSecurityChainTest} - this
 * class only covers what happens before any request is served: whether the beans appear at all,
 * and whether a misconfigured application is stopped at startup rather than exposed.
 */
class PersonApiConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PersonApiServiceConfiguration.class))
        .withUserConfiguration(PersonApiPrerequisites.class);

    @Test
    void shouldStayOutOfTheWayWhenPersonApiIsDisabled() {
        // The default. An application without a resource server must keep booting.
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PersonApiService.class);
            assertThat(context).doesNotHaveBean("orgsecApiSecurityFilterChain");
        });
    }

    @Test
    void shouldStayOutOfTheWayWhenPersonApiIsExplicitlyDisabled() {
        contextRunner
            .withPropertyValues("orgsec.api.person.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(PersonApiService.class);
            });
    }

    @Test
    void shouldFailFastWhenPersonApiIsEnabledWithoutJwtDecoder() {
        // spring-security-oauth2-jose is on the test classpath, but the application never
        // declared a decoder. Starting anyway would leave the endpoint either unreachable or,
        // if some other chain matched it first, unauthenticated.
        contextRunner
            .withPropertyValues("orgsec.api.person.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(OrgsecConfigurationException.class)
                    .rootCause()
                    .hasMessageStartingWith("ORGSEC_PERSON_API_JWT_DECODER_REQUIRED:");
            });
    }

    @Test
    void shouldFailFastWithStableDiagnosticWhenPersonLoaderIsMissing() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PersonApiServiceConfiguration.class))
            .withUserConfiguration(PersonApiWithoutLoaderPrerequisites.class)
            .withPropertyValues("orgsec.api.person.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(OrgsecConfigurationException.class)
                    .rootCause()
                    .hasMessageStartingWith("ORGSEC_PERSON_API_PERSON_LOADER_REQUIRED:");
            });
    }

    /**
     * Everything {@code PersonApiServiceConfiguration} needs before its own conditions are even
     * evaluated: {@code PersonLoader} (its {@code @ConditionalOnBean} gate) plus the collaborators
     * of {@code PersonApiService}. A {@code @Primary} storage is present on purpose - the service
     * must not pick it up.
     */
    @Configuration(proxyBeanMethods = false)
    static class PersonApiPrerequisites {

        @Bean
        AllPersonsStore allPersonsStore() {
            return new AllPersonsStore();
        }

        @Bean
        AllRolesStore allRolesStore() {
            return new AllRolesStore();
        }

        @Bean
        PersonLoader personLoader(AllRolesStore rolesStore, AllPersonsStore personsStore) {
            return new PersonLoader(rolesStore, personsStore);
        }

        @Bean
        SecurityQueryProvider securityQueryProvider() {
            return mock(SecurityQueryProvider.class);
        }

        @Bean
        @Primary
        SecurityDataStorage jwtSecurityDataStorage() {
            return new StubSecurityDataStorage("jwt-primary");
        }

        @Bean("delegateSecurityDataStorage")
        SecurityDataStorage delegateSecurityDataStorage() {
            return new StubSecurityDataStorage("inmemory-delegate");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PersonApiWithoutLoaderPrerequisites {

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }

        @Bean("orgsecApiSecurityFilterChain")
        SecurityFilterChain existingPersonApiChain() {
            return mock(SecurityFilterChain.class);
        }
    }
}
