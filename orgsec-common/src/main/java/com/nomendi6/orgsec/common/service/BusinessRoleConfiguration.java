package com.nomendi6.orgsec.common.service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import com.nomendi6.orgsec.model.BusinessRoleDefinition;

/**
 * Configuration for business roles used in the security system.
 * Uses a provider-based approach to allow clean extension and override of business roles.
 */
@Configuration
@ConfigurationProperties(prefix = "orgsec")
public class BusinessRoleConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BusinessRoleConfiguration.class);
    private static final int MAX_RSQL_SELECTOR_LENGTH = 200;
    private static final String RSQL_SELECTOR_PATTERN = "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*";

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
        validateBusinessRoleDefinitions();

        log.info("Business roles initialization completed. Active roles: {}", businessRoleDefinitions.keySet());
    }

    private void applyYamlConfiguration() {
        if (businessRoles != null && !businessRoles.isEmpty()) {
            log.debug("Applying YAML configuration overrides for {} roles", businessRoles.size());
            for (Map.Entry<String, BusinessRoleConfig> entry : businessRoles.entrySet()) {
                String roleName = entry.getKey();
                BusinessRoleConfig config = entry.getValue();

                if (config != null && (config.getSupportedFields() != null || config.getRsqlFields() != null)) {
                    BusinessRoleDefinition existing = businessRoleDefinitions.get(roleName);
                    Set<SecurityFieldType> supportedFields = resolveSupportedFields(config, existing);
                    Map<SecurityFieldType, String> rsqlFields = resolveRsqlFields(roleName, config, existing);

                    businessRoleDefinitions.put(roleName, new BusinessRoleDefinition(roleName, supportedFields, rsqlFields));
                    log.debug("Applied YAML override for role: {}", roleName);
                }
            }
        }
    }

    private Set<SecurityFieldType> resolveSupportedFields(BusinessRoleConfig config, BusinessRoleDefinition existing) {
        if (config.getSupportedFields() != null) {
            return Set.copyOf(config.getSupportedFields());
        }
        if (existing != null) {
            return existing.getSupportedFields();
        }
        return Collections.emptySet();
    }

    private Map<SecurityFieldType, String> resolveRsqlFields(
        String roleName,
        BusinessRoleConfig config,
        BusinessRoleDefinition existing
    ) {
        Map<SecurityFieldType, String> rsqlFields = new EnumMap<>(SecurityFieldType.class);
        if (existing != null) {
            rsqlFields.putAll(existing.getRsqlFields());
        }
        if (config.getRsqlFields() != null) {
            for (Map.Entry<String, String> fieldEntry : config.getRsqlFields().entrySet()) {
                SecurityFieldType fieldType = parseSecurityFieldType(roleName, fieldEntry.getKey());
                rsqlFields.put(fieldType, fieldEntry.getValue());
            }
        }
        return rsqlFields;
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
     * Add or update a business role definition programmatically.
     * Spring Boot configuration binding goes through {@link BusinessRoleConfig} and {@link #applyYamlConfiguration()};
     * this entry point is for code that registers business roles outside the YAML / provider lifecycle.
     */
    public void addBusinessRole(String name, Set<SecurityFieldType> supportedFields) {
        addBusinessRole(name, supportedFields, Collections.emptyMap());
    }

    /**
     * Add or update a business role definition with custom RSQL selectors.
     * Selectors are validated immediately; invalid input throws {@link OrgsecConfigurationException}.
     */
    public void addBusinessRole(String name, Set<SecurityFieldType> supportedFields, Map<SecurityFieldType, String> rsqlFields) {
        BusinessRoleDefinition definition = new BusinessRoleDefinition(name, supportedFields, rsqlFields);
        validateRsqlFields(name, definition);
        businessRoleDefinitions.put(name, definition);
    }

    /**
     * Check if a business role supports a specific security field type.
     */
    public boolean roleSupportsField(String roleName, SecurityFieldType fieldType) {
        BusinessRoleDefinition definition = businessRoleDefinitions.get(roleName);
        return definition != null && definition.supportsField(fieldType);
    }

    /**
     * Resolve the RSQL selector for a business role and security field type.
     * Custom selectors are configured through orgsec.business-roles.<role>.rsql-fields.
     */
    public String getRsqlFieldSelector(String roleName, SecurityFieldType fieldType) {
        BusinessRoleDefinition definition = businessRoleDefinitions.get(roleName);
        if (definition != null) {
            Optional<String> customSelector = definition.getRsqlField(fieldType);
            if (customSelector.isPresent()) {
                return customSelector.get();
            }
        }
        return getDefaultRsqlFieldSelector(roleName, fieldType);
    }

    private String getDefaultRsqlFieldSelector(String roleName, SecurityFieldType fieldType) {
        String fieldName = fieldType.generateFieldName(roleName);
        switch (fieldType) {
            case COMPANY:
            case ORG:
            case PERSON:
                return fieldName + ".id";
            case COMPANY_PATH:
            case ORG_PATH:
                return fieldName;
            default:
                return fieldName;
        }
    }

    private void validateBusinessRoleDefinitions() {
        for (BusinessRoleDefinition definition : businessRoleDefinitions.values()) {
            validateRsqlFields(definition.getName(), definition);
        }
    }

    private void validateRsqlFields(String roleName, BusinessRoleDefinition definition) {
        for (Map.Entry<SecurityFieldType, String> entry : definition.getRsqlFields().entrySet()) {
            SecurityFieldType fieldType = entry.getKey();
            if (!definition.supportsField(fieldType)) {
                throw new OrgsecConfigurationException(
                    "Invalid rsql-fields configuration for business role '" + roleName + "': field " + fieldType +
                    " is not listed in supported-fields"
                );
            }
            validateRsqlSelector(roleName, fieldType, entry.getValue());
        }
    }

    private SecurityFieldType parseSecurityFieldType(String roleName, String rawKey) {
        SecurityFieldType fieldType = SecurityFieldType.fromCode(rawKey);
        if (fieldType == null) {
            throw new OrgsecConfigurationException(
                "Invalid rsql-fields configuration for business role '" + roleName + "': unknown security field '" + rawKey + "'"
            );
        }
        return fieldType;
    }

    private void validateRsqlSelector(String roleName, SecurityFieldType fieldType, String selector) {
        if (selector == null || selector.isBlank()) {
            throw new OrgsecConfigurationException(
                "Invalid rsql-fields configuration for business role '" + roleName + "', field " + fieldType +
                ": selector must not be blank"
            );
        }
        if (selector.length() > MAX_RSQL_SELECTOR_LENGTH) {
            throw new OrgsecConfigurationException(
                "Invalid rsql-fields configuration for business role '" + roleName + "', field " + fieldType +
                ": selector length must not exceed " + MAX_RSQL_SELECTOR_LENGTH + " characters"
            );
        }
        if (!selector.matches(RSQL_SELECTOR_PATTERN)) {
            throw new OrgsecConfigurationException(
                "Invalid rsql-fields configuration for business role '" + roleName + "', field " + fieldType +
                ": selector '" + selector + "' is not a valid RSQL field path"
            );
        }
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

        private Set<SecurityFieldType> supportedFields;
        private Map<String, String> rsqlFields;

        public Set<SecurityFieldType> getSupportedFields() {
            return supportedFields;
        }

        public void setSupportedFields(Set<SecurityFieldType> supportedFields) {
            this.supportedFields = supportedFields;
        }

        public Map<String, String> getRsqlFields() {
            return rsqlFields;
        }

        public void setRsqlFields(Map<String, String> rsqlFields) {
            this.rsqlFields = rsqlFields;
        }
    }
}
