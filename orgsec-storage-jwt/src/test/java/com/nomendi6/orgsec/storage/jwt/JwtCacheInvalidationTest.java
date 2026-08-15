package com.nomendi6.orgsec.storage.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A cached principal must not keep deciding on state the delegate has already left behind.
 *
 * <p>{@code personCache} holds the <em>enriched</em> principal, and enrichment copies the organization
 * name, both hierarchy anchors, the organization roles and the business roles out of the delegate. None of
 * that comes from the token, so all of it goes stale when the delegate changes - and the anchors decide
 * hierarchy privileges directly.
 *
 * <p>Nothing evicted such an entry on its own. The key is the whole token, which in an OIDC session flow is
 * stable for the life of the session; the LRU bound is only reached once a thousand other principals have
 * been seen more recently; and the notification methods for organization and role changes forwarded to the
 * delegate without touching the cache. When the delegate is InMemory the divergence opened inside the same
 * call, because there the notification triggers a full reload.
 */
class JwtCacheInvalidationTest {

    private static final Long ORG_ID = 15L;
    private static final Long PERSON_ID = 1L;
    private static final Long ROLE_ID = 77L;
    private static final String TOKEN = "token-that-does-not-change";

    private static final String ANCHOR_BEFORE = "|1|10|15|";
    private static final String ANCHOR_AFTER = "|1|20|15|";

    private JwtTokenContextHolder tokenContextHolder;
    private SecurityDataStorage delegateStorage;
    private JwtSecurityDataStorage storage;
    private OrganizationDef delegateOrganization;

    @BeforeEach
    void setUp() {
        JwtClaimsParser claimsParser = mock(JwtClaimsParser.class);
        tokenContextHolder = new JwtTokenContextHolder();
        delegateStorage = mock(SecurityDataStorage.class);
        storage = new JwtSecurityDataStorage(claimsParser, tokenContextHolder, delegateStorage);

        delegateOrganization = organizationAnchoredAt(ANCHOR_BEFORE);
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization);

        // A fresh PersonDef per parse, so a stale result can only come from the cache, never from the parser
        // handing back the same mutated object.
        when(claimsParser.parsePersonFromToken(TOKEN)).thenAnswer(invocation -> membershipOnlyPrincipal());
        when(claimsParser.getPositionRoleIds(TOKEN, ORG_ID)).thenReturn(List.of());
        tokenContextHolder.setToken(TOKEN);
    }

    @Test
    void anOrganizationChangeMakesTheNextReadSeeTheNewAnchor() {
        assertThat(anchorSeenByPrincipal()).isEqualTo(ANCHOR_BEFORE);

        delegateOrganization.parentPath = ANCHOR_AFTER;
        storage.notifyOrganizationChanged(ORG_ID);

        assertThat(anchorSeenByPrincipal())
            .as("the token never changed, so only invalidation can surface the delegate's new anchor")
            .isEqualTo(ANCHOR_AFTER);
    }

    @Test
    void aPartyRoleChangeMakesTheNextReadSeeTheNewState() {
        assertThat(anchorSeenByPrincipal()).isEqualTo(ANCHOR_BEFORE);

        delegateOrganization.parentPath = ANCHOR_AFTER;
        storage.notifyPartyRoleChanged(ROLE_ID);

        assertThat(anchorSeenByPrincipal())
            .as("enrichment copies organization roles from the delegate, so a role change invalidates too")
            .isEqualTo(ANCHOR_AFTER);
    }

    @Test
    void aPositionRoleChangeMakesTheNextReadSeeTheNewState() {
        assertThat(anchorSeenByPrincipal()).isEqualTo(ANCHOR_BEFORE);

        delegateOrganization.parentPath = ANCHOR_AFTER;
        storage.notifyPositionRoleChanged(ROLE_ID);

        assertThat(anchorSeenByPrincipal())
            .as("position roles are resolved through the delegate and merged into the business roles")
            .isEqualTo(ANCHOR_AFTER);
    }

    /** Without any notification the entry is expected to stay cached - that is the point of the cache. */
    @Test
    void repeatedReadsWithoutANotificationAreServedFromTheCache() {
        assertThat(anchorSeenByPrincipal()).isEqualTo(ANCHOR_BEFORE);

        delegateOrganization.parentPath = ANCHOR_AFTER;

        assertThat(anchorSeenByPrincipal()).isEqualTo(ANCHOR_BEFORE);
    }

    // --- fixture ------------------------------------------------------------------------------

    private String anchorSeenByPrincipal() {
        PersonDef person = storage.getPerson(PERSON_ID);
        assertThat(person).isNotNull();
        return person.organizationsMap.get(ORG_ID).parentPath;
    }

    private static PersonDef membershipOnlyPrincipal() {
        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        OrganizationDef membership = new OrganizationDef();
        membership.organizationId = ORG_ID;
        person.organizationsMap.put(ORG_ID, membership);
        return person;
    }

    private static OrganizationDef organizationAnchoredAt(String anchor) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = ORG_ID;
        organization.organizationName = "Org 15";
        organization.parentPath = anchor;
        organization.companyParentPath = "|1|";
        return organization;
    }
}
