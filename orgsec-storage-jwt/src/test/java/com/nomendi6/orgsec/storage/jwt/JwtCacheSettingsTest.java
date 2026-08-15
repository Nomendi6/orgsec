package com.nomendi6.orgsec.storage.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code cache-parsed-person} and {@code cache-ttl-seconds} have to do what they say.
 *
 * <p>Both were read from configuration and then ignored: the storage never received the properties, so an
 * entry lived as long as the process. The TTL is a backstop for the changes the library cannot see - a Redis
 * delegate updated by another instance, or an application that never publishes the change notifications -
 * so leaving it unwired removed the only bound on those.
 */
class JwtCacheSettingsTest {

    private static final Long ORG_ID = 15L;
    private static final Long PERSON_ID = 1L;
    private static final String TOKEN = "token-that-does-not-change";

    private static final String ANCHOR_BEFORE = "|1|10|15|";
    private static final String ANCHOR_AFTER = "|1|20|15|";

    private JwtClaimsParser claimsParser;
    private JwtTokenContextHolder tokenContextHolder;
    private SecurityDataStorage delegateStorage;
    private OrganizationDef delegateOrganization;
    private AtomicLong now;

    @BeforeEach
    void setUp() {
        claimsParser = mock(JwtClaimsParser.class);
        tokenContextHolder = new JwtTokenContextHolder();
        delegateStorage = mock(SecurityDataStorage.class);
        now = new AtomicLong(1_000_000L);

        delegateOrganization = new OrganizationDef();
        delegateOrganization.organizationId = ORG_ID;
        delegateOrganization.parentPath = ANCHOR_BEFORE;
        when(delegateStorage.getOrganization(ORG_ID)).thenReturn(delegateOrganization);

        when(claimsParser.parsePersonFromToken(TOKEN)).thenAnswer(invocation -> membershipOnlyPrincipal());
        when(claimsParser.getPositionRoleIds(TOKEN, ORG_ID)).thenReturn(List.of());
        tokenContextHolder.setToken(TOKEN);
    }

    @Test
    void cachingDisabledMeansEveryReadReEnriches() {
        JwtSecurityDataStorage storage = storageWith(false, 0);

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_BEFORE);
        delegateOrganization.parentPath = ANCHOR_AFTER;

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_AFTER);
    }

    @Test
    void anEntryIsReusedWhileItIsWithinTheTtl() {
        JwtSecurityDataStorage storage = storageWith(true, 60);

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_BEFORE);
        delegateOrganization.parentPath = ANCHOR_AFTER;
        now.addAndGet(59_000);

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_BEFORE);
    }

    @Test
    void anEntryIsReEnrichedOnceTheTtlHasPassed() {
        JwtSecurityDataStorage storage = storageWith(true, 60);

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_BEFORE);
        delegateOrganization.parentPath = ANCHOR_AFTER;
        now.addAndGet(60_000);

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_AFTER);
    }

    /** Zero means no expiry, so only the notification methods invalidate - the documented reading. */
    @Test
    void aTtlOfZeroNeverExpires() {
        JwtSecurityDataStorage storage = storageWith(true, 0);

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_BEFORE);
        delegateOrganization.parentPath = ANCHOR_AFTER;
        now.addAndGet(10 * 365 * 24 * 3600 * 1000L);

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_BEFORE);
    }

    /** The three-argument constructor is existing API and must keep its current behaviour. */
    @Test
    void theLegacyConstructorCachesWithoutExpiry() {
        JwtSecurityDataStorage storage = new JwtSecurityDataStorage(claimsParser, tokenContextHolder, delegateStorage);

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_BEFORE);
        delegateOrganization.parentPath = ANCHOR_AFTER;

        assertThat(anchorSeenBy(storage)).isEqualTo(ANCHOR_BEFORE);
    }

    // --- fixture ------------------------------------------------------------------------------

    private JwtSecurityDataStorage storageWith(boolean cacheParsedPerson, int ttlSeconds) {
        return new JwtSecurityDataStorage(
            claimsParser,
            tokenContextHolder,
            delegateStorage,
            cacheParsedPerson,
            ttlSeconds,
            now::get
        );
    }

    private String anchorSeenBy(JwtSecurityDataStorage storage) {
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
}
