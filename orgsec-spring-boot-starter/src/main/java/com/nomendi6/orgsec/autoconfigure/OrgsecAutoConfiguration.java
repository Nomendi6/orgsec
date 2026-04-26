package com.nomendi6.orgsec.autoconfigure;

import com.nomendi6.orgsec.provider.SecurityContextProvider;
import com.nomendi6.orgsec.storage.inmemory.PrivilegeSecurityService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;

import org.springframework.context.annotation.ComponentScan;

import java.util.Optional;

@AutoConfiguration
@ConditionalOnClass(PrivilegeSecurityService.class)
@EnableConfigurationProperties(OrgsecProperties.class)
@AutoConfigureBefore(SecurityAutoConfiguration.class)
@AutoConfigureAfter(JpaRepositoriesAutoConfiguration.class)
@ComponentScan(
    basePackages = {"com.nomendi6.orgsec.api"},
    excludeFilters = {
        @ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = "com\\.nomendi6\\.orgsec\\.api\\.controller\\..*"
        ),
        @ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.REGEX,
            pattern = "com\\.nomendi6\\.orgsec\\.api\\.service\\..*"
        )
    }
)
public class OrgsecAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    public SecurityContextProvider springSecurityContextProvider() {
        return new SpringSecurityContextProvider();
    }

    /**
     * Default implementation of SecurityContextProvider using Spring Security
     */
    public static class SpringSecurityContextProvider implements SecurityContextProvider {

        @Override
        public Optional<String> getCurrentUserLogin() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated() &&
                !"anonymousUser".equals(authentication.getPrincipal())) {
                return Optional.ofNullable(authentication.getName());
            }
            return Optional.empty();
        }

        @Override
        public boolean isAuthenticated() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication != null && authentication.isAuthenticated() &&
                   !"anonymousUser".equals(authentication.getPrincipal());
        }

        @Override
        public boolean hasRole(String role) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                return authentication.getAuthorities().stream()
                    .anyMatch(authority -> authority.getAuthority().equals(role) ||
                                         authority.getAuthority().equals("ROLE_" + role));
            }
            return false;
        }

        @Override
        public Optional<Object> getPrincipal() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                return Optional.ofNullable(authentication.getPrincipal());
            }
            return Optional.empty();
        }
    }
}