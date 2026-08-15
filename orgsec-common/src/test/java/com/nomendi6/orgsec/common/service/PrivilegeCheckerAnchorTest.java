package com.nomendi6.orgsec.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A hierarchical comparison needs the principal's own anchor path, and the principal side is not
 * covered by the {@code shouldCheckXxx} gates - those validate the record.
 *
 * <p>A backend that cannot supply the anchor therefore reached the comparison with null and raised a
 * {@link NullPointerException}, which {@code checkBusinessRolePrivilege} does not catch (it catches only
 * {@link IllegalArgumentException}), so the request failed instead of the record being denied. The list
 * filter refuses the same condition with a warning, so the two authorization paths disagreed: same
 * principal, same privilege, GET threw and LIST returned nothing.
 *
 * <p>The company axis reached this state on every JWT deployment, because no claim carries a company
 * path and nothing populated {@code companyParentPath}.
 */
class PrivilegeCheckerAnchorTest {

    private static final Long PRINCIPAL_ORG = 10L;
    private static final Long PRINCIPAL_COMPANY = 1L;
    private static final String RECORD_PATH = "|A|B|";

    private PrivilegeChecker privilegeChecker;
    private PersonData currentPerson;

    @BeforeEach
    void setUp() {
        privilegeChecker = new PrivilegeChecker(mock(BusinessRoleConfiguration.class));
        currentPerson = new PersonData(1L, "Alice");
    }

    @Test
    void deniesCompanyHierarchyDownWhenThePrincipalHasNoCompanyAnchor() {
        OrganizationDef principal = principalWithoutCompanyAnchor();

        assertThatCode(() -> checkCompany(principal, PrivilegeDirection.HIERARCHY_DOWN)).doesNotThrowAnyException();
        assertThat(checkCompany(principal, PrivilegeDirection.HIERARCHY_DOWN)).isFalse();
    }

    @Test
    void deniesCompanyHierarchyUpWhenThePrincipalHasNoCompanyAnchor() {
        OrganizationDef principal = principalWithoutCompanyAnchor();

        assertThatCode(() -> checkCompany(principal, PrivilegeDirection.HIERARCHY_UP)).doesNotThrowAnyException();
        assertThat(checkCompany(principal, PrivilegeDirection.HIERARCHY_UP)).isFalse();
    }

    @Test
    void deniesOrgHierarchyWhenThePrincipalHasNoOrgAnchor() {
        OrganizationDef principal = principalWithoutOrgAnchor();

        for (PrivilegeDirection direction : new PrivilegeDirection[] { PrivilegeDirection.HIERARCHY_DOWN, PrivilegeDirection.HIERARCHY_UP }) {
            assertThatCode(() -> checkOrg(principal, direction)).doesNotThrowAnyException();
            assertThat(checkOrg(principal, direction)).as("direction %s", direction).isFalse();
        }
    }

    /** EXACT compares ids, and the principal's own id can be absent for the same reason. */
    @Test
    void deniesExactWhenThePrincipalHasNoId() {
        OrganizationDef principal = new OrganizationDef();
        principal.parentPath = "|A|B|";
        principal.companyParentPath = "|A|";

        assertThatCode(() -> checkOrg(principal, PrivilegeDirection.EXACT)).doesNotThrowAnyException();
        assertThat(checkOrg(principal, PrivilegeDirection.EXACT)).isFalse();
        assertThatCode(() -> checkCompany(principal, PrivilegeDirection.EXACT)).doesNotThrowAnyException();
        assertThat(checkCompany(principal, PrivilegeDirection.EXACT)).isFalse();
    }

    /** The guard must not change the answer when the anchor is present. */
    @Test
    void stillGrantsWhenTheAnchorIsPresent() {
        OrganizationDef principal = new OrganizationDef();
        principal.organizationId = PRINCIPAL_ORG;
        principal.companyId = PRINCIPAL_COMPANY;
        principal.parentPath = "|A|B|C|";
        principal.companyParentPath = "|A|B|C|";

        assertThat(checkOrg(principal, PrivilegeDirection.HIERARCHY_UP))
            .as("record |A|B| is an ancestor of the principal |A|B|C|")
            .isTrue();
        assertThat(checkCompany(principal, PrivilegeDirection.HIERARCHY_UP)).isTrue();
    }

