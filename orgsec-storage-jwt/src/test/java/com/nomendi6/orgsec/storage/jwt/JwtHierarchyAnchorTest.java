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
 * The hierarchy anchors a JWT principal is evaluated against must come from the delegate, not from the
 * token.
 *
 * <p>The claim carries only {@code pathId}, so {@code JwtClaimsParser} derived {@code parentPath} from it
 * - and derived the strict parent, one level above the full-path-of-this-node that every other backend
 * stores. {@code PrivilegeChecker} compares against that field directly, so a principal at
 * {@code |1|10|15|} was anchored at {@code |1|10|} and HIERARCHY_DOWN granted every sibling subtree under
 * organization 10. For a root-level membership the derived anchor collapses to {@code "|"}, which every
 * well-formed path starts with, so the grant covered every organization in the system.
 *
 * <p>{@code companyParentPath} had no claim at all and stayed null, which is a different failure: the
 * company hierarchy comparisons dereferenced it.
 *
 * <p>The delegate holds whatever path convention the application maintains, so copying from it keeps JWT
 * principals deciding the same way InMemory and Redis do without this class knowing that convention.
 */
class JwtHierarchyAnchorTest {

    private static final Long ORG_ID = 15L;
    private static final Long COMPANY_ID = 1L;
    private static final Long PERSON_ID = 1L;
    private static final String TOKEN = "token-a";

    /** What the delegate stores: the full path of this node, per docs/reference/concepts.md. */
    private static final String DELEGATE_ORG_PATH = "|1|10|15|";
    private static final String DELEGATE_COMPANY_PATH = "|1|";

    /** What the parser derives from the claim: the strict parent, one level too high. */
    private static final String TOKEN_DERIVED_ORG_PATH = "|1|10|";

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

        when(claimsParser.parsePrincipalFromToken(TOKEN)).thenReturn(
            new JwtClaimsParser.ParsedPrincipal(principalAnchoredByToken(), Map.of(ORG_ID, List.of()))
        );
        tokenContextHolder.setToken(TOKEN);
    }

    @Test
    void takesTheOrgAnchorFromTheDelegateRatherThanTheToken() {
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization());

        OrganizationDef membership = membershipOf(storage.getPerson(PERSON_ID));

        assertThat(membership.parentPath).isEqualTo(DELEGATE_ORG_PATH);
        assertThat(membership.parentPath)
            .as("the token-derived anchor sits one level too high and grants the sibling subtree")
            .isNotEqualTo(TOKEN_DERIVED_ORG_PATH);
    }

    @Test
    void takesTheCompanyAnchorFromTheDelegateBecauseNoClaimCarriesIt() {
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization());

        OrganizationDef membership = membershipOf(storage.getPerson(PERSON_ID));

        assertThat(membership.companyParentPath).isEqualTo(DELEGATE_COMPANY_PATH);
    }

    /** Without a delegate entry there is no trustworthy anchor, so neither may be left in place. */
    @Test
    void dropsMembershipWhenTheDelegateDoesNotKnowTheOrganization() {
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(null);

        PersonDef person = storage.getPerson(PERSON_ID);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap)
            .as("an unconfirmed id-only membership could still grant EXACT privileges")
            .doesNotContainKey(ORG_ID);
    }

    @Test
    void dropsMembershipWhenClaimedCompanyDoesNotMatchTheDelegate() {
        PersonDef mismatched = principalAnchoredByToken();
        mismatched.organizationsMap.get(ORG_ID).companyId = 999L;
        when(claimsParser.parsePrincipalFromToken(TOKEN)).thenReturn(
            new JwtClaimsParser.ParsedPrincipal(mismatched, Map.of(ORG_ID, List.of()))
        );
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization());

        PersonDef person = storage.getPerson(PERSON_ID);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap)
            .as("an organization id cannot be trusted under a different claimed company")
            .doesNotContainKey(ORG_ID);
    }

    // --- fixture ------------------------------------------------------------------------------

    private static PersonDef principalAnchoredByToken() {
        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        OrganizationDef membership = new OrganizationDef();
        membership.organizationId = ORG_ID;
        membership.companyId = COMPANY_ID;
        membership.pathId = DELEGATE_ORG_PATH;
        membership.parentPath = TOKEN_DERIVED_ORG_PATH;
        person.organizationsMap.put(ORG_ID, membership);
        return person;
    }

    private static OrganizationDef delegateOrganization() {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = ORG_ID;
        organization.companyId = COMPANY_ID;
        organization.organizationName = "Org 15";
        organization.parentPath = DELEGATE_ORG_PATH;
        organization.companyParentPath = DELEGATE_COMPANY_PATH;
        return organization;
    }

    private static OrganizationDef membershipOf(PersonDef person) {
        assertThat(person).isNotNull();
        return person.organizationsMap.get(ORG_ID);
    }
}
