package com.nomendi6.orgsec.storage.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The delegate is the authority for a claimed membership: whether the organization exists, which
 * company it belongs to, and where it sits in the hierarchy.
 *
 * <p>A token asserts membership once, at issue time, and is then carried around while the
 * organization graph keeps changing. Hierarchy anchors in particular cannot come from the claim at
 * all - {@code pathId} names the organization's own segment and says nothing about its ancestry -
 * and {@code PrivilegeChecker} compares against those fields directly.
 *
 * <p>A membership the delegate does not confirm is <strong>removed</strong> rather than weakened.
 * Clearing only the anchors still leaves {@code EXACT} privileges matching on the organization id,
 * which is the same unverified assertion in a quieter form.
 */
class JwtHierarchyAnchorTest {

    private static final Long ORG_ID = 15L;
    private static final Long OTHER_ORG_ID = 16L;
    private static final Long PERSON_ID = 1L;
    private static final Long COMPANY_ID = 1L;
    private static final String TOKEN = "token-a";

    /** What the delegate stores: the full path of this node, per docs/reference/concepts.md. */
    private static final String DELEGATE_ORG_PATH = "|1|10|15|";
    private static final String DELEGATE_COMPANY_PATH = "|1|";

    private JwtClaimsParser claimsParser;
    private JwtTokenContextHolder tokenContextHolder;
    private SecurityDataStorage delegateStorage;
    private JwtSecurityDataStorage storage;

    @BeforeEach
    void setUp() {
        claimsParser = mock(JwtClaimsParser.class);
        tokenContextHolder = new JwtTokenContextHolder();
        delegateStorage = mock(SecurityDataStorage.class);
        storage = new JwtSecurityDataStorage(claimsParser, tokenContextHolder, delegateStorage);

        givenClaimedMemberships(claimedMembership(ORG_ID, COMPANY_ID));
        tokenContextHolder.setToken(TOKEN);
    }

    @Test
    void takesTheOrgAnchorFromTheDelegate() {
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization(COMPANY_ID));

        OrganizationDef membership = membershipOf(storage.getPerson(PERSON_ID));

        assertThat(membership.parentPath).isEqualTo(DELEGATE_ORG_PATH);
    }

    @Test
    void takesTheCompanyAnchorFromTheDelegateBecauseNoClaimCarriesIt() {
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization(COMPANY_ID));

        OrganizationDef membership = membershipOf(storage.getPerson(PERSON_ID));

        assertThat(membership.companyParentPath).isEqualTo(DELEGATE_COMPANY_PATH);
    }

    @Test
    void dropsTheMembershipWhenTheDelegateDoesNotKnowTheOrganization() {
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(null);

        PersonDef person = storage.getPerson(PERSON_ID);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap)
            .as("an unconfirmed membership must not survive as an id-only entry that EXACT can still match")
            .doesNotContainKey(ORG_ID);
    }

    @Test
    void dropsTheMembershipWhenTheClaimedCompanyDoesNotMatchTheDelegate() {
        // Company-scoped privileges are evaluated against companyId. Trusting the token here would
        // let a token pick its own tenant.
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization(999L));

        PersonDef person = storage.getPerson(PERSON_ID);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap).doesNotContainKey(ORG_ID);
    }

    @Test
    void dropsTheMembershipWhenTheClaimNamesNoCompanyAtAll() {
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization(COMPANY_ID));
        givenClaimedMemberships(claimedMembership(ORG_ID, null));

        PersonDef person = storage.getPerson(PERSON_ID);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap).doesNotContainKey(ORG_ID);
    }

    @Test
    void keepsTheConfirmedMembershipsAndDropsOnlyTheRejectedOne() {
        givenClaimedMemberships(claimedMembership(ORG_ID, COMPANY_ID), claimedMembership(OTHER_ORG_ID, COMPANY_ID));
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(null);
        when(delegateStorage.getOrganization(OTHER_ORG_ID)).thenReturn(delegateOrganization(COMPANY_ID));

        PersonDef person = storage.getPerson(PERSON_ID);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap).containsOnlyKeys(OTHER_ORG_ID);
    }

    // --- fixture ------------------------------------------------------------------------------

    private void givenClaimedMemberships(OrganizationDef... memberships) {
        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        for (OrganizationDef membership : memberships) {
            person.organizationsMap.put(membership.organizationId, membership);
        }
        Map<Long, List<Long>> roleIds = person.organizationsMap.keySet().stream()
            .collect(java.util.stream.Collectors.toMap(orgId -> orgId, orgId -> List.of()));

        when(claimsParser.parsePrincipalFromToken(TOKEN))
            .thenReturn(new JwtClaimsParser.ParsedPrincipal(person, roleIds));
    }

    /** What the parser produces: ids and the local segment, and nothing hierarchical. */
    private static OrganizationDef claimedMembership(Long orgId, Long companyId) {
        OrganizationDef membership = new OrganizationDef();
        membership.organizationId = orgId;
        membership.companyId = companyId;
        membership.pathId = String.valueOf(orgId);
        return membership;
    }

    private static OrganizationDef delegateOrganization(Long companyId) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = ORG_ID;
        organization.organizationName = "Org 15";
        organization.companyId = companyId;
        organization.parentPath = DELEGATE_ORG_PATH;
        organization.companyParentPath = DELEGATE_COMPANY_PATH;
        return organization;
    }

    private static OrganizationDef membershipOf(PersonDef person) {
        assertThat(person).isNotNull();
        return person.organizationsMap.get(ORG_ID);
    }
}
