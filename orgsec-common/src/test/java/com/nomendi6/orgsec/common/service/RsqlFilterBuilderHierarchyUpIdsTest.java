package com.nomendi6.orgsec.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.common.store.SecurityDataStore;
import com.nomendi6.orgsec.constants.HierarchyUpStrategy;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RsqlFilterBuilderHierarchyUpIdsTest {

    private static final String RESOURCE = "document";
    private static final PersonData CURRENT = new PersonData(1L, "Alice");

    private SecurityDataStorage storage;
    private RsqlFilterBuilder builder;

    @BeforeEach
    void setUp() {
        storage = mock(SecurityDataStorage.class);
        BusinessRoleConfiguration roles = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        roles.getHierarchyUp().setStrategy(HierarchyUpStrategy.IDS);
        roles.initializeBusinessRoles();
        builder = new RsqlFilterBuilder(new SecurityDataStore(storage), roles);
    }

    @Test
    void orgHierarchyUpEmitsOwnerOrgIdInList() {
        when(storage.getPerson(1L)).thenReturn(person(orgPrivilege(PrivilegeDirection.HIERARCHY_UP)));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT);

        assertThat(filter).isEqualTo("(ownerOrg.id=in=(1,10,15))");
        assertThat(filter).doesNotContain("ownerOrgPath");
    }

    @Test
    void companyHierarchyUpEmitsCompanySelectorNotTheOrgWalk() {
        when(storage.getPerson(1L)).thenReturn(person(companyPrivilege(PrivilegeDirection.HIERARCHY_UP)));

        String filter = builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT);

        assertThat(filter).isEqualTo("(ownerCompany.id=in=(1))");
        assertThat(filter).doesNotContain("10").doesNotContain("15");
    }

    @Test
    void missingLineageFailsClosed() {
        PersonDef person = person(orgPrivilege(PrivilegeDirection.HIERARCHY_UP));
        person.organizationsMap.get(15L).orgLineageIds = null;
        when(storage.getPerson(1L)).thenReturn(person);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> builder.buildRsqlFilterForReadPrivileges(RESOURCE, null, CURRENT)
        ).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private PersonDef person(PrivilegeDef privilege) {
        PersonDef person = new PersonDef(1L, "Alice");
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = 15L;
        organization.companyId = 1L;
        organization.parentPath = "|1|10|15|";
        organization.companyParentPath = "|1|";
        organization.orgLineageIds = List.of(1L, 10L, 15L);
        organization.companyLineageIds = List.of(1L);

        ResourceDef resource = new ResourceDef(RESOURCE);
        resource.getPrivilegesList().add(privilege);
        resource.setAggregatedReadPrivilege(privilege);
        BusinessRoleDef role = new BusinessRoleDef("owner");
        role.resourcesMap.put(RESOURCE, resource);
        organization.businessRolesMap.put("owner", role);
        person.organizationsMap.put(15L, organization);
        return person;
    }

    private static PrivilegeDef orgPrivilege(PrivilegeDirection org) {
        return new PrivilegeDef("document_orhu", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, org, false);
    }

    private static PrivilegeDef companyPrivilege(PrivilegeDirection company) {
        return new PrivilegeDef("document_comphu", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(company, PrivilegeDirection.NONE, false);
    }
}
