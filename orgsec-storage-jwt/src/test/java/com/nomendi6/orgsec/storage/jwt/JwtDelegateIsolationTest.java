package com.nomendi6.orgsec.storage.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a token principal may and may not take from the delegate's stored organization.
 *
 * <p>Enrichment used to copy the delegate organization's {@code businessRolesMap} and
 * {@code organizationRolesSet} into the principal. Those are the roles the organization confers on
 * its <em>party members</em>, which the token holder may well not be - so every token-authenticated
 * principal received the union of everyone's privileges in that organization. The copy was also by
 * reference, so merging the request's own position roles wrote back into the delegate and the next
 * principal inherited the previous one's privileges.
 *
 * <p>Since 1.0.5 nothing role-shaped is copied: a principal's privileges come only from the position
 * roles its own claim names. Only the organization's identity - name and hierarchy anchors - is
 * taken from the delegate.
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

        // The delegate's stored organization carries an organization-level business role and an
        // organization role. Neither belongs to a token principal.
        delegateOrganization = new OrganizationDef();
        delegateOrganization.organizationId = ORG_ID;
        delegateOrganization.parentPath = "|A|B|";
        BusinessRoleDef organizationLevel = new BusinessRoleDef(BUSINESS_ROLE);
        organizationLevel.resourcesMap.put(SHARED_RESOURCE, resourceWith("DOCUMENT_ORG_R", PrivilegeDirection.EXACT));
        delegateOrganization.businessRolesMap.put(BUSINESS_ROLE, organizationLevel);
        delegateOrganization.organizationRolesSet.add(partyRoleGranting("DOCUMENT_ALL_RW"));

        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization);

        storage = new JwtSecurityDataStorage(claimsParser, tokenContextHolder, delegateStorage);
    }

    @Test
    void shouldNotGrantTheOrganizationsOwnBusinessRolesToATokenPrincipal() {
        givenPrincipal("token-alice", 1L, "Alice", 100L, "DOCUMENT_ORGHD_R", PrivilegeDirection.HIERARCHY_DOWN);

        tokenContextHolder.setToken("token-alice");
        PersonDef alice = storage.getPerson(1L);

        assertThat(privilegeNamesOf(alice))
            .as("DOCUMENT_ORG_R belongs to the organization's party members, not to whoever holds a token")
            .containsExactly("DOCUMENT_ORGHD_R");
    }

    @Test
    void shouldNotGrantTheOrganizationsPartyRolesToATokenPrincipal() {
        givenPrincipal("token-alice", 1L, "Alice", 100L, "DOCUMENT_ORGHD_R", PrivilegeDirection.HIERARCHY_DOWN);

        tokenContextHolder.setToken("token-alice");
        PersonDef alice = storage.getPerson(1L);

        assertThat(alice.organizationsMap.get(ORG_ID).organizationRolesSet)
            .as("a DOCUMENT_ALL_RW party role must stay invisible to a token principal")
            .isEmpty();
    }

    @Test
    void shouldNotLeakOnePrincipalsPrivilegesIntoTheNextPrincipal() {
        givenPrincipal("token-alice", 1L, "Alice", 100L, "DOCUMENT_ORGHD_R", PrivilegeDirection.HIERARCHY_DOWN);
        givenPrincipal("token-bob", 2L, "Bob", 200L, "DOCUMENT_ORGHU_R", PrivilegeDirection.HIERARCHY_UP);

        tokenContextHolder.setToken("token-alice");
        PersonDef alice = storage.getPerson(1L);
        assertThat(privilegeNamesOf(alice)).containsExactly("DOCUMENT_ORGHD_R");

        tokenContextHolder.setToken("token-bob");
        PersonDef bob = storage.getPerson(2L);

        assertThat(privilegeNamesOf(bob))
            .as("Bob must not inherit Alice's DOCUMENT_ORGHD_R")
            .containsExactly("DOCUMENT_ORGHU_R");
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

        assertThat(alice.organizationsMap.get(ORG_ID).businessRolesMap.get(BUSINESS_ROLE))
            .isNotSameAs(delegateOrganization.businessRolesMap.get(BUSINESS_ROLE));
    }

    @Test
    void shouldStillTakeTheOrganizationsIdentityFromTheDelegate() {
        givenPrincipal("token-alice", 1L, "Alice", 100L, "DOCUMENT_ORGHD_R", PrivilegeDirection.HIERARCHY_DOWN);
        delegateOrganization.organizationName = "EU Region";

        tokenContextHolder.setToken("token-alice");
        OrganizationDef membership = storage.getPerson(1L).organizationsMap.get(ORG_ID);

        assertThat(membership.parentPath).isEqualTo("|A|B|");
        assertThat(membership.organizationName).isEqualTo("EU Region");
    }

    @Test
    void shouldNotReReadRoleIdsFromTheTokenPerOrganization() {
        givenPrincipal("token-alice", 1L, "Alice", 100L, "DOCUMENT_ORGHD_R", PrivilegeDirection.HIERARCHY_DOWN);

        tokenContextHolder.setToken("token-alice");
        storage.getPerson(1L);

        // A second, independent parse could pair role ids with memberships differently - and it
        // swallows failures as an empty list, so a claim already rejected could still yield roles.
        verify(claimsParser, never()).getPositionRoleIds(any(), any());
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
        person.organizationsMap.put(ORG_ID, membership);

        when(claimsParser.parsePrincipalFromToken(token))
            .thenReturn(new JwtClaimsParser.ParsedPrincipal(person, Map.of(ORG_ID, List.of(positionRoleId))));

        RoleDef positionRole = new RoleDef(positionRoleId, privilegeName + "_ROLE");
        positionRole.addBusinessRole(BUSINESS_ROLE);
        positionRole.addPrivilegeDef(
            new PrivilegeDef(privilegeName, SHARED_RESOURCE)
                .allowOperation(PrivilegeOperation.READ)
                .allowOrg(PrivilegeDirection.NONE, direction, false)
        );
        when(delegateStorage.getPositionRole(positionRoleId)).thenReturn(positionRole);
    }

    private static RoleDef partyRoleGranting(String privilegeName) {
        RoleDef partyRole = new RoleDef(900L, privilegeName + "_ROLE");
        partyRole.addBusinessRole(BUSINESS_ROLE);
        partyRole.addPrivilegeDef(
            new PrivilegeDef(privilegeName, SHARED_RESOURCE)
                .allowOperation(PrivilegeOperation.WRITE)
                .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_DOWN, false)
        );
        return partyRole;
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
