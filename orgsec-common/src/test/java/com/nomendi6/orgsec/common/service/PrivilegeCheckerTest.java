package com.nomendi6.orgsec.common.service;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrivilegeCheckerTest {

    @Mock
    private BusinessRoleConfiguration businessRoleConfiguration;

    private PrivilegeChecker privilegeChecker;

    @BeforeEach
    void setUp() {
        privilegeChecker = new PrivilegeChecker(businessRoleConfiguration);
    }

    @Nested
    class GetResourcePrivilegesTests {

        @Test
        void shouldReturnNullForNullResourceDef() {
            assertThat(privilegeChecker.getResourcePrivileges(null, PrivilegeOperation.READ)).isNull();
        }

        @Test
        void shouldReturnReadPrivilege() {
            // Given
            ResourceDef resourceDef = createResourceDef();
            PrivilegeDef readPrivilege = createPrivilege(PrivilegeOperation.READ);
            resourceDef.setAggregatedReadPrivilege(readPrivilege);

            // When
            PrivilegeDef result = privilegeChecker.getResourcePrivileges(resourceDef, PrivilegeOperation.READ);

            // Then
            assertThat(result).isEqualTo(readPrivilege);
        }

        @Test
        void shouldReturnWritePrivilege() {
            // Given
            ResourceDef resourceDef = createResourceDef();
            PrivilegeDef writePrivilege = createPrivilege(PrivilegeOperation.WRITE);
            resourceDef.setAggregatedWritePrivilege(writePrivilege);

            // When
            PrivilegeDef result = privilegeChecker.getResourcePrivileges(resourceDef, PrivilegeOperation.WRITE);

            // Then
            assertThat(result).isEqualTo(writePrivilege);
        }

        @Test
        void shouldReturnExecutePrivilege() {
            // Given
            ResourceDef resourceDef = createResourceDef();
            PrivilegeDef executePrivilege = createPrivilege(PrivilegeOperation.EXECUTE);
            resourceDef.setAggregatedExecutePrivilege(executePrivilege);

            // When
            PrivilegeDef result = privilegeChecker.getResourcePrivileges(resourceDef, PrivilegeOperation.EXECUTE);

            // Then
            assertThat(result).isEqualTo(executePrivilege);
        }

        @Test
        void shouldReturnNullForNoneOperation() {
            // Given
            ResourceDef resourceDef = createResourceDef();

            // When
            PrivilegeDef result = privilegeChecker.getResourcePrivileges(resourceDef, PrivilegeOperation.NONE);

            // Then
            assertThat(result).isNull();
        }
    }

    @Nested
    class HasRequiredOperationTests {

        @Test
        void shouldReturnFalseForNullPrivilege() {
            assertThat(privilegeChecker.hasRequiredOperation(null, PrivilegeOperation.READ)).isFalse();
        }

        @Test
        void shouldAllowWriteWhenPrivilegeIsWrite() {
            // Given
            PrivilegeDef privilege = createPrivilege(PrivilegeOperation.WRITE);

            // When/Then
            assertThat(privilegeChecker.hasRequiredOperation(privilege, PrivilegeOperation.WRITE)).isTrue();
        }

        @Test
        void shouldAllowReadWhenPrivilegeIsWrite() {
            // Given - WRITE includes READ
            PrivilegeDef privilege = createPrivilege(PrivilegeOperation.WRITE);

            // When/Then
            assertThat(privilegeChecker.hasRequiredOperation(privilege, PrivilegeOperation.READ)).isTrue();
        }

        @Test
        void shouldAllowReadWhenPrivilegeIsRead() {
            // Given
            PrivilegeDef privilege = createPrivilege(PrivilegeOperation.READ);

            // When/Then
            assertThat(privilegeChecker.hasRequiredOperation(privilege, PrivilegeOperation.READ)).isTrue();
        }

        @Test
        void shouldDenyWriteWhenPrivilegeIsOnlyRead() {
            // Given
            PrivilegeDef privilege = createPrivilege(PrivilegeOperation.READ);

            // When/Then
            assertThat(privilegeChecker.hasRequiredOperation(privilege, PrivilegeOperation.WRITE)).isFalse();
        }

        @Test
        void shouldDenyReadWhenPrivilegeIsNone() {
            // Given
            PrivilegeDef privilege = createPrivilege(PrivilegeOperation.NONE);

            // When/Then
            assertThat(privilegeChecker.hasRequiredOperation(privilege, PrivilegeOperation.READ)).isFalse();
        }
    }

    @Nested
    class CheckOrganizationPrivilegeTests {

        private PersonData currentPerson;
        private OrganizationDef organizationDef;

        @BeforeEach
        void setUp() {
            currentPerson = createPersonData(1L);
            organizationDef = createOrganizationDef(100L, "/1/100/", 10L, "/1/10/");
        }

        @Test
        void shouldAllowExactCompanyMatch() {
            // Given
            PrivilegeDef privilege = createPrivilegeWithCompany(PrivilegeDirection.EXACT);

            // When
            boolean result = privilegeChecker.checkOrganizationPrivilege(
                    currentPerson, organizationDef, privilege,
                    10L, "/1/10/",     // company match
                    null, null,        // no org check
                    null,              // no person check
                    true, false, false
            );

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void shouldDenyExactCompanyMismatch() {
            // Given
            PrivilegeDef privilege = createPrivilegeWithCompany(PrivilegeDirection.EXACT);

            // When
            boolean result = privilegeChecker.checkOrganizationPrivilege(
                    currentPerson, organizationDef, privilege,
                    999L, "/1/999/",   // wrong company
                    null, null,
                    null,
                    true, false, false
            );

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void shouldAllowExactOrgMatch() {
            // Given
            PrivilegeDef privilege = createPrivilegeWithOrg(PrivilegeDirection.EXACT);

            // When
            boolean result = privilegeChecker.checkOrganizationPrivilege(
                    currentPerson, organizationDef, privilege,
                    null, null,        // no company check
                    100L, "/1/100/",   // org match
                    null,
                    false, true, false
            );

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void shouldAllowPersonMatch() {
            // Given
            PrivilegeDef privilege = createPrivilegeWithPerson();

            // When
            boolean result = privilegeChecker.checkOrganizationPrivilege(
                    currentPerson, organizationDef, privilege,
                    null, null,
                    null, null,
                    1L,                // person match
                    false, false, true
            );

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void shouldDenyPersonMismatch() {
            // Given
            PrivilegeDef privilege = createPrivilegeWithPerson();

            // When
            boolean result = privilegeChecker.checkOrganizationPrivilege(
                    currentPerson, organizationDef, privilege,
                    null, null,
                    null, null,
                    999L,              // wrong person
                    false, false, true
            );

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void shouldReturnFalseWhenNoChecksEnabled() {
            // Given
            PrivilegeDef privilege = createPrivilege(PrivilegeOperation.READ);

            // When
            boolean result = privilegeChecker.checkOrganizationPrivilege(
                    currentPerson, organizationDef, privilege,
                    null, null,
                    null, null,
                    null,
                    false, false, false
            );

            // Then
            assertThat(result).isFalse();
        }

        @Test
        void shouldAllowHierarchyDownCompany() {
            // Given
            PrivilegeDef privilege = createPrivilegeWithCompany(PrivilegeDirection.HIERARCHY_DOWN);

            // When - business role company path starts with org company parent path
            boolean result = privilegeChecker.checkOrganizationPrivilege(
                    currentPerson, organizationDef, privilege,
                    10L, "/1/10/20/",  // child in hierarchy
                    null, null,
                    null,
                    true, false, false
            );

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void shouldAllowHierarchyDownOrg() {
            // Given
            PrivilegeDef privilege = createPrivilegeWithOrg(PrivilegeDirection.HIERARCHY_DOWN);

            // When
            boolean result = privilegeChecker.checkOrganizationPrivilege(
                    currentPerson, organizationDef, privilege,
                    null, null,
                    100L, "/1/100/200/",  // child org in hierarchy
                    null,
                    false, true, false
            );

            // Then
            assertThat(result).isTrue();
        }
    }

    // ========== HELPER METHODS ==========

    private ResourceDef createResourceDef() {
        return new ResourceDef("test-resource");
    }

    private PrivilegeDef createPrivilege(PrivilegeOperation operation) {
        PrivilegeDef privilege = new PrivilegeDef("TEST", "resource");
        privilege.operation = operation;
        privilege.company = PrivilegeDirection.NONE;
        privilege.org = PrivilegeDirection.NONE;
        privilege.person = false;
        return privilege;
    }

    private PrivilegeDef createPrivilegeWithCompany(PrivilegeDirection companyDirection) {
        PrivilegeDef privilege = createPrivilege(PrivilegeOperation.READ);
        privilege.company = companyDirection;
        return privilege;
    }

    private PrivilegeDef createPrivilegeWithOrg(PrivilegeDirection orgDirection) {
        PrivilegeDef privilege = createPrivilege(PrivilegeOperation.READ);
        privilege.org = orgDirection;
        return privilege;
    }

    private PrivilegeDef createPrivilegeWithPerson() {
        PrivilegeDef privilege = createPrivilege(PrivilegeOperation.READ);
        privilege.person = true;
        return privilege;
    }

    private PersonData createPersonData(Long id) {
        return new PersonData() {
            @Override
            public Long getId() {
                return id;
            }
        };
    }

    private OrganizationDef createOrganizationDef(Long orgId, String parentPath, Long companyId, String companyPath) {
        OrganizationDef org = new OrganizationDef();
        org.organizationId = orgId;
        org.parentPath = parentPath;
        org.companyId = companyId;
        org.companyParentPath = companyPath;
        return org;
    }
}
