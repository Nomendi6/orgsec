package com.nomendi6.orgsec.common.service;

import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.model.BusinessRoleDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessRoleConfigurationTest {

    private BusinessRoleConfiguration configuration;

    @BeforeEach
    void setUp() {
        // Create with default provider
        DefaultBusinessRoleProvider defaultProvider = new DefaultBusinessRoleProvider();
        configuration = new BusinessRoleConfiguration(List.of(defaultProvider));
        // Manually call @PostConstruct since we're not using Spring
        configuration.initializeBusinessRoles();
    }

    @Test
    void shouldHaveDefaultBusinessRoles() {
        Set<String> roles = configuration.getAllBusinessRoleNames();

        assertThat(roles).containsExactlyInAnyOrder("owner", "customer", "contractor");
    }

    @Test
    void shouldValidateKnownBusinessRole() {
        assertThat(configuration.isValidBusinessRole("owner")).isTrue();
        assertThat(configuration.isValidBusinessRole("customer")).isTrue();
        assertThat(configuration.isValidBusinessRole("contractor")).isTrue();
    }

    @Test
    void shouldRejectUnknownBusinessRole() {
        assertThat(configuration.isValidBusinessRole("unknown")).isFalse();
        assertThat(configuration.isValidBusinessRole("")).isFalse();
        assertThat(configuration.isValidBusinessRole(null)).isFalse();
    }

    @Test
    void shouldGetBusinessRoleDefinition() {
        BusinessRoleDefinition ownerDef = configuration.getBusinessRoleDefinition("owner");

        assertThat(ownerDef).isNotNull();
        assertThat(ownerDef.getName()).isEqualTo("owner");
    }

    @Test
    void shouldReturnNullForUnknownRoleDefinition() {
        BusinessRoleDefinition unknown = configuration.getBusinessRoleDefinition("unknown");
        assertThat(unknown).isNull();
    }

    @Test
    void shouldOwnerSupportAllFields() {
        BusinessRoleDefinition ownerDef = configuration.getBusinessRoleDefinition("owner");

        assertThat(ownerDef.supportsField(SecurityFieldType.COMPANY)).isTrue();
        assertThat(ownerDef.supportsField(SecurityFieldType.ORG)).isTrue();
        assertThat(ownerDef.supportsField(SecurityFieldType.PERSON)).isTrue();
    }

    @Test
    void shouldHandleEmptyProviderList() {
        BusinessRoleConfiguration emptyConfig = new BusinessRoleConfiguration(Collections.emptyList());
        emptyConfig.initializeBusinessRoles();

        assertThat(emptyConfig.getAllBusinessRoleNames()).isEmpty();
        assertThat(emptyConfig.isValidBusinessRole("owner")).isFalse();
    }

    @Test
    void shouldMergeMultipleProviders() {
        // Create custom provider
        BusinessRoleProvider customProvider = () -> {
            BusinessRoleDefinition customRole = new BusinessRoleDefinition(
                    "custom",
                    Set.of(SecurityFieldType.COMPANY)
            );
            return Map.of("custom", customRole);
        };

        // Create configuration with both providers
        DefaultBusinessRoleProvider defaultProvider = new DefaultBusinessRoleProvider();
        BusinessRoleConfiguration mergedConfig = new BusinessRoleConfiguration(
                List.of(defaultProvider, customProvider)
        );
        mergedConfig.initializeBusinessRoles();

        Set<String> roles = mergedConfig.getAllBusinessRoleNames();

        assertThat(roles).contains("owner", "customer", "contractor", "custom");
    }

    @Test
    void shouldOverrideRolesFromLaterProviders() {
        // Later provider with same role name should override
        BusinessRoleProvider overrideProvider = () -> {
            BusinessRoleDefinition overriddenOwner = new BusinessRoleDefinition(
                    "owner",
                    Set.of(SecurityFieldType.PERSON) // Only person field
            );
            return Map.of("owner", overriddenOwner);
        };

        DefaultBusinessRoleProvider defaultProvider = new DefaultBusinessRoleProvider();
        BusinessRoleConfiguration config = new BusinessRoleConfiguration(
                List.of(defaultProvider, overrideProvider)
        );
        config.initializeBusinessRoles();

        BusinessRoleDefinition ownerDef = config.getBusinessRoleDefinition("owner");

        // Should have the overridden definition
        assertThat(ownerDef.supportsField(SecurityFieldType.PERSON)).isTrue();
        assertThat(ownerDef.supportsField(SecurityFieldType.COMPANY)).isFalse();
        assertThat(ownerDef.supportsField(SecurityFieldType.ORG)).isFalse();
    }
}
