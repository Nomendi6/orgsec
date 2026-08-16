package com.nomendi6.orgsec.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which operand each direction requires, and what a prefix comparison is allowed to match.
 *
 * <p>The gates used to demand an id <em>and</em> a usable path for every direction. That denied
 * records the list filter grants: {@code EXACT} compares ids and never reads a path, so a record
 * with no path is perfectly decidable, and the hierarchy directions compare paths and never read the
 * record's id. GET and LIST have to answer the same for the same row, so the gate is now
 * direction-specific.
 *
 * <p>The comparison itself is only equivalent to "is a descendant of" when both paths are canonical.
 * {@code |A|B} without its closing separator makes {@code startsWith} match {@code |A|BX|C|} - a
 * different branch whose first segment merely begins with the same characters.
 */
class PrivilegeCheckerDirectionGuardTest {

    private static final Long PRINCIPAL_ORG = 10L;
    private static final Long PRINCIPAL_COMPANY = 1L;

    private PrivilegeChecker privilegeChecker;
    private PersonData currentPerson;

    @BeforeEach
    void setUp() {
        privilegeChecker = new PrivilegeChecker(mock(BusinessRoleConfiguration.class));
        currentPerson = new PersonData(1L, "Alice");
    }

    // ------------------------------------------------------------------ EXACT ignores the path

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "|", "not-a-path" })
    void exactGrantsOnMatchingIdsWhateverTheRecordPathIs(String recordPath) {
        // The path is not part of an EXACT decision, so an absent or unusable one cannot make the
        // ids stop matching. Demanding a usable path here was a narrowing against 1.0.4.
        OrganizationDef principal = anchoredPrincipal();

        assertThat(checkOrg(principal, PrivilegeDirection.EXACT, PRINCIPAL_ORG, recordPath)).isTrue();
        assertThat(checkCompany(principal, PrivilegeDirection.EXACT, PRINCIPAL_COMPANY, recordPath)).isTrue();
    }

    @Test
    void exactStillDeniesOnDifferentIds() {
        OrganizationDef principal = anchoredPrincipal();

        assertThat(checkOrg(principal, PrivilegeDirection.EXACT, 999L, null)).isFalse();
        assertThat(checkCompany(principal, PrivilegeDirection.EXACT, 999L, null)).isFalse();
    }

    @Test
    void exactDeniesWhenTheRecordHasNoId() {
        OrganizationDef principal = anchoredPrincipal();

        assertThat(checkOrg(principal, PrivilegeDirection.EXACT, null, "|A|B|")).isFalse();
        assertThat(checkCompany(principal, PrivilegeDirection.EXACT, null, "|A|B|")).isFalse();
    }

    // ------------------------------------------------------- hierarchy ignores the record's id

    @Test
    void hierarchyGrantsWithoutTheRecordsIdWhenThePathsMatch() {
        OrganizationDef principal = anchoredPrincipal();

        assertThat(checkOrg(principal, PrivilegeDirection.HIERARCHY_DOWN, null, "|A|B|C|"))
            .as("a record inside the principal's subtree is readable whether or not it names an org id")
            .isTrue();
        assertThat(checkCompany(principal, PrivilegeDirection.HIERARCHY_UP, null, "|A|"))
            .as("a record on the principal's ancestor chain, with no company id of its own")
            .isTrue();
    }

    // ----------------------------------------------------------------- segment-boundary safety

    @Test
    void deniesASiblingBranchWhoseNameSharesAPrefix() {
        OrganizationDef principal = new OrganizationDef();
        principal.organizationId = PRINCIPAL_ORG;
        principal.companyId = PRINCIPAL_COMPANY;
        // Non-canonical: no closing separator. "|A|BX|C|".startsWith("|A|B") is true.
        principal.parentPath = "|A|B";
        principal.companyParentPath = "|A|B";

        assertThat(checkOrg(principal, PrivilegeDirection.HIERARCHY_DOWN, null, "|A|BX|C|"))
            .as("|A|BX|C| is a different branch, not a descendant of |A|B|")
            .isFalse();
        assertThat(checkCompany(principal, PrivilegeDirection.HIERARCHY_DOWN, null, "|A|BX|C|")).isFalse();
    }

    @Test
    void grantsARealDescendantOfACanonicalAnchor() {
        OrganizationDef principal = new OrganizationDef();
        principal.organizationId = PRINCIPAL_ORG;
        principal.companyId = PRINCIPAL_COMPANY;
        principal.parentPath = "|A|B|";
        principal.companyParentPath = "|A|B|";

        assertThat(checkOrg(principal, PrivilegeDirection.HIERARCHY_DOWN, null, "|A|B|C|")).isTrue();
        assertThat(checkCompany(principal, PrivilegeDirection.HIERARCHY_DOWN, null, "|A|B|C|")).isTrue();
    }

    @Test
    void deniesAMalformedRecordPathWithoutThrowing() {
        OrganizationDef principal = anchoredPrincipal();

        for (String malformed : new String[] { "|A||", "|A$|", "A|B|" }) {
            assertThat(checkOrg(principal, PrivilegeDirection.HIERARCHY_DOWN, null, malformed))
                .as("record path %s", malformed)
                .isFalse();
        }
    }

    // ------------------------------------------------------------------------------ axis ALL

    @Test
    void axisAllNeverGrants() {
        // ALL is the top of the direction lattice used by the union algebra, not a scope an
        // evaluator can act on - unrestricted access is the separate PrivilegeDef.all flag.
        OrganizationDef principal = anchoredPrincipal();

        assertThat(checkOrg(principal, PrivilegeDirection.ALL, PRINCIPAL_ORG, "|A|B|C|")).isFalse();
        assertThat(checkCompany(principal, PrivilegeDirection.ALL, PRINCIPAL_COMPANY, "|A|B|C|")).isFalse();
    }

    @Test
    void directionNoneNeverGrants() {
        OrganizationDef principal = anchoredPrincipal();

        assertThat(checkOrg(principal, PrivilegeDirection.NONE, PRINCIPAL_ORG, "|A|B|C|")).isFalse();
        assertThat(checkCompany(principal, PrivilegeDirection.NONE, PRINCIPAL_COMPANY, "|A|B|C|")).isFalse();
    }

    // --- fixture ------------------------------------------------------------------------------

    private static OrganizationDef anchoredPrincipal() {
        OrganizationDef principal = new OrganizationDef();
        principal.organizationId = PRINCIPAL_ORG;
        principal.companyId = PRINCIPAL_COMPANY;
        principal.parentPath = "|A|B|";
        principal.companyParentPath = "|A|B|";
        return principal;
    }

    private boolean checkCompany(OrganizationDef principal, PrivilegeDirection direction, Long recordId, String recordPath) {
        PrivilegeDef privilege = new PrivilegeDef("probe", "document")
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(direction, PrivilegeDirection.NONE, false);
        return privilegeChecker.checkOrganizationPrivilege(
            currentPerson, principal, privilege,
            recordId, recordPath,
            null, null,
            null,
            true, false, false
        );
    }

    private boolean checkOrg(OrganizationDef principal, PrivilegeDirection direction, Long recordId, String recordPath) {
        PrivilegeDef privilege = new PrivilegeDef("probe", "document")
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, direction, false);
        return privilegeChecker.checkOrganizationPrivilege(
            currentPerson, principal, privilege,
            null, null,
            recordId, recordPath,
            null,
            false, true, false
        );
    }
}
