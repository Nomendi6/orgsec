package com.nomendi6.orgsec.storage.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
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

/**
 * Enriching one principal must not write into the delegate's stored organization.
 *
 * <p>{@code enrichPersonWithRoles} used to copy the delegate's business-role map by reference and
 * then merge the request's position roles into those very objects. When the delegate's organization
 * already carried the business role - which it does whenever the organization has organization-level
 * roles - the merge wrote into the delegate's own {@code BusinessRoleDef}, so the next principal of
 * that organization inherited the previous one's privileges.
 */
class JwtDelegateIsolationTest {

    private static final Long ORG_ID = 10L;
    private static final String BUSINESS_ROLE = "owner";
    private static final String SHARED_RESOURCE = "DOCUMENT";

    private JwtClaimsParser claimsParser;
    private JwtTokenContextHolder tokenContextHolder;
    private SecurityDataStorage delegateStorage;
    private JwtSecurityDataStorage storage;
    private OrganizationDef delegateOrganization;

    @BeforeEach
    void setUp() {
        claimsParser = mock(JwtClaimsParser.class);
        tokenContextHolder = new JwtTokenContextHolder();
        delegateStorage = mock(SecurityDataStorage.class);

        // The delegate's stored organization already carries the business role, so enrichment takes
        // the merge branch rather than creating a fresh BusinessRoleDef.
        delegateOrganization = new OrganizationDef();
        delegateOrganization.organizationId = ORG_ID;
        delegateOrganization.parentPath = "|A|B|";
        BusinessRoleDef organizationLevel = new BusinessRoleDef(BUSINESS_ROLE);
        organizationLevel.resourcesMap.put(SHARED_RESOURCE, resourceWith("DOCUMENT_ORG_R", PrivilegeDirection.EXACT));
        delegateOrganization.businessRolesMap.put(BUSINESS_ROLE, organizationLevel);

        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization);

        storage = new JwtSecurityDataStorage(claimsParser, tokenContextHolder, delegateStorage);
    }

    @Test
    void shouldNotLeakOnePrincipalsPrivilegesIntoTheNextPrincipal() {
        givenPrincipal("token-alice", 1L, "Alice", 100L, "DOCUMENT_ORGHD_R", PrivilegeDirection.HIERARCHY_DOWN);
        givenPrincipal("token-bob", 2L, "Bob", 200L, "DOCUMENT_ORGHU_R", PrivilegeDirection.HIERARCHY_UP);

        tokenContextHolder.setToken("token-alice");
        PersonDef alice = storage.getPerson(1L);
        assertThat(privilegeNamesOf(alice)).containsExactlyInAnyOrder("DOCUMENT_ORG_R", "DOCUMENT_ORGHD_R");

        tokenContextHolder.setToken("token-bob");
        PersonDef bob = storage.getPerson(2L);

        assertThat(privilegeNamesOf(bob))
            .as("Bob must not inherit Alice's DOCUMENT_ORGHD_R")
            .containsExactlyInAnyOrder("DOCUMENT_ORG_R", "DOCUMENT_ORGHU_R");
    }

    @Test
    void shouldLeaveTheDelegateStoredOrganizationUntouched() {
        givenPrincipal("token-alice", 1L, "Alice", 100L, "DOCUMENT_ORGHD_R", PrivilegeDirection.HIERARCHY_DOWN);

        tokenContextHolder.setToken("token-alice");
        storage.getPerson(1L);

        ResourceDef stored = delegateOrganization.businessRolesMap.get(BUSINESS_ROLE).resourcesMap.get(SHARED_RESOURCE);
        assertThat(stored.getPrivilegesList())
            .as("the delegate's own organization must be unchanged after enrichment")
            .extracting(privilege -> privilege.name)
            .containsExactly("DOCUMENT_ORG_R");
    }

    @Test
    void shouldGiveEachPrincipalItsOwnBusinessRoleInstance() {
        givenPrincipal("token-alice", 1L, "Alice", 100L, "DOCUMENT_ORGHD_R", PrivilegeDirection.HIERARCHY_DOWN);

        tokenContextHolder.setToken("token-alice");
        PersonDef alice = storage.getPerson(1L);

        BusinessRoleDef principalRole = alice.organizationsMap.get(ORG_ID).businessRolesMap.get(BUSINESS_ROLE);
        assertThat(principalRole).isNotSameAs(delegateOrganization.businessRolesMap.get(BUSINESS_ROLE));
        assertThat(principalRole.resourcesMap.get(SHARED_RESOURCE))
            .isNotSameAs(delegateOrganization.businessRolesMap.get(BUSINESS_ROLE).resourcesMap.get(SHARED_RESOURCE));
    }

    // --- fixture ------------------------------------------------------------------------------

    private void givenPrincipal(
        String token,
        Long personId,
        String personName,
        Long positionRoleId,
        String privilegeName,
        PrivilegeDirection direction
    ) {
        PersonDef person = new PersonDef(personId, personName);
        OrganizationDef membership = new OrganizationDef();
        membership.organizationId = ORG_ID;
        membership.parentPath = "|A|B|";
        person.organizationsMap.put(ORG_ID, membership);

        when(claimsParser.parsePersonFromToken(token)).thenReturn(person);
        when(claimsParser.getPositionRoleIds(token, ORG_ID)).thenReturn(List.of(positionRoleId));

        RoleDef positionRole = new RoleDef(positionRoleId, privilegeName + "_ROLE");
        positionRole.addBusinessRole(BUSINESS_ROLE);
        positionRole.addPrivilegeDef(
            new PrivilegeDef(privilegeName, SHARED_RESOURCE)
                .allowOperation(PrivilegeOperation.READ)
                .allowOrg(PrivilegeDirection.NONE, direction, false)
        );
        when(delegateStorage.getPositionRole(positionRoleId)).thenReturn(positionRole);
    }

    private static ResourceDef resourceWith(String privilegeName, PrivilegeDirection direction) {
        ResourceDef resource = new ResourceDef(SHARED_RESOURCE);
        resource
            .getPrivilegesList()
            .add(
                new PrivilegeDef(privilegeName, SHARED_RESOURCE)
                    .allowOperation(PrivilegeOperation.READ)
                    .allowOrg(PrivilegeDirection.NONE, direction, false)
            );
        return resource;
    }

    private static List<String> privilegeNamesOf(PersonDef person) {
        return person
            .organizationsMap.get(ORG_ID)
            .businessRolesMap.get(BUSINESS_ROLE)
            .resourcesMap.get(SHARED_RESOURCE)
            .getPrivilegesList()
            .stream()
            .map(privilege -> privilege.name)
            .toList();
    }
}
