package com.nomendi6.orgsec.common.service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.model.BusinessRoleDefinition;

/**
 * Configuration for business roles used in the security system.
 * Uses a provider-based approach to allow clean extension and override of business roles.
 */
@Configuration
@ConfigurationProperties(prefix = "orgsec")
public class BusinessRoleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BusinessRoleConfiguration.class);

    private Map<String, BusinessRoleConfig> businessRoles = new LinkedHashMap<>();

    private final Map<String, BusinessRoleDefinition> businessRoleDefinitions = new LinkedHashMap<>();
    private final List<BusinessRoleProvider> providers;

    @Autowired
    public BusinessRoleConfiguration(List<BusinessRoleProvider> providers) {
        this.providers = providers != null ? providers : Collections.emptyList();
    }

    @PostConstruct
    public void initializeBusinessRoles() {
        log.info("Initializing business roles from {} providers", providers.size());

        // Clear any existing definitions
        businessRoleDefinitions.clear();

        // Process providers in priority order (higher priority wins)
        providers
            .stream()
            .sorted(Comparator.comparingInt(BusinessRoleProvider::getPriority))
            .forEach(provider -> {
                log.debug("Processing provider: {} (priority: {})", provider.getProviderName(), provider.getPriority());
                Map<String, BusinessRoleDefinition> defs = provider.getBusinessRoleDefinitions();
                if (defs != null) {
                    businessRoleDefinitions.putAll(defs);
                    log.debug("Added {} roles from provider {}", defs.size(), provider.getProviderName());
                }
            });

        // Apply YAML configuration overrides if present
        applyYamlConfiguration();

        log.info("Business roles initialization completed. Active roles: {}", businessRoleDefinitions.keySet());
    }

    private void applyYamlConfiguration() {
        if (businessRoles != null && !businessRoles.isEmpty()) {
            log.debug("Applying YAML configuration overrides for {} roles", businessRoles.size());
            for (Map.Entry<String, BusinessRoleConfig> entry : businessRoles.entrySet()) {
                String roleName = entry.getKey();
                BusinessRoleConfig config = entry.getValue();

                if (config != null && config.getSupportedFields() != null) {
                    businessRoleDefinitions.put(roleName, new BusinessRoleDefinition(roleName, Set.copyOf(config.getSupportedFields())));
                    log.debug("Applied YAML override for role: {}", roleName);
                }
            }
        }
    }

    /**
     * Get all defined business roles.
     */
    public Set<String> getAllBusinessRoleNames() {
        return Collections.unmodifiableSet(businessRoleDefinitions.keySet());
    }

    /**
     * Get business role definition by name.
     */
    public BusinessRoleDefinition getBusinessRoleDefinition(String roleName) {
        return businessRoleDefinitions.get(roleName);
    }

    /**
     * Check if a business role is defined.
     */
    public boolean isValidBusinessRole(String roleName) {
        return businessRoleDefinitions.containsKey(roleName);
    }

    /**
     * Get all business role definitions.
     */
    public Collection<BusinessRoleDefinition> getAllBusinessRoleDefinitions() {
        return Collections.unmodifiableCollection(businessRoleDefinitions.values());
    }

    /**
     * Add or update a business role definition.
     * This method is primarily used by Spring Boot configuration binding.
     */
    public void addBusinessRole(String name, Set<SecurityFieldType> supportedFields) {
        businessRoleDefinitions.put(name, new BusinessRoleDefinition(name, supportedFields));
    }

    /**
     * Check if a business role supports a specific security field type.
     */
    public boolean roleSupportsField(String roleName, SecurityFieldType fieldType) {
        BusinessRoleDefinition definition = businessRoleDefinitions.get(roleName);
        return definition != null && definition.supportsField(fieldType);
    }

    // Setters for Spring Boot configuration binding
    public void setBusinessRoles(Map<String, BusinessRoleConfig> businessRoles) {
        this.businessRoles = businessRoles;
    }

    public Map<String, BusinessRoleConfig> getBusinessRoles() {
        return businessRoles;
    }

    /**
     * Configuration class for individual business role settings.
     */
    public static class BusinessRoleConfig {

        private Set<SecurityFieldType> supportedFields = new HashSet<>();

        public Set<SecurityFieldType> getSupportedFields() {
            return supportedFields;
        }

        public void setSupportedFields(Set<SecurityFieldType> supportedFields) {
            this.supportedFields = supportedFields;
        }
    }
}
