package com.nomendi6.orgsec.common.service;

import com.nomendi6.orgsec.common.store.SecurityDataStore;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.BusinessRoleDefinition;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RsqlFilterBuilderTest {

    private static final String RESOURCE = "document";
    private static final PersonData CURRENT_PERSON = new PersonData(1L, "Alice");

    private SecurityDataStorage storage;
    private RsqlFilterBuilder builder;

    @BeforeEach
    void setUp() {
        storage = mock(SecurityDataStorage.class);
        builder = builderWithDefaultRoles();
    }

    @Test
    void shouldBuildDefaultCompanyExactFilter() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(ownerCompany.id==100)");
    }

    @Test
    void shouldBuildDefaultCompanyHierarchyDownFilter() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.HIERARCHY_DOWN, PrivilegeDirection.NONE, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(ownerCompanyPath=*'|1|10|*')");
    }

    @Test
    void shouldBuildDefaultCompanyHierarchyUpFilter() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.HIERARCHY_UP, PrivilegeDirection.NONE, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(ownerCompanyPath=*'*|1|10|')");
    }

    @Test
    void shouldBuildDefaultOrgExactFilter() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.NONE, PrivilegeDirection.EXACT, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(ownerOrg.id==10)");
    }

    @Test
    void shouldBuildDefaultOrgHierarchyDownFilter() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(ownerOrgPath=*'|1|10|*')");
    }

    @Test
    void shouldBuildDefaultOrgHierarchyUpFilter() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_UP, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(ownerOrgPath=*'*|1|10|')");
    }

    @Test
    void shouldBuildDefaultPersonFilter() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.NONE, PrivilegeDirection.NONE, true),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(ownerPerson.id==1)");
    }

    @Test
    void shouldBuildCustomFlatCompanyFilter() {
        builder = builderWithRoleDefinition(new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.COMPANY),
            Map.of(SecurityFieldType.COMPANY, "ownerCompanyId")
        ));
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(ownerCompanyId==100)");
    }

    @Test
    void shouldBuildCustomDottedCompanyFilter() {
        builder = builderWithRoleDefinition(new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.COMPANY),
            Map.of(SecurityFieldType.COMPANY, "owner.company.id")
        ));
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEqualTo("(owner.company.id==100)");
    }

    @Test
    void shouldBuildCustomPathFilters() {
        builder = builderWithRoleDefinition(new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.COMPANY, SecurityFieldType.COMPANY_PATH, SecurityFieldType.ORG, SecurityFieldType.ORG_PATH),
            Map.of(
                SecurityFieldType.COMPANY_PATH, "ownerCompanyHierarchy",
                SecurityFieldType.ORG_PATH, "ownerOrganizationHierarchy"
            )
        ));
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.HIERARCHY_UP, PrivilegeDirection.NONE, false),
            "|1|10|"
        ));

        String companyFilter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false),
            "|1|10|"
        ));
        String orgFilter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(companyFilter).isEqualTo("(ownerCompanyHierarchy=*'*|1|10|')");
        assertThat(orgFilter).isEqualTo("(ownerOrganizationHierarchy=*'|1|10|*')");
    }

    @Test
    void shouldPrefixCustomSelectorWithParentField() {
        builder = builderWithRoleDefinition(new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.COMPANY),
            Map.of(SecurityFieldType.COMPANY, "ownerCompanyId")
        ));
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false),
            "|1|10|"
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, "document", CURRENT_PERSON);

        assertThat(filter).isEqualTo("(document.ownerCompanyId==100)");
    }

    @Test
    void shouldFailClosedWhenHierarchyPrivilegeHasNullParentPath() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false),
            null
        ));

        assertThatThrownBy(() -> builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldStillAllowExplicitAllPrivilege() {
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            new PrivilegeDef("document_ALL_R", RESOURCE)
                .allowOperation(PrivilegeOperation.READ)
                .allowAll(true),
            null
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEmpty();
    }

    @Test
    void shouldShortCircuitAllPrivilegeRegardlessOfCustomRsqlFields() {
        builder = builderWithRoleDefinition(new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.COMPANY),
            Map.of(SecurityFieldType.COMPANY, "ownerCompanyId")
        ));
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            new PrivilegeDef("document_ALL_R", RESOURCE)
                .allowOperation(PrivilegeOperation.READ)
                .allowAll(true),
            null
        ));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).isEmpty();
    }

    @Test
    void shouldCombineMultipleRoleFiltersWithOr() {
        builder = builderWithRoleDefinitions(
            new BusinessRoleDefinition(
                "owner",
                Set.of(SecurityFieldType.COMPANY),
                Map.of(SecurityFieldType.COMPANY, "ownerCompanyId")
            ),
            new BusinessRoleDefinition(
                "customer",
                Set.of(SecurityFieldType.COMPANY),
                Map.of()
            )
        );
        when(storage.getPerson(1L)).thenReturn(personWithPrivileges(Map.of(
            "owner", privilege(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false),
            "customer", privilege(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false)
        ), "|1|10|"));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON);

        assertThat(filter).contains("ownerCompanyId==100");
        assertThat(filter).contains("customerCompany.id==100");
        assertThat(filter).contains(",");
    }

    @Test
    void shouldFailClosedWhenPrivilegeFieldIsUnsupportedByRole() {
        builder = builderWithRoleDefinition(new BusinessRoleDefinition(
            "owner",
            Set.of(SecurityFieldType.PERSON)
        ));
        when(storage.getPerson(1L)).thenReturn(personWithPrivilege(
            "owner",
            privilege(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false),
            "|1|10|"
        ));

        assertThatThrownBy(() -> builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT_PERSON))
            .isInstanceOf(AccessDeniedException.class);
    }

    private RsqlFilterBuilder builderWithDefaultRoles() {
        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        roleConfiguration.initializeBusinessRoles();
        return new RsqlFilterBuilder(new SecurityDataStore(storage), roleConfiguration);
    }

    private RsqlFilterBuilder builderWithRoleDefinition(BusinessRoleDefinition definition) {
        return builderWithRoleDefinitions(definition);
    }

    private RsqlFilterBuilder builderWithRoleDefinitions(BusinessRoleDefinition... definitions) {
        BusinessRoleProvider provider = () -> {
            java.util.Map<String, BusinessRoleDefinition> definitionMap = new java.util.LinkedHashMap<>();
            for (BusinessRoleDefinition definition : definitions) {
                definitionMap.put(definition.getName(), definition);
            }
            return definitionMap;
        };
        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(provider));
        roleConfiguration.initializeBusinessRoles();
        return new RsqlFilterBuilder(new SecurityDataStore(storage), roleConfiguration);
    }

    private PrivilegeDef privilege(PrivilegeDirection company, PrivilegeDirection org, boolean person) {
        return new PrivilegeDef("document_privilege", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(company, org, person);
    }

    private PersonDef personWithPrivilege(String businessRole, PrivilegeDef privilege, String parentPath) {
        return personWithPrivileges(Map.of(businessRole, privilege), parentPath);
    }

    private PersonDef personWithPrivileges(Map<String, PrivilegeDef> privilegesByRole, String parentPath) {
        PersonDef person = new PersonDef(1L, "Alice");
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = 10L;
        organization.companyId = 100L;
        organization.parentPath = parentPath;
        organization.companyParentPath = parentPath;

        for (Map.Entry<String, PrivilegeDef> entry : privilegesByRole.entrySet()) {
            ResourceDef resource = new ResourceDef(RESOURCE);
            resource.setAggregatedReadPrivilege(entry.getValue());
            BusinessRoleDef role = new BusinessRoleDef(entry.getKey());
            role.resourcesMap.put(RESOURCE, resource);
            organization.businessRolesMap.put(entry.getKey(), role);
        }

        person.organizationsMap.put(10L, organization);
        return person;
    }
}
