package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.api.controller.PersonApiController;
import com.nomendi6.orgsec.api.service.PersonApiService;
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
@ConditionalOnBean(PersonLoader.class)
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
    public PersonApiController personApiController(PersonApiService personApiService) {
        return new PersonApiController(personApiService);
    }

    /**
     * Registered when {@code spring-security-oauth2-jose} is on the classpath. Whether the
     * chain itself is created then depends on the application supplying a {@link org.springframework.security.oauth2.jwt.JwtDecoder}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
    static class ResourceServerPresentConfiguration {

        /**
         * Bearer-token chain for the Person API.
         *
         * <p>Deliberately reuses the application's own {@code JwtDecoder} bean rather than
         * building a {@code NimbusJwtDecoder} here: issuer and audience validation are the
         * application's contract (JHipster applications, for example, validate audience with
         * their own {@code AudienceValidator} rather than through
         * {@code spring.security.oauth2.resourceserver.jwt.audiences}). Building a second
         * decoder would silently accept tokens the application rejects.
         */
        @Bean(name = "orgsecApiSecurityFilterChain")
        @ConditionalOnClass(SecurityFilterChain.class)
        @ConditionalOnBean(type = "org.springframework.security.oauth2.jwt.JwtDecoder")
        @ConditionalOnMissingBean(name = "orgsecApiSecurityFilterChain")
        @Order(SecurityProperties.BASIC_AUTH_ORDER - 50)
        SecurityFilterChain orgsecApiSecurityFilterChain(HttpSecurity http, OrgsecProperties properties) throws Exception {
            String requiredRole = properties.getApi().getPerson().getRequiredRole();
            return http
                .securityMatcher("/api/orgsec/person/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(requiredRole))
                .oauth2ResourceServer(oauth2 ->
                    oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(KeycloakRealmRoleConverter.jwtAuthenticationConverter()))
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
        }

        /**
         * The Person API is enabled but the application never defined a {@code JwtDecoder}, so
         * the chain above cannot be built. Failing here beats starting up with an endpoint that
         * is either unreachable or - worse, if another chain matches it first - unauthenticated.
         */
        @Bean
        @ConditionalOnMissingBean(type = "org.springframework.security.oauth2.jwt.JwtDecoder")
        PersonApiConfigurationError orgsecPersonApiJwtDecoderRequiredFailFast() {
            throw new IllegalStateException(
                "orgsec.api.person.enabled=true, but no JwtDecoder bean is present. The OrgSec Person API " +
                "(/api/orgsec/person/**) is authenticated with a bearer token issued to the Keycloak mapper's " +
                "service account, and it reuses the application's own JwtDecoder so issuer/audience validation " +
                "stays in one place. Either configure a resource server (a JwtDecoder bean, or " +
                "spring.security.oauth2.resourceserver.jwt.issuer-uri), or set orgsec.api.person.enabled=false."
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
            throw new IllegalStateException(
                "orgsec.api.person.enabled=true, but spring-security-oauth2-jose is not on the classpath. The " +
                "OrgSec Person API (/api/orgsec/person/**) is authenticated with a bearer token. Add " +
                "org.springframework.boot:spring-boot-starter-oauth2-resource-server (the OrgSec starter declares " +
                "it as optional), or set orgsec.api.person.enabled=false."
            );
        }
    }

    /**
     * Marker type for the fail-fast beans above. They never return an instance - the bean method
     * throws - but a distinct type keeps them out of every other injection point.
     */
    static final class PersonApiConfigurationError {}
}
