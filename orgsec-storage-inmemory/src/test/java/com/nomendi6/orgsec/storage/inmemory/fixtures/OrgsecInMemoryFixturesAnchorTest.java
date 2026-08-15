package com.nomendi6.orgsec.storage.inmemory.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nomendi6.orgsec.common.service.BusinessRoleConfiguration;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.inmemory.InMemorySecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.OrganizationLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.RoleLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPrivilegesStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fixture has to produce the same two path fields a real party query returns, because those fields are
 * what the evaluator compares.
 *
 * <p>The contract is one-way: {@code pathId} is the local segment and no evaluator reads it, while
 * {@code parentPath} is the node's own full path from the root and is the sole hierarchy anchor. The
 * generated import data shows the same shape - a root carries {@code path_id 'ow'} with
 * {@code parent_path '|ow|'}.
 *
 * <p>The fixture used to invert them: the full path went into {@code pathId} and {@code parentPath} held the
 * STRICT parent. Sibling nodes then shared one anchor, and roots had none. The existing parity test could
 * not catch it, because the loader tuples it compares against were hand-written with the same inversion -
 * which is why these assertions are on the values themselves and on what they decide.
 */
class OrgsecInMemoryFixturesAnchorTest {

    private static final long COMPANY = 1L;
    private static final long LEFT = 10L;
    private static final long RIGHT = 11L;
    private static final long LEAF = 100L;

    private AllOrganizationsStore organizationsStore;
    private OrgsecInMemoryFixtures fixtures;

    @BeforeEach
    void setUp() {
        AllPersonsStore personsStore = new AllPersonsStore();
        organizationsStore = new AllOrganizationsStore();
        AllRolesStore rolesStore = new AllRolesStore();
        AllPrivilegesStore privilegesStore = new AllPrivilegesStore();

        InMemorySecurityDataStorage storage = new InMemorySecurityDataStorage(
            personsStore,
            organizationsStore,
            rolesStore,
            privilegesStore,
            new PersonLoader(rolesStore, personsStore),
            new OrganizationLoader(rolesStore, organizationsStore),
            new RoleLoader(privilegesStore, rolesStore),
            new PrivilegeLoader(privilegesStore),
            mock(SecurityQueryProvider.class)
        );
        fixtures = new OrgsecInMemoryFixtures(
            personsStore,
            organizationsStore,
            rolesStore,
            privilegesStore,
            storage,
            mock(BusinessRoleConfiguration.class)
        );
        givenTwoSiblingsAndALeaf();
    }

    @Test
    void theAnchorIsTheNodesOwnFullPath() {
        assertThat(organization(COMPANY).parentPath).isEqualTo("|1|");
        assertThat(organization(LEFT).parentPath).isEqualTo("|1|10|");
        assertThat(organization(RIGHT).parentPath).isEqualTo("|1|11|");
        assertThat(organization(LEAF).parentPath).isEqualTo("|1|10|100|");
    }

    @Test
    void pathIdIsTheLocalSegment() {
        assertThat(organization(COMPANY).pathId).isEqualTo("1");
        assertThat(organization(LEFT).pathId).isEqualTo("10");
        assertThat(organization(LEAF).pathId).isEqualTo("100");
    }

    /** A root is a legitimate hierarchy position; it used to come out with no anchor at all. */
    @Test
    void aRootGetsAnAnchorRatherThanNull() {
        assertThat(organization(COMPANY).parentPath).isNotNull();
    }

    /** Siblings must be distinguishable, which is exactly what a shared strict-parent anchor destroyed. */
    @Test
    void siblingsDoNotShareAnAnchor() {
        assertThat(organization(LEFT).parentPath).isNotEqualTo(organization(RIGHT).parentPath);
    }

    /**
     * The two comparisons the anchor feeds, stated the way {@code PrivilegeChecker} states them.
     *
     * <p>Asserting the strings alone would not show that the earlier values were wrong, because they were
     * well-formed paths - just the wrong ones.
     */
    @Test
    void theAnchorDecidesHierarchyTheWayItShould() {
        String principal = organization(LEFT).parentPath;

        assertThat(organization(LEAF).parentPath.startsWith(principal))
            .as("HIERARCHY_DOWN reaches the principal's own subtree")
            .isTrue();
        assertThat(organization(RIGHT).parentPath.startsWith(principal))
            .as("HIERARCHY_DOWN must not reach a sibling subtree")
            .isFalse();
        assertThat(principal.startsWith(organization(COMPANY).parentPath))
            .as("HIERARCHY_UP reaches an ancestor")
            .isTrue();
        assertThat(principal.startsWith(organization(LEFT).parentPath))
            .as("HIERARCHY_UP is reflexive - the principal's own organization counts")
            .isTrue();
    }

    // --- fixture ------------------------------------------------------------------------------

    private void givenTwoSiblingsAndALeaf() {
        fixtures
            .load()
            .company(COMPANY, "Acme")
            .organizationUnder(LEFT, "Left", COMPANY)
            .organizationUnder(RIGHT, "Right", COMPANY)
            .organizationUnder(LEAF, "Leaf", LEFT)
            .apply();
    }

    private OrganizationDef organization(long id) {
        OrganizationDef organization = organizationsStore.getOrganization(id);
        assertThat(organization).as("organization %s", id).isNotNull();
        return organization;
    }
}
