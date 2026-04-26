package com.nomendi6.orgsec.storage.jwt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.jwt.JwtClaimsParser;
import com.nomendi6.orgsec.storage.jwt.JwtSecurityDataStorage;
import com.nomendi6.orgsec.storage.jwt.JwtTokenContextHolder;
import com.nomendi6.orgsec.storage.jwt.JwtTokenFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;

/**
 * Auto-configuration for JWT storage.
 *
 * This configuration creates:
 * - JwtTokenContextHolder - ThreadLocal holder for JWT token
 * - JwtClaimsParser - Parser for OrgSec claims from JWT
 * - JwtSecurityDataStorage - Primary SecurityDataStorage implementation
 * - JwtTokenFilter - Filter to extract and set JWT token in context
 *
 * The JWT storage acts as a hybrid storage that:
 * - Gets Person data from JWT token
 * - Delegates Organization/Role/Privilege queries to delegate storage (InMemory/Redis)
 *
 * IMPORTANT: When this configuration is active, JwtSecurityDataStorage becomes @Primary
 * and InMemorySecurityDataStorage serves as delegate (not primary).
 */
@AutoConfiguration
@EnableConfigurationProperties(JwtStorageProperties.class)
@ConditionalOnProperty(name = "orgsec.storage.features.jwt-enabled", havingValue = "true")
public class JwtStorageAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtStorageAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenContextHolder jwtTokenContextHolder() {
        log.debug("Creating JwtTokenContextHolder bean");
        return new JwtTokenContextHolder();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtClaimsParser jwtClaimsParser(ObjectMapper objectMapper, JwtStorageProperties properties) {
        log.debug("Creating JwtClaimsParser bean with claim name: {}", properties.getClaimName());
        return new JwtClaimsParser(objectMapper, properties.getClaimName());
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "jwtSecurityDataStorage")
    public SecurityDataStorage jwtSecurityDataStorage(
            JwtClaimsParser claimsParser,
            JwtTokenContextHolder tokenContextHolder,
            @Qualifier("delegateSecurityDataStorage") SecurityDataStorage delegateStorage) {
        log.info("Creating JwtSecurityDataStorage as primary SecurityDataStorage with delegate: {}",
                delegateStorage.getProviderType());
        return new JwtSecurityDataStorage(claimsParser, tokenContextHolder, delegateStorage);
    }

    @Bean
    public FilterRegistrationBean<JwtTokenFilter> jwtTokenFilterRegistration(
            JwtTokenContextHolder tokenContextHolder,
            JwtStorageProperties properties) {
        log.debug("Registering JwtTokenFilter");

        JwtTokenFilter filter = new JwtTokenFilter(
                tokenContextHolder,
                properties.getTokenHeader(),
                properties.getTokenPrefix());

        FilterRegistrationBean<JwtTokenFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        registration.setName("jwtTokenFilter");
        // Must run AFTER Spring Security filter to access SecurityContextHolder
        // Spring Security filter chain is typically at -100
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);

        return registration;
    }
}
