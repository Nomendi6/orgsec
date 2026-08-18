package com.nomendi6.orgsec.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrivilegeCheckerHierarchyUpIdsTest {

    @Mock
    private BusinessRoleConfiguration businessRoleConfiguration;

    private PrivilegeChecker checker;
    private final PersonData person = new PersonData(1L, "Alice");

    @BeforeEach
    void setUp() {
        when(businessRoleConfiguration.hierarchyUpUsesIds()).thenReturn(true);
        checker = new PrivilegeChecker(businessRoleConfiguration);
    }

    @Test
    void orgUpAllowsSelfAndAncestorsAndDeniesSiblings() {
        OrganizationDef principal = principal(15L, List.of(1L, 10L, 15L), 1L, List.of(1L));

        assertThat(checkOrg(principal, 15L)).isTrue();
        assertThat(checkOrg(principal, 10L)).isTrue();
        assertThat(checkOrg(principal, 1L)).isTrue();
        assertThat(checkOrg(principal, 11L)).isFalse();
    }

    @Test
    void companyUpUsesCompanyLineageNotOrgWalk() {
        OrganizationDef principal = principal(15L, List.of(1L, 10L, 15L), 1L, List.of(1L));

        assertThat(checkCompany(principal, 1L)).isTrue();
        assertThat(checkCompany(principal, 10L)).isFalse();
        assertThat(checkCompany(principal, 15L)).isFalse();
    }

    @Test
    void missingLineageDeniesEvenWhenRecordPathWouldMatch() {
        OrganizationDef principal = principal(15L, null, 1L, null);
        principal.parentPath = "|1|10|15|";

        assertThat(checkOrg(principal, 1L)).isFalse();
    }

    @Test
    void staleRecordPathIsIgnoredWhenIdsGrant() {
        OrganizationDef principal = principal(15L, List.of(1L, 10L, 15L), 1L, List.of(1L));
        principal.parentPath = "|9|";

        assertThat(checkOrg(principal, 10L)).isTrue();
    }

    private boolean checkOrg(OrganizationDef principal, long recordOrgId) {
        return checker.checkOrganizationPrivilege(
            person,
            principal,
            privilege(PrivilegeDirection.NONE, PrivilegeDirection.HIERARCHY_UP),
            null,
            "|stale|",
            recordOrgId,
            "|stale|",
            null,
            false,
            true,
            false
        );
    }

    private boolean checkCompany(OrganizationDef principal, long recordCompanyId) {
        return checker.checkOrganizationPrivilege(
            person,
            principal,
            privilege(PrivilegeDirection.HIERARCHY_UP, PrivilegeDirection.NONE),
            recordCompanyId,
            "|stale|",
            null,
            null,
            null,
            true,
            false,
            false
        );
    }

    private static OrganizationDef principal(
        long orgId,
        List<Long> orgLineage,
        long companyId,
        List<Long> companyLineage
    ) {
        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = orgId;
        organization.companyId = companyId;
        organization.orgLineageIds = orgLineage;
        organization.companyLineageIds = companyLineage;
        return organization;
    }

    private static PrivilegeDef privilege(PrivilegeDirection company, PrivilegeDirection org) {
        return new PrivilegeDef("document_probe", "document")
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(company, org, false);
    }
}
