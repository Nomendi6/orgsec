package com.nomendi6.orgsec.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.common.store.SecurityDataStore;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.constants.SecurityConstants;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

/**
 * How the filter builder spells "deny".
 *
 * <p>The empty string already means something here: it is what a caller receives for an {@code all}
 * grant, and every consumer reads it as "no filtering needed". A privilege that cannot be turned into
 * a clause therefore must not produce one - it has to be dropped, and if nothing else grants, the
 * call must raise {@link AccessDeniedException}. Returning {@code ""} from a validation failure would
 * turn the tightest possible answer into unrestricted access to the whole table.
 *
 * <p>{@code selector==null} is the same trap in another shape: depending on the RSQL dialect it is a
 * parse error or a clause matching every row whose column is null.
 */
class RsqlFilterBuilderDenyClauseTest {

    private static final String RESOURCE = "document";
    private static final String OWNER = SecurityConstants.BusinessRoles.OWNER;
    private static final Long PERSON_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long COMPANY_ID = 5L;

    private SecurityDataStorage storage;
    private RsqlFilterBuilder filterBuilder;
    private PersonData currentPerson;

    @BeforeEach
    void setUp() {
        storage = mock(SecurityDataStorage.class);
        currentPerson = new PersonData(PERSON_ID, "Alice");

        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        roleConfiguration.initializeBusinessRoles();
        filterBuilder = new RsqlFilterBuilder(new SecurityDataStore(storage), roleConfiguration);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "|", "|A|B", "|A||", "|A$|" })
    void anUnusableAnchorDeniesInsteadOfProducingAnEmptyFilter(String anchor) {
        givenPrincipalAnchoredAt(anchor, orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN));

        assertThatThrownBy(() -> buildFilter())
            .as("anchor %s yields no clause, and no other privilege grants", anchor)
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aDeniedHierarchyPrivilegeDoesNotWidenAnotherRolesFilter() {
        // The dangerous shape: one privilege denies, another grants. If the denied one contributed
        // "" the OR would collapse to "no filtering" and silently return the whole table.
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = ORG_ID;
        organization.companyId = COMPANY_ID;
        organization.parentPath = "|A|B";          // unusable: no closing separator
        organization.companyParentPath = "|A|B|";  // usable

        ResourceDef resource = new ResourceDef(RESOURCE);
        resource.getPrivilegesList().add(orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN));
        resource.getPrivilegesList().add(companyPrivilege(PrivilegeDirection.HIERARCHY_DOWN));
        givenPerson(organization, businessRole(resource));

        String filter = buildFilter();

        assertThat(filter)
            .isNotEqualTo("")
            .isNotNull()
            .as("only the company clause survives")
            .contains("CompanyPath");
    }

    @Test
    void exactDeniesRatherThanEmittingASelectorComparedToNull() {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = ORG_ID;
        organization.companyId = null; // the principal has no company
        organization.parentPath = "|A|B|";

        ResourceDef resource = new ResourceDef(RESOURCE);
        resource.getPrivilegesList().add(companyPrivilege(PrivilegeDirection.EXACT));
        givenPerson(organization, businessRole(resource));

        assertThatThrownBy(() -> buildFilter()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anAllGrantStillProducesTheEmptyFilter() {
        // The one legitimate producer of "". If this regressed, every list endpoint would start
        // filtering where the privilege says it should not.
        givenPrincipalAnchoredAt("|A|B|", new PrivilegeDef(RESOURCE + "_ALL_R", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowAll(true));

        assertThat(buildFilter()).isEmpty();
    }

    @Test
    void aUsableAnchorStillProducesAClause() {
        givenPrincipalAnchoredAt("|A|B|", orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN));

        assertThat(buildFilter()).isNotEmpty().contains("|A|B|");
    }

    @Test
    void axisAllDeniesOnTheListPathToo() {
        // Matches PrivilegeCheckerDirectionGuardTest.axisAllNeverGrants - GET and LIST must agree.
        givenPrincipalAnchoredAt("|A|B|", orgPrivilege(PrivilegeDirection.ALL));

        assertThatThrownBy(() -> buildFilter()).isInstanceOf(AccessDeniedException.class);
    }

    // --- fixture ------------------------------------------------------------------------------

    private String buildFilter() {
        return filterBuilder.buildRsqlFilterForPrivileges(RESOURCE, null, List.of(OWNER), PrivilegeOperation.READ, currentPerson);
    }

    private void givenPrincipalAnchoredAt(String orgAnchor, PrivilegeDef privilege) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = ORG_ID;
        organization.companyId = COMPANY_ID;
        organization.parentPath = orgAnchor;
        organization.companyParentPath = orgAnchor;

        ResourceDef resource = new ResourceDef(RESOURCE);
        resource.getPrivilegesList().add(privilege);
        givenPerson(organization, businessRole(resource));
    }

    private void givenPerson(OrganizationDef organization, BusinessRoleDef role) {
        organization.businessRolesMap.put(role.businessRoleName, role);
        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        person.organizationsMap.put(ORG_ID, organization);
        when(storage.getPerson(PERSON_ID)).thenReturn(person);
    }

    private static BusinessRoleDef businessRole(ResourceDef resource) {
        BusinessRoleDef role = new BusinessRoleDef(OWNER);
        role.resourcesMap.put(RESOURCE, resource);
        return role;
    }

    private static PrivilegeDef orgPrivilege(PrivilegeDirection org) {
        return new PrivilegeDef(RESOURCE + "_ORGHD_R", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, org, false);
    }

    private static PrivilegeDef companyPrivilege(PrivilegeDirection company) {
        return new PrivilegeDef(RESOURCE + "_COMPHD_R", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(company, PrivilegeDirection.NONE, false);
    }
}
