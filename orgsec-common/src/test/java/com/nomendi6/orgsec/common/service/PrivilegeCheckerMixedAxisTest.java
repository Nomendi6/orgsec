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
 * plus organization-subtree - must grant on <em>either</em> axis, on both authorization paths.
 *
 * <p>This is the shape a single {@link PrivilegeDef} cannot express. Its cascade evaluates company,
 * then org only if company is {@code NONE}, then person only if both are - so summarising the two
 * privileges into one aggregate loses whichever axis comes second. Both paths therefore evaluate the
 * privileges <em>individually</em> and OR the outcomes: {@code PrivilegeSecurityService} iterates
 * {@code ResourceDef.privilegesList} per record, {@code RsqlFilterBuilder} iterates the same list and
 * ORs a clause per privilege.
 *
 * <p>Each branch is asserted on its own, because a test that only exercises a record matching both
 * axes passes even when the second one is being dropped.
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
    void companyPlusOrgSubtreeGrantsOnTheOrgBranchToo() {
        ResourceDef resource = resourceWith(companyExact(), orgHierarchyDown());

        assertThat(buildFilter(resource))
            .as("the list filter carries both selectors")
            .contains("ownerOrgPath")
            .contains("ownerCompany.id");

        assertThat(checkGet(resource, 999L, "|Z|", ORG_ID, INSIDE_SUBTREE))
            .as("a record inside the org subtree, in another company - the org privilege alone must grant it")
            .isTrue();
    }

    @Test
    void companyPlusOrgSubtreeStillDeniesARecordOnNeitherAxis() {
        ResourceDef resource = resourceWith(companyExact(), orgHierarchyDown());

        assertThat(checkGet(resource, 999L, "|Z|", 77L, OUTSIDE_SUBTREE)).isFalse();
    }

    // -------------------------------------------------------------------- person + org subtree

    @Test
    void personPlusOrgSubtreeGrantsOnTheOrgBranch() {
        ResourceDef resource = resourceWith(personPrivilege(), orgHierarchyDown());

        assertThat(checkGet(resource, null, null, ORG_ID, INSIDE_SUBTREE)).isTrue();
    }

    @Test
    void personPlusOrgSubtreeGrantsOnThePersonBranchToo() {
        ResourceDef resource = resourceWith(personPrivilege(), orgHierarchyDown());

        assertThat(buildFilter(resource))
            .as("the list filter carries both selectors")
            .contains("ownerOrgPath")
            .contains("ownerPerson.id");

        assertThat(checkGet(resource, null, null, 77L, OUTSIDE_SUBTREE))
            .as("the record names the principal as its person but sits outside the subtree")
            .isTrue();
    }

    @Test
    void personPlusOrgSubtreeStillDeniesSomeoneElsesRecordOutsideTheSubtree() {
        ResourceDef resource = resourceWith(personPrivilege(), orgHierarchyDown());
        PersonData someoneElse = new PersonData(999L, "Bob");

        boolean granted = privilegeChecker.checkOrganizationPrivilege(
            someoneElse, principalOrganization(), personPrivilege(),
            null, null, 77L, OUTSIDE_SUBTREE, PERSON_ID,
            true, true, true
        );

        assertThat(granted).isFalse();
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

    /**
     * Mirrors the per-record loop in {@code PrivilegeSecurityService}: every privilege in the list is
     * evaluated on its own and the outcomes are ORed. Deliberately not
     * {@code getResourcePrivileges(...)} - that returns the aggregate, which exists only as the
     * empty-list compatibility fallback and would collapse exactly the axis under test.
     */
    private boolean checkGet(
        ResourceDef resource,
        Long recordCompanyId,
        String recordCompanyPath,
        Long recordOrgId,
        String recordOrgPath
    ) {
        for (PrivilegeDef privilege : resource.getPrivilegesList()) {
            if (!privilegeChecker.hasRequiredOperation(privilege, PrivilegeOperation.READ)) {
                continue;
            }
            boolean granted = privilegeChecker.checkOrganizationPrivilege(
                currentPerson,
                principalOrganization(),
                privilege,
                recordCompanyId,
                recordCompanyPath,
                recordOrgId,
                recordOrgPath,
                PERSON_ID,
                true,
                true,
                true
            );
            if (granted) {
                return true;
            }
        }
        return false;
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
