package com.nomendi6.orgsec.common.service;

import com.nomendi6.orgsec.common.store.SecurityDataStore;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RsqlFilterBuilderTest {

    private SecurityDataStorage storage;
    private RsqlFilterBuilder builder;

    @BeforeEach
    void setUp() {
        storage = mock(SecurityDataStorage.class);
        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        roleConfiguration.initializeBusinessRoles();
        builder = new RsqlFilterBuilder(new SecurityDataStore(storage), roleConfiguration);
    }

    @Test
    void shouldFailClosedWhenHierarchyPrivilegeHasNullParentPath() {
        PersonData currentPerson = new PersonData(1L, "Alice");
        PersonDef personDef = personWithOwnerPrivilege(
            new PrivilegeDef("document_ORGHD_R", "document")
                .allowOperation(PrivilegeOperation.READ)
                .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false),
            null
        );
        when(storage.getPerson(1L)).thenReturn(personDef);

        assertThatThrownBy(() -> builder.buildRsqlFilterForReadPrivileges("document", null, currentPerson))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldStillAllowExplicitAllPrivilege() {
        PersonData currentPerson = new PersonData(1L, "Alice");
        PersonDef personDef = personWithOwnerPrivilege(
            new PrivilegeDef("document_ALL_R", "document")
                .allowOperation(PrivilegeOperation.READ)
                .allowAll(true),
            null
        );
        when(storage.getPerson(1L)).thenReturn(personDef);

        String filter = builder.buildRsqlFilterForReadPrivileges("document", null, currentPerson);

        assertThat(filter).isEmpty();
    }

    private PersonDef personWithOwnerPrivilege(PrivilegeDef privilege, String parentPath) {
        PersonDef person = new PersonDef(1L, "Alice");
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = 10L;
        organization.parentPath = parentPath;
        organization.companyParentPath = parentPath;

        ResourceDef resource = new ResourceDef("document");
        resource.setAggregatedReadPrivilege(privilege);
        BusinessRoleDef owner = new BusinessRoleDef("owner");
        owner.resourcesMap.put("document", resource);
        organization.businessRolesMap.put("owner", owner);

        person.organizationsMap.put(10L, organization);
        return person;
    }
}
