package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.api.PersonApiErrorWriter;
import com.nomendi6.orgsec.api.controller.PersonApiController;
import com.nomendi6.orgsec.api.dto.PersonApiErrorCodes;
import com.nomendi6.orgsec.api.service.PersonApiService;
import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Conditional configuration for PersonApiService.
 * Only creates the bean when PersonLoader is available (inmemory storage module is active).
 *
 * <p>The Person API is the endpoint the Keycloak protocol mapper calls to build the
 * {@code orgsec} claim. It is off by default; when it is switched on, this library owns the
 * security chain for {@code /api/orgsec/person/**} so applications do not have to hand-roll
 * one (and cannot accidentally leave it anonymous).
 */
@Configuration
@EnableConfigurationProperties(OrgsecProperties.class)
@ConditionalOnProperty(prefix = "orgsec.api.person", name = "enabled", havingValue = "true")
@AutoConfigureAfter(name = "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration")
public class PersonApiServiceConfiguration {

    /**
     * The Person API answers "who is this Keycloak user, organizationally" out of the
     * authoritative store. In JWT mode the {@code @Primary} {@code SecurityDataStorage} is
     * {@code JwtSecurityDataStorage}, which resolves the person from the token of the request
     * currently in flight - i.e. the mapper's own service-account token. Bind to the delegate
     * explicitly so the answer always comes from the database, never from the caller's token.
     */
    @Bean
    @ConditionalOnBean(PersonLoader.class)
    public PersonApiService personApiService(
        @Qualifier("delegateSecurityDataStorage") SecurityDataStorage securityDataStorage,
        SecurityQueryProvider queryProvider,
        PersonLoader personLoader,
        AllPersonsStore personsStore
    ) {
        return new PersonApiService(securityDataStorage, queryProvider, personLoader, personsStore);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PersonApiService.class)
    public PersonApiController personApiController(PersonApiService personApiService) {
        return new PersonApiController(personApiService);
    }

    /**
     * Enabling the endpoint without its authoritative loader used to omit the complete API bean
     * graph silently. Fail before startup instead, with a stable diagnostic.
     */
    @Bean
    @ConditionalOnMissingBean(PersonLoader.class)
    PersonApiConfigurationError orgsecPersonApiPersonLoaderRequiredFailFast() {
        throw new OrgsecConfigurationException(
            "ORGSEC_PERSON_API_PERSON_LOADER_REQUIRED: orgsec.api.person.enabled=true requires " +
                "a PersonLoader bean backed by the authoritative delegate storage."
        );
    }

    /**
     * Bearer-token chain for the Person API.
     *
     * <p>This public factory method is part of the published 1.0.4 Boot configuration API.
     * Keep it on the outer configuration class while the nested configurations below isolate
     * the fail-fast checks for optional resource-server classes.
     */
    @Bean(name = "orgsecApiSecurityFilterChain")
    @ConditionalOnClass(SecurityFilterChain.class)
    @ConditionalOnBean(type = "org.springframework.security.oauth2.jwt.JwtDecoder")
    @ConditionalOnMissingBean(name = "orgsecApiSecurityFilterChain")
    @Order(SecurityProperties.BASIC_AUTH_ORDER - 50)
    public SecurityFilterChain orgsecApiSecurityFilterChain(HttpSecurity http, OrgsecProperties properties) throws Exception {
        String requiredRole = properties.getApi().getPerson().getRequiredRole();
        return http
            .securityMatcher("/api/orgsec/person/**")
            .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(requiredRole))
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(KeycloakRealmRoleConverter.jwtAuthenticationConverter()))
                    .authenticationEntryPoint((request, response, exception) ->
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

    /**
     * Registered when {@code spring-security-oauth2-jose} is on the classpath. Whether the
     * chain itself is created then depends on the application supplying a {@link org.springframework.security.oauth2.jwt.JwtDecoder}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
    static class ResourceServerPresentConfiguration {
        /**
         * The Person API is enabled but the application never defined a {@code JwtDecoder}, so
         * the chain above cannot be built. Failing here beats starting up with an endpoint that
         * is either unreachable or - worse, if another chain matches it first - unauthenticated.
         */
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

    /**
     * Registered when the resource-server support is not on the classpath at all. The starter
     * declares {@code spring-boot-starter-oauth2-resource-server} as optional, so this is the
     * state of any application that enabled the Person API without adding that dependency.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.security.oauth2.jwt.JwtDecoder")
    static class ResourceServerMissingConfiguration {

        @Bean
        PersonApiConfigurationError orgsecPersonApiResourceServerRequiredFailFast() {
            throw new OrgsecConfigurationException(
                "ORGSEC_PERSON_API_RESOURCE_SERVER_REQUIRED: orgsec.api.person.enabled=true requires " +
                    "org.springframework.boot:spring-boot-starter-oauth2-resource-server."
            );
        }
    }

    /**
     * Marker type for the fail-fast beans above. They never return an instance - the bean method
     * throws - but a distinct type keeps them out of every other injection point.
     */
    static final class PersonApiConfigurationError {}
}
