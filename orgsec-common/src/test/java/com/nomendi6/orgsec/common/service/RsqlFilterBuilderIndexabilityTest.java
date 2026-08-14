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
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The generated filter has to be something a database can serve from an index, and it must not
 * repeat work.
 *
 * <p>Two properties are pinned here. Subtree matching uses the case-sensitive {@code =^*}: the
 * case-insensitive {@code =*} compiles to {@code lower(col) LIKE ...}, which no index on the path
 * column can serve. And clauses are de-duplicated and folded: a principal holding the same scope
 * through several organizations or business roles produced the same clause repeatedly, and a broad
 * subtree clause makes the narrower subtrees it already covers redundant.
 */
class RsqlFilterBuilderIndexabilityTest {

    private static final String RESOURCE = "document";
    private static final String OWNER = SecurityConstants.BusinessRoles.OWNER;
    private static final String CUSTOMER = SecurityConstants.BusinessRoles.CUSTOMER;
    private static final Long PERSON_ID = 1L;

    private SecurityDataStorage storage;
    private RsqlFilterBuilder filterBuilder;
    private PersonData currentPerson;
    private PersonDef person;

    @BeforeEach
    void setUp() {
        storage = mock(SecurityDataStorage.class);
        currentPerson = new PersonData(PERSON_ID, "Alice");
        person = new PersonDef(PERSON_ID, "Alice");
        when(storage.getPerson(PERSON_ID)).thenReturn(person);

        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(new DefaultBusinessRoleProvider()));
        roleConfiguration.initializeBusinessRoles();
        filterBuilder = new RsqlFilterBuilder(new SecurityDataStore(storage), roleConfiguration);
    }

    /** Subtree matching must stay sargable - no {@code lower()} around the path column. */
    @Test
    void subtreeMatchingUsesTheCaseSensitiveLikeOperator() {
        givenOrganization(10L, "|A|B|", OWNER, PrivilegeDirection.HIERARCHY_DOWN);

        String filter = read();

        assertThat(filter).contains("=^*'|A|B|*'");
        assertThat(filter)
            .as("the case-insensitive form compiles to lower(col) LIKE and cannot use an index")
            .doesNotContain("=*'|A|B|*'");
    }

    /** The same scope reached through two business roles must not be emitted twice. */
    @Test
    void identicalClausesFromDifferentBusinessRolesAreEmittedOnce() {
        OrganizationDef organization = organization(10L, "|A|B|");
        organization.businessRolesMap.put(OWNER, businessRole(OWNER, PrivilegeDirection.HIERARCHY_DOWN));
        organization.businessRolesMap.put(CUSTOMER, businessRole(CUSTOMER, PrivilegeDirection.HIERARCHY_DOWN));
        person.organizationsMap.put(10L, organization);

        String filter = read();

        assertThat(occurrences(filter, "=^*'|A|B|*'")).isEqualTo(1);
    }

    /** The same scope reached through two organizations must not be emitted twice either. */
    @Test
    void identicalClausesFromDifferentOrganizationsAreEmittedOnce() {
        givenOrganization(10L, "|A|B|", OWNER, PrivilegeDirection.HIERARCHY_DOWN);
        givenOrganization(20L, "|A|B|", OWNER, PrivilegeDirection.HIERARCHY_DOWN);

        String filter = read();

        assertThat(occurrences(filter, "=^*'|A|B|*'")).isEqualTo(1);
    }

    /** A broad subtree absorbs the narrower subtrees nested inside it. */
    @Test
    void aBroaderSubtreeAbsorbsTheNarrowerOnesItAlreadyCovers() {
        givenOrganization(10L, "|A|", OWNER, PrivilegeDirection.HIERARCHY_DOWN);
        givenOrganization(20L, "|A|B|", OWNER, PrivilegeDirection.HIERARCHY_DOWN);
        givenOrganization(30L, "|A|B|C|", OWNER, PrivilegeDirection.HIERARCHY_DOWN);

        String filter = read();

        assertThat(filter).contains("=^*'|A|*'");
        assertThat(filter).doesNotContain("|A|B|*'");
        assertThat(filter).doesNotContain("|A|B|C|*'");
    }

    /** Folding is prefix containment on rooted paths, so a sibling branch is never absorbed. */
    @Test
    void aSiblingBranchIsNotAbsorbed() {
        givenOrganization(10L, "|A|", OWNER, PrivilegeDirection.HIERARCHY_DOWN);
        givenOrganization(20L, "|AX|", OWNER, PrivilegeDirection.HIERARCHY_DOWN);

        String filter = read();

        assertThat(filter).contains("=^*'|A|*'");
        assertThat(filter).as("|AX| is not inside |A| - the trailing separator makes that decidable").contains("=^*'|AX|*'");
    }

    /** Only the subtree form folds; ancestor and exact clauses are left alone. */
    @Test
    void ancestorAndExactClausesAreNotFolded() {
        givenOrganization(10L, "|A|B|", OWNER, PrivilegeDirection.HIERARCHY_UP);
        givenOrganization(20L, "|A|", OWNER, PrivilegeDirection.EXACT);

        String filter = read();

        assertThat(filter).contains("=in=");
        assertThat(filter).contains("==");
    }

    // --- fixture ------------------------------------------------------------------------------

    private String read() {
        return filterBuilder.buildRsqlFilterForPrivileges(RESOURCE, null, null, PrivilegeOperation.READ, currentPerson);
    }

    private void givenOrganization(Long orgId, String path, String role, PrivilegeDirection direction) {
        OrganizationDef organization = organization(orgId, path);
        organization.businessRolesMap.put(role, businessRole(role, direction));
        person.organizationsMap.put(orgId, organization);
    }

    private static OrganizationDef organization(Long orgId, String path) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = orgId;
        organization.parentPath = path;
        return organization;
    }

    private static BusinessRoleDef businessRole(String name, PrivilegeDirection direction) {
        ResourceDef resource = new ResourceDef(RESOURCE);
        resource
            .getPrivilegesList()
            .add(
                new PrivilegeDef(RESOURCE + "_probe", RESOURCE)
                    .allowOperation(PrivilegeOperation.READ)
                    .allowOrg(PrivilegeDirection.NONE, direction, false)
            );

        BusinessRoleDef role = new BusinessRoleDef(name);
        role.resourcesMap.put(RESOURCE, resource);
        return role;
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
