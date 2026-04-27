package com.nomendi6.orgsec.model;

import com.nomendi6.orgsec.constants.SecurityFieldType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessRoleDefinitionTest {

    @Test
    void shouldKeepRsqlFieldsEmptyForLegacyConstructor() {
        BusinessRoleDefinition definition = new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.COMPANY)
        );

        assertThat(definition.getRsqlFields()).isEmpty();
        assertThat(definition.getRsqlField(SecurityFieldType.COMPANY)).isEmpty();
    }

    @Test
    void shouldExposeConfiguredRsqlFieldsAsImmutableMap() {
        BusinessRoleDefinition definition = new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.COMPANY),
            Map.of(SecurityFieldType.COMPANY, "ownerCompanyId")
        );

        assertThat(definition.getRsqlField(SecurityFieldType.COMPANY)).contains("ownerCompanyId");
        assertThatThrownBy(() -> definition.getRsqlFields().put(SecurityFieldType.ORG, "ownerOrgId"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldDefensivelyCopyRsqlFields() {
        Map<SecurityFieldType, String> rsqlFields = new java.util.EnumMap<>(SecurityFieldType.class);
        rsqlFields.put(SecurityFieldType.COMPANY, "ownerCompanyId");

        BusinessRoleDefinition definition = new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.COMPANY),
            rsqlFields
        );
        rsqlFields.put(SecurityFieldType.COMPANY, "changed");

        assertThat(definition.getRsqlField(SecurityFieldType.COMPANY)).contains("ownerCompanyId");
    }
}
