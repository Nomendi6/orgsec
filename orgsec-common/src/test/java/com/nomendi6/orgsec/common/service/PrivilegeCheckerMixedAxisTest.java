package com.nomendi6.orgsec.common.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * A role holding privileges on two different axes - company plus organization-subtree, or person
 * plus organization-subtree - and what each authorization path does with it.
 *
 * <p>These are the shapes where GET and LIST are built differently. LIST iterates the privileges and
 * ORs a clause per privilege, so both axes contribute. GET is handed the <em>aggregate</em>
 * {@link PrivilegeDef} and walks the company -> org -> person cascade, which stops after the first
 * axis that is configured: an org privilege is only consulted when the aggregate's company direction
 * is {@code NONE}, and a person privilege only when both company and org are.
 *
 * <p>The result is a documented divergence on this line, characterised below: a record that matches
 * only on the second axis is returned by a list endpoint and refused by a direct read of the same
 * row. Each branch is asserted on its own so the boundary is explicit rather than implied.
 */
class PrivilegeCheckerMixedAxisTest {

    private static final String RESOURCE = "document";
    private static final String OWNER = SecurityConstants.BusinessRoles.OWNER;
    private static final Long PERSON_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long COMPANY_ID = 5L;
    private static final String PRINCIPAL_ORG_PATH = "|A|B|";
    private static final String INSIDE_SUBTREE = "|A|B|C|";
    private static final String OUTSIDE_SUBTREE = "|A|Z|";

    private PrivilegeChecker privilegeChecker;
    private RsqlFilterBuilder filterBuilder;
    private SecurityDataStorage storage;
    private PersonData currentPerson;

    @BeforeEach
    void setUp() {
        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        roleConfiguration.initializeBusinessRoles();

        privilegeChecker = new PrivilegeChecker(roleConfiguration);
        storage = mock(SecurityDataStorage.class);
        filterBuilder = new RsqlFilterBuilder(new SecurityDataStore(storage), roleConfiguration);
        currentPerson = new PersonData(PERSON_ID, "Alice");
    }

    // ------------------------------------------------------------------- company + org subtree

    @Test
    void companyPlusOrgSubtreeGrantsOnTheCompanyBranch() {
        ResourceDef resource = resourceWith(companyExact(), orgHierarchyDown());

        assertThat(checkGet(resource, COMPANY_ID, "|A|", null, OUTSIDE_SUBTREE))
            .as("the record belongs to the principal's company")
            .isTrue();
    }

    @Test
    void companyPlusOrgSubtreeOnTheOrgBranchIsWhereGetAndListDiverge() {
        ResourceDef resource = resourceWith(companyExact(), orgHierarchyDown());

        // LIST returns the row: the org clause is ORed in.
        assertThat(buildFilter(resource))
            .as("the list filter carries both selectors")
            .contains("ownerOrgPath")
            .contains("ownerCompany.id");

        // GET refuses it: the aggregate's company direction is EXACT, not NONE, so the cascade
        // never reaches the org axis. Characterised, not endorsed - see the divergence note above.
        assertThat(checkGet(resource, 999L, "|Z|", ORG_ID, INSIDE_SUBTREE))
            .as("a record inside the org subtree but in another company")
            .isFalse();
    }

    // -------------------------------------------------------------------- person + org subtree

    @Test
    void personPlusOrgSubtreeGrantsOnTheOrgBranch() {
        ResourceDef resource = resourceWith(personPrivilege(), orgHierarchyDown());

        assertThat(checkGet(resource, null, null, ORG_ID, INSIDE_SUBTREE)).isTrue();
    }

    @Test
    void personPlusOrgSubtreeOnThePersonBranchIsWhereGetAndListDiverge() {
        ResourceDef resource = resourceWith(personPrivilege(), orgHierarchyDown());

        assertThat(buildFilter(resource))
            .as("the list filter carries both selectors")
            .contains("ownerOrgPath")
            .contains("ownerPerson.id");

        // The record names the principal as its person but sits outside the subtree. The aggregate's
        // org direction is HIERARCHY_DOWN, not NONE, so the person axis is never reached.
        assertThat(checkGet(resource, null, null, null, OUTSIDE_SUBTREE)).isFalse();
    }

    // ------------------------------------------------------------------- single axis, unchanged

    @Test
    void aSingleAxisRoleIsUnaffected() {
        ResourceDef resource = resourceWith(orgHierarchyDown());

        assertThat(checkGet(resource, null, null, ORG_ID, INSIDE_SUBTREE)).isTrue();
        assertThat(checkGet(resource, null, null, ORG_ID, OUTSIDE_SUBTREE)).isFalse();
        assertThat(buildFilter(resource)).contains("ownerOrgPath").doesNotContain("ownerCompany.id");
    }

    // --- fixture ------------------------------------------------------------------------------

    private boolean checkGet(
        ResourceDef resource,
        Long recordCompanyId,
        String recordCompanyPath,
        Long recordOrgId,
        String recordOrgPath
    ) {
        PrivilegeDef aggregate = privilegeChecker.getResourcePrivileges(resource, PrivilegeOperation.READ);
        return privilegeChecker.checkOrganizationPrivilege(
            currentPerson,
            principalOrganization(),
            aggregate,
            recordCompanyId,
            recordCompanyPath,
            recordOrgId,
            recordOrgPath,
            PERSON_ID,
            true,
            true,
            true
        );
    }

    private String buildFilter(ResourceDef resource) {
        OrganizationDef organization = principalOrganization();
        BusinessRoleDef role = new BusinessRoleDef(OWNER);
        role.resourcesMap.put(RESOURCE, resource);
        organization.businessRolesMap.put(OWNER, role);

        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        person.organizationsMap.put(ORG_ID, organization);
        when(storage.getPerson(PERSON_ID)).thenReturn(person);

        try {
            return filterBuilder.buildRsqlFilterForPrivileges(RESOURCE, null, List.of(OWNER), PrivilegeOperation.READ, currentPerson);
        } catch (AccessDeniedException e) {
            return "<denied>";
        }
    }

    private static OrganizationDef principalOrganization() {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = ORG_ID;
        organization.companyId = COMPANY_ID;
        organization.parentPath = PRINCIPAL_ORG_PATH;
        organization.companyParentPath = "|A|";
        return organization;
    }

    /** Built through RoleDef so the aggregate is produced exactly as it is at runtime. */
    private static ResourceDef resourceWith(PrivilegeDef... privileges) {
        RoleDef role = new RoleDef(1L, "MIXED_AXIS_ROLE");
        role.addBusinessRole(OWNER);
        for (PrivilegeDef privilege : privileges) {
            role.addPrivilegeDef(privilege);
        }
        return role.resourcesMap.get(RESOURCE);
    }

    private static PrivilegeDef companyExact() {
        return new PrivilegeDef(RESOURCE + "_COMP_R", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.EXACT, PrivilegeDirection.NONE, false);
    }

    private static PrivilegeDef orgHierarchyDown() {
        return new PrivilegeDef(RESOURCE + "_ORGHD_R", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false);
    }

    private static PrivilegeDef personPrivilege() {
        return new PrivilegeDef(RESOURCE + "_EMP_R", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.NONE, true);
    }
}