    /**
     * A path that matches everything must not grant, whichever side carries it.
     *
     * <p>The gates checked only {@code != null}, so an empty path went through and
     * {@code startsWith("")} answered true for every record. The bare separator does the same while
     * being well-formed: {@code validatePath} accepts it, and every path starts with it.
     *
     * <p>Direction decides which side is dangerous. HIERARCHY_UP compares
     * {@code principal.startsWith(record)}, so an unusable RECORD path grants; HIERARCHY_DOWN compares
     * {@code record.startsWith(principal)}, so an unusable PRINCIPAL path grants.
     */
    @Test
    void refusesAPathThatWouldMatchEveryRecord() {
        OrganizationDef principal = new OrganizationDef();
        principal.organizationId = PRINCIPAL_ORG;
        principal.companyId = PRINCIPAL_COMPANY;
        principal.parentPath = "|A|B|";
        principal.companyParentPath = "|A|B|";

        for (String unusable : new String[] { "", "|" }) {
            assertThat(checkOrg(principal, PrivilegeDirection.HIERARCHY_UP, unusable))
                .as("record org path %s must not grant upward", quoted(unusable))
                .isFalse();
            assertThat(checkCompany(principal, PrivilegeDirection.HIERARCHY_UP, unusable))
                .as("record company path %s must not grant upward", quoted(unusable))
                .isFalse();

            OrganizationDef anchored = new OrganizationDef();
            anchored.organizationId = PRINCIPAL_ORG;
            anchored.companyId = PRINCIPAL_COMPANY;
            anchored.parentPath = unusable;
            anchored.companyParentPath = unusable;

            assertThat(checkOrg(anchored, PrivilegeDirection.HIERARCHY_DOWN))
                .as("principal org path %s must not grant downward", quoted(unusable))
                .isFalse();
            assertThat(checkCompany(anchored, PrivilegeDirection.HIERARCHY_DOWN))
                .as("principal company path %s must not grant downward", quoted(unusable))
                .isFalse();
        }
    }

    // --- fixture ------------------------------------------------------------------------------

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private static OrganizationDef principalWithoutCompanyAnchor() {
        OrganizationDef principal = new OrganizationDef();
        principal.organizationId = PRINCIPAL_ORG;
        principal.companyId = PRINCIPAL_COMPANY;
        principal.parentPath = "|A|B|";
        principal.companyParentPath = null;
        return principal;
    }

    private static OrganizationDef principalWithoutOrgAnchor() {
        OrganizationDef principal = new OrganizationDef();
        principal.organizationId = PRINCIPAL_ORG;
        principal.companyId = PRINCIPAL_COMPANY;
        principal.parentPath = null;
        principal.companyParentPath = "|A|";
        return principal;
    }

    private boolean checkCompany(OrganizationDef principal, PrivilegeDirection direction) {
        return checkCompany(principal, direction, RECORD_PATH);
    }

    private boolean checkCompany(OrganizationDef principal, PrivilegeDirection direction, String recordPath) {
        PrivilegeDef privilege = new PrivilegeDef("probe", "document")
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(direction, PrivilegeDirection.NONE, false);
        return privilegeChecker.checkOrganizationPrivilege(
            currentPerson,
            principal,
            privilege,
            PRINCIPAL_COMPANY,
            recordPath,
            null,
            null,
            null,
            true,
            false,
            false
        );
    }

    private boolean checkOrg(OrganizationDef principal, PrivilegeDirection direction) {
        return checkOrg(principal, direction, RECORD_PATH);
    }

    private boolean checkOrg(OrganizationDef principal, PrivilegeDirection direction, String recordPath) {
        PrivilegeDef privilege = new PrivilegeDef("probe", "document")
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, direction, false);
        return privilegeChecker.checkOrganizationPrivilege(
            currentPerson,
            principal,
            privilege,
            null,
            null,
            PRINCIPAL_ORG,
            recordPath,
            null,
            false,
            true,
            false
        );
    }
}
