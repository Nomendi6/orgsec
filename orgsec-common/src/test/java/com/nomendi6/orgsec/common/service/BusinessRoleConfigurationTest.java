package com.nomendi6.orgsec.common.service;

import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import com.nomendi6.orgsec.model.BusinessRoleDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void shouldResolveDefaultRsqlSelectors() {
        assertThat(configuration.getRsqlFieldSelector("owner", SecurityFieldType.COMPANY)).isEqualTo("ownerCompany.id");
        assertThat(configuration.getRsqlFieldSelector("owner", SecurityFieldType.COMPANY_PATH)).isEqualTo("ownerCompanyPath");
        assertThat(configuration.getRsqlFieldSelector("owner", SecurityFieldType.ORG)).isEqualTo("ownerOrg.id");
        assertThat(configuration.getRsqlFieldSelector("owner", SecurityFieldType.ORG_PATH)).isEqualTo("ownerOrgPath");
        assertThat(configuration.getRsqlFieldSelector("owner", SecurityFieldType.PERSON)).isEqualTo("ownerPerson.id");
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

    @Test
    void shouldAllowProviderToDefineRsqlFields() {
        BusinessRoleProvider provider = () -> Map.of(
            "custom",
            new BusinessRoleDefinition(
                "custom",
                Set.of(SecurityFieldType.COMPANY),
                Map.of(SecurityFieldType.COMPANY, "customCompanyId")
            )
        );

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(provider));
        config.initializeBusinessRoles();

        assertThat(config.getRsqlFieldSelector("custom", SecurityFieldType.COMPANY)).isEqualTo("customCompanyId");
    }

    @Test
    void shouldOverrideProviderRsqlFieldsFromHigherPriorityProvider() {
        BusinessRoleProvider lowPriorityProvider = new TestBusinessRoleProvider(
            1,
            Map.of(
                "owner",
                new BusinessRoleDefinition(
                    "owner",
                    Set.of(SecurityFieldType.COMPANY),
                    Map.of(SecurityFieldType.COMPANY, "lowCompanyId")
                )
            )
        );
        BusinessRoleProvider highPriorityProvider = new TestBusinessRoleProvider(
            100,
            Map.of(
                "owner",
                new BusinessRoleDefinition(
                    "owner",
                    Set.of(SecurityFieldType.COMPANY),
                    Map.of(SecurityFieldType.COMPANY, "highCompanyId")
                )
            )
        );

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(lowPriorityProvider, highPriorityProvider));
        config.initializeBusinessRoles();

        assertThat(config.getRsqlFieldSelector("owner", SecurityFieldType.COMPANY)).isEqualTo("highCompanyId");
    }

    @Test
    void shouldMergeYamlRsqlFieldsWithoutReplacingProviderSupportedFields() {
        BusinessRoleConfiguration.BusinessRoleConfig ownerConfig = new BusinessRoleConfiguration.BusinessRoleConfig();
        ownerConfig.setRsqlFields(Map.of("COMPANY", "ownerCompanyId"));

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        config.setBusinessRoles(Map.of("owner", ownerConfig));
        config.initializeBusinessRoles();

        BusinessRoleDefinition owner = config.getBusinessRoleDefinition("owner");
        assertThat(owner.supportsField(SecurityFieldType.ORG)).isTrue();
        assertThat(config.getRsqlFieldSelector("owner", SecurityFieldType.COMPANY)).isEqualTo("ownerCompanyId");
        assertThat(config.getRsqlFieldSelector("owner", SecurityFieldType.ORG)).isEqualTo("ownerOrg.id");
    }

    @Test
    void shouldMergeYamlSupportedFieldsWithoutReplacingProviderRsqlFields() {
        BusinessRoleProvider provider = () -> Map.of(
            "owner",
            new BusinessRoleDefinition(
                "owner",
                Set.of(SecurityFieldType.COMPANY, SecurityFieldType.ORG),
                Map.of(SecurityFieldType.COMPANY, "providerCompanyId")
            )
        );
        BusinessRoleConfiguration.BusinessRoleConfig ownerConfig = new BusinessRoleConfiguration.BusinessRoleConfig();
        ownerConfig.setSupportedFields(Set.of(SecurityFieldType.COMPANY));

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(provider));
        config.setBusinessRoles(Map.of("owner", ownerConfig));
        config.initializeBusinessRoles();

        BusinessRoleDefinition owner = config.getBusinessRoleDefinition("owner");
        assertThat(owner.supportsField(SecurityFieldType.COMPANY)).isTrue();
        assertThat(owner.supportsField(SecurityFieldType.ORG)).isFalse();
        assertThat(config.getRsqlFieldSelector("owner", SecurityFieldType.COMPANY)).isEqualTo("providerCompanyId");
    }

    @Test
    void shouldParseRsqlFieldKeysCaseInsensitively() {
        BusinessRoleConfiguration.BusinessRoleConfig ownerConfig = new BusinessRoleConfiguration.BusinessRoleConfig();
        ownerConfig.setRsqlFields(Map.of("Company", "ownerCompanyId", "org", "ownerOrgId"));

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        config.setBusinessRoles(Map.of("owner", ownerConfig));
        config.initializeBusinessRoles();

        assertThat(config.getRsqlFieldSelector("owner", SecurityFieldType.COMPANY)).isEqualTo("ownerCompanyId");
        assertThat(config.getRsqlFieldSelector("owner", SecurityFieldType.ORG)).isEqualTo("ownerOrgId");
    }

    @Test
    void shouldRejectUnknownRsqlFieldKey() {
        BusinessRoleConfiguration.BusinessRoleConfig ownerConfig = new BusinessRoleConfiguration.BusinessRoleConfig();
        ownerConfig.setRsqlFields(Map.of("TENANT", "tenantId"));

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        config.setBusinessRoles(Map.of("owner", ownerConfig));

        assertThatThrownBy(config::initializeBusinessRoles)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("owner")
            .hasMessageContaining("TENANT");
    }

    @Test
    void shouldRejectInvalidRsqlSelector() {
        BusinessRoleConfiguration.BusinessRoleConfig ownerConfig = new BusinessRoleConfiguration.BusinessRoleConfig();
        ownerConfig.setRsqlFields(Map.of("COMPANY", "ownerCompany.id==1"));

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        config.setBusinessRoles(Map.of("owner", ownerConfig));

        assertThatThrownBy(config::initializeBusinessRoles)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("owner")
            .hasMessageContaining("COMPANY");
    }

    @Test
    void shouldRejectBlankRsqlSelector() {
        BusinessRoleConfiguration.BusinessRoleConfig ownerConfig = new BusinessRoleConfiguration.BusinessRoleConfig();
        ownerConfig.setRsqlFields(Map.of("COMPANY", " "));

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        config.setBusinessRoles(Map.of("owner", ownerConfig));

        assertThatThrownBy(config::initializeBusinessRoles)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("selector must not be blank");
    }

    @Test
    void shouldRejectTooLongRsqlSelector() {
        BusinessRoleConfiguration.BusinessRoleConfig ownerConfig = new BusinessRoleConfiguration.BusinessRoleConfig();
        ownerConfig.setRsqlFields(Map.of("COMPANY", "a".repeat(201)));

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        config.setBusinessRoles(Map.of("owner", ownerConfig));

        assertThatThrownBy(config::initializeBusinessRoles)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("must not exceed 200");
    }

    @Test
    void shouldRejectRsqlFieldOutsideSupportedFields() {
        BusinessRoleConfiguration.BusinessRoleConfig ownerConfig = new BusinessRoleConfiguration.BusinessRoleConfig();
        ownerConfig.setSupportedFields(Set.of(SecurityFieldType.PERSON));
        ownerConfig.setRsqlFields(Map.of("COMPANY", "ownerCompanyId"));

        BusinessRoleConfiguration config = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        config.setBusinessRoles(Map.of("owner", ownerConfig));

        assertThatThrownBy(config::initializeBusinessRoles)
            .isInstanceOf(OrgsecConfigurationException.class)
            .hasMessageContaining("not listed in supported-fields");
    }

    private static final class TestBusinessRoleProvider implements BusinessRoleProvider {

        private final int priority;
        private final Map<String, BusinessRoleDefinition> definitions;

        private TestBusinessRoleProvider(int priority, Map<String, BusinessRoleDefinition> definitions) {
            this.priority = priority;
            this.definitions = definitions;
        }

        @Override
        public Map<String, BusinessRoleDefinition> getBusinessRoleDefinitions() {
            return definitions;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }
}
