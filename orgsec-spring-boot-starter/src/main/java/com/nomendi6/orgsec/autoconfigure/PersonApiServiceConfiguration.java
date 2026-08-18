package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.api.PersonApiErrorWriter;
import com.nomendi6.orgsec.api.controller.PersonApiController;
import com.nomendi6.orgsec.api.dto.PersonApiErrorCodes;
import com.nomendi6.orgsec.api.service.PersonApiService;
import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Library-owned Person API used by the Keycloak mapper.
 *
 * <p>When enabled, OrgSec owns both the controller and its Bearer-token security chain. Missing
 * prerequisites fail startup with stable OrgSec diagnostics instead of silently omitting the
 * endpoint or letting another application chain decide its security.
 */
@AutoConfiguration(afterName =
    "org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration")
@EnableConfigurationProperties(OrgsecProperties.class)
@ConditionalOnProperty(prefix = "orgsec.api.person", name = "enabled", havingValue = "true")
public class PersonApiServiceConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PersonApiService personApiService(
        @Qualifier("delegateSecurityDataStorage") SecurityDataStorage delegateStorage,
        SecurityQueryProvider queryProvider,
        ObjectProvider<PersonLoader> personLoaderProvider
    ) {
        PersonLoader personLoader = personLoaderProvider.getIfAvailable();
        if (personLoader == null) {
            throw new OrgsecConfigurationException(
                "ORGSEC_PERSON_API_PERSON_LOADER_REQUIRED: orgsec.api.person.enabled=true " +
                    "requires a PersonLoader bean backed by the authoritative delegate storage."
            );
        }
        return new PersonApiService(delegateStorage, queryProvider, personLoader);
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonApiController personApiController(PersonApiService personApiService) {
        return new PersonApiController(personApiService);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
    static class ResourceServerPresentConfiguration {

        @Bean(name = "orgsecApiSecurityFilterChain")
        @ConditionalOnBean(type = "org.springframework.security.oauth2.jwt.JwtDecoder")
        @ConditionalOnMissingBean(name = "orgsecApiSecurityFilterChain")
        @Order(SecurityFilterProperties.BASIC_AUTH_ORDER - 50)
        SecurityFilterChain orgsecApiSecurityFilterChain(
            HttpSecurity http,
            OrgsecProperties properties
        ) throws Exception {
            String requiredRole = properties.getApi().getPerson().getRequiredRole();
            return http
                .securityMatcher("/api/orgsec/person/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(requiredRole))
                .oauth2ResourceServer(oauth2 ->
                    oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        KeycloakRealmRoleConverter.jwtAuthenticationConverter()
                    )).authenticationEntryPoint((request, response, exception) ->
                        PersonApiErrorWriter.write(
                            response,
                            401,
                            PersonApiErrorCodes.CALLBACK_UNAUTHENTICATED
                        )
                    )
                )
                .exceptionHandling(exceptions ->
                    exceptions.accessDeniedHandler((request, response, exception) ->
                        PersonApiErrorWriter.write(
                            response,
                            403,
                            PersonApiErrorCodes.CALLBACK_FORBIDDEN
                        )
                    )
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
        }

        @Bean
        @ConditionalOnMissingBean(type = "org.springframework.security.oauth2.jwt.JwtDecoder")
        PersonApiConfigurationError orgsecPersonApiJwtDecoderRequiredFailFast() {
            throw new OrgsecConfigurationException(
                "ORGSEC_PERSON_API_JWT_DECODER_REQUIRED: orgsec.api.person.enabled=true requires " +
                    "the application's JwtDecoder so the Person API validates Bearer-token signature, " +
                    "issuer, audience and expiry."
            );
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.security.oauth2.jwt.JwtDecoder")
    static class ResourceServerMissingConfiguration {

        @Bean
        PersonApiConfigurationError orgsecPersonApiResourceServerRequiredFailFast() {
            throw new OrgsecConfigurationException(
                "ORGSEC_PERSON_API_RESOURCE_SERVER_REQUIRED: orgsec.api.person.enabled=true requires " +
                    "spring-boot-starter-oauth2-resource-server."
            );
        }
    }

    static final class PersonApiConfigurationError {
    }
}
