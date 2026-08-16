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
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

class PersonApiConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PersonApiServiceConfiguration.class));

    @Test
    void staysInactiveByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PersonApiService.class);
            assertThat(context).doesNotHaveBean("orgsecApiSecurityFilterChain");
        });
    }

    @Test
    void bindsServiceToNamedDelegateRatherThanPrimaryStorage() {
        contextRunner
            .withPropertyValues("orgsec.api.person.enabled=true")
            .withUserConfiguration(CompletePrerequisites.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                PersonApiService service = context.getBean(PersonApiService.class);
                assertThat(ReflectionTestUtils.getField(service, "securityDataStorage"))
                    .isSameAs(context.getBean("delegateSecurityDataStorage"));
            });
    }

    @Test
    void failsWithStableDiagnosticWhenPersonLoaderIsMissing() {
        contextRunner
            .withPropertyValues("orgsec.api.person.enabled=true")
            .withUserConfiguration(PrerequisitesWithoutPersonLoader.class)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(OrgsecConfigurationException.class)
                    .rootCause()
                    .hasMessageStartingWith("ORGSEC_PERSON_API_PERSON_LOADER_REQUIRED:");
            });
    }

    @Test
    void failsWithStableDiagnosticWhenJwtDecoderIsMissing() {
        contextRunner
            .withPropertyValues("orgsec.api.person.enabled=true")
            .withUserConfiguration(PrerequisitesWithoutJwtDecoder.class)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(OrgsecConfigurationException.class)
                    .rootCause()
                    .hasMessageStartingWith("ORGSEC_PERSON_API_JWT_DECODER_REQUIRED:");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class BasePrerequisites {

        @Bean
        SecurityQueryProvider securityQueryProvider() {
            return mock(SecurityQueryProvider.class);
        }

        @Bean("jwtPrimaryStorage")
        SecurityDataStorage jwtPrimaryStorage() {
            return new StubSecurityDataStorage("jwt-primary");
        }

        @Bean("delegateSecurityDataStorage")
        SecurityDataStorage delegateSecurityDataStorage() {
            return new StubSecurityDataStorage("inmemory-delegate");
        }

        @Bean("orgsecApiSecurityFilterChain")
        SecurityFilterChain existingTestChain() {
            return mock(SecurityFilterChain.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PersonLoaderPrerequisites {

        @Bean
        AllPersonsStore allPersonsStore() {
            return new AllPersonsStore();
        }

        @Bean
        AllRolesStore allRolesStore() {
            return new AllRolesStore();
        }

        @Bean
        PersonLoader personLoader(AllRolesStore roles, AllPersonsStore persons) {
            return new PersonLoader(roles, persons);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({BasePrerequisites.class, PersonLoaderPrerequisites.class})
    static class CompletePrerequisites {

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BasePrerequisites.class)
    static class PrerequisitesWithoutPersonLoader {

        @Bean
        JwtDecoder jwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({BasePrerequisites.class, PersonLoaderPrerequisites.class})
    static class PrerequisitesWithoutJwtDecoder {
    }
}
