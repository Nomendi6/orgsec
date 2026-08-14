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
import org.springframework.security.access.AccessDeniedException;

/**
 * {@code allowedBusinessRoles} restricts which business roles the filter may draw privileges from.
 *
 * <p>The parameter used to be accepted and never applied, so a query scoped to one business role
 * still evaluated every role the principal held. A principal whose unrelated role carried a broader
 * privilege therefore received a filter built from that role - and an {@code ALL} privilege there
 * produced an empty filter, which every consumer reads as "no filtering".
 */
class RsqlFilterBuilderAllowedRolesTest {

    private static final String RESOURCE = "document";
    private static final String OWNER = SecurityConstants.BusinessRoles.OWNER;
    private static final String CUSTOMER = SecurityConstants.BusinessRoles.CUSTOMER;
    private static final Long PERSON_ID = 1L;
    private static final Long ORG_ID = 10L;

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

    /** The reported over-grant: owner-only query, but the customer role carries ALL. */
    @Test
    void shouldNotDrawPrivilegesFromARoleTheCallerDidNotAllow() {
        givenPerson(
            businessRole(OWNER, orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN)),
            businessRole(CUSTOMER, allPrivilege())
        );

        String ownerOnly = filterBuilder.buildRsqlFilterForPrivileges(
            RESOURCE,
            null,
            List.of(OWNER),
            PrivilegeOperation.READ,
            currentPerson
        );

        assertThat(ownerOnly)
            .as("an empty filter would mean 'no filtering' - the customer ALL privilege must not leak in")
            .isNotEmpty();
    }

    /** Without a restriction the customer role is still evaluated, as before. */
    @Test
    void shouldEvaluateEveryRoleWhenNoRestrictionIsGiven() {
        givenPerson(
            businessRole(OWNER, orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN)),
            businessRole(CUSTOMER, allPrivilege())
        );

        String unrestricted = filterBuilder.buildRsqlFilterForPrivileges(
            RESOURCE,
            null,
            null,
            PrivilegeOperation.READ,
            currentPerson
        );

        assertThat(unrestricted).as("the ALL privilege on customer grants everything").isEmpty();
    }

    /** A restriction naming a role the principal does not hold denies. */
    @Test
    void shouldDenyWhenTheAllowedRoleCarriesNoPrivilege() {
        givenPerson(businessRole(CUSTOMER, allPrivilege()));

        assertThatThrownBy(() ->
                filterBuilder.buildRsqlFilterForPrivileges(RESOURCE, null, List.of(OWNER), PrivilegeOperation.READ, currentPerson)
            )
            .isInstanceOf(AccessDeniedException.class);
    }

    /** An empty restriction denies - the caller asked for no roles at all. */
    @Test
    void shouldDenyOnAnEmptyAllowedRolesList() {
        givenPerson(businessRole(OWNER, orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN)));

        assertThatThrownBy(() ->
                filterBuilder.buildRsqlFilterForPrivileges(RESOURCE, null, List.of(), PrivilegeOperation.READ, currentPerson)
            )
            .isInstanceOf(AccessDeniedException.class);
    }

    /** Business role names are matched case-insensitively, as everywhere else. */
    @Test
    void shouldMatchAllowedRolesCaseInsensitively() {
        givenPerson(businessRole(OWNER, orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN)));

        String filter = filterBuilder.buildRsqlFilterForPrivileges(
            RESOURCE,
            null,
            List.of(OWNER.toUpperCase()),
            PrivilegeOperation.READ,
            currentPerson
        );

        assertThat(filter).isNotEmpty();
    }

    /** {@code buildRsqlFilterForBasicPrivileges} is documented as owner-only and must behave so. */
    @Test
    void basicPrivilegesMustBeOwnerOnly() {
        givenPerson(businessRole(CUSTOMER, allPrivilege()));

        assertThatThrownBy(() -> filterBuilder.buildRsqlFilterForBasicPrivileges(RESOURCE, null, currentPerson))
            .as("documented as 'owner role only'")
            .isInstanceOf(AccessDeniedException.class);
    }

    // --- fixture ------------------------------------------------------------------------------

    private void givenPerson(BusinessRoleDef... roles) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = ORG_ID;
        organization.parentPath = "|A|B|";
        for (BusinessRoleDef role : roles) {
            organization.businessRolesMap.put(role.businessRoleName, role);
        }

        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        person.organizationsMap.put(ORG_ID, organization);
        when(storage.getPerson(PERSON_ID)).thenReturn(person);
    }

    private static BusinessRoleDef businessRole(String name, PrivilegeDef privilege) {
        ResourceDef resource = new ResourceDef(RESOURCE);
        resource.getPrivilegesList().add(privilege);

        BusinessRoleDef role = new BusinessRoleDef(name);
        role.resourcesMap.put(RESOURCE, resource);
        return role;
    }

    private static PrivilegeDef orgPrivilege(PrivilegeDirection org) {
        return new PrivilegeDef(RESOURCE + "_ORGHD_R", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, org, false);
    }

    private static PrivilegeDef allPrivilege() {
        return new PrivilegeDef(RESOURCE + "_ALL_R", RESOURCE).allowOperation(PrivilegeOperation.READ).allowAll(true);
    }
}
