package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.api.controller.PersonApiController;
import com.nomendi6.orgsec.api.service.PersonApiService;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
 */
@Configuration
@EnableConfigurationProperties(OrgsecProperties.class)
@ConditionalOnBean(PersonLoader.class)
@ConditionalOnProperty(prefix = "orgsec.api.person", name = "enabled", havingValue = "true")
public class PersonApiServiceConfiguration {

    @Bean
    public PersonApiService personApiService(SecurityDataStorage securityDataStorage,
                                             SecurityQueryProvider queryProvider,
                                             PersonLoader personLoader,
                                             AllPersonsStore personsStore) {
        return new PersonApiService(securityDataStorage, queryProvider, personLoader, personsStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonApiController personApiController(PersonApiService personApiService) {
        return new PersonApiController(personApiService);
    }

    @Bean(name = "orgsecApiSecurityFilterChain")
    @ConditionalOnClass(SecurityFilterChain.class)
    @ConditionalOnMissingBean(name = "orgsecApiSecurityFilterChain")
    @Order(SecurityProperties.BASIC_AUTH_ORDER - 50)
    public SecurityFilterChain orgsecApiSecurityFilterChain(HttpSecurity http, OrgsecProperties properties) throws Exception {
        String requiredRole = properties.getApi().getPerson().getRequiredRole();
        return http
            .securityMatcher("/api/orgsec/person/**")
            .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole(requiredRole))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .build();
    }
}
