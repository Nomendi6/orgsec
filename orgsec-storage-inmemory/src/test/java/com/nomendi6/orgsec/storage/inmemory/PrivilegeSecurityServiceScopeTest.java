package com.nomendi6.orgsec.storage.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nomendi6.orgsec.common.service.BusinessRoleConfiguration;
import com.nomendi6.orgsec.common.service.BusinessRoleProvider;
import com.nomendi6.orgsec.common.service.DefaultBusinessRoleProvider;
import com.nomendi6.orgsec.common.service.PrivilegeChecker;
import com.nomendi6.orgsec.common.service.RsqlFilterBuilder;
import com.nomendi6.orgsec.common.service.SecurityEventPublisher;
import com.nomendi6.orgsec.common.store.SecurityDataStore;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.constants.SecurityConstants;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.dto.OrganizationData;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.interfaces.SecurityEnabledDTO;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.provider.PersonDataProvider;
import com.nomendi6.orgsec.provider.SecurityContextProvider;
import com.nomendi6.orgsec.provider.UserDataProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Per-record scope check when one business role holds several privileges on the same resource.
 * <p>
 * A role granted both {@code document_ORGHD_R} (subtree) and {@code document_ORGHU_R} (ancestors)
 * covers a union that no single {@code PrivilegeDirection} can express, so the check has to consider
 * each privilege on its own. Evaluating the aggregate instead denies every record that only one of
 * the two privileges covers.
 */
class PrivilegeSecurityServiceScopeTest {

    private static final String RESOURCE = "document";
    private static final String ROLE = SecurityConstants.BusinessRoles.OWNER;
    private static final String LOGIN = "alice";
    private static final Long PERSON_ID = 1L;
    private static final Long PRINCIPAL_ORG_ID = 10L;
    private static final String PRINCIPAL_PATH = "|A|B|";

    private SecurityDataStorage storage;
    private PrivilegeSecurityService service;

    @BeforeEach
    void setUp() {
        storage = mock(SecurityDataStorage.class);

        PersonDataProvider personDataProvider = mock(PersonDataProvider.class);
        when(personDataProvider.findByRelatedUserLogin(anyString()))
            .thenReturn(Optional.of(new PersonData(PERSON_ID, "Alice")));

        SecurityContextProvider securityContextProvider = mock(SecurityContextProvider.class);
        when(securityContextProvider.getCurrentUserLogin()).thenReturn(Optional.of(LOGIN));

        BusinessRoleProvider roleProvider = new DefaultBusinessRoleProvider();
        BusinessRoleConfiguration roleConfiguration = new BusinessRoleConfiguration(List.of(roleProvider));
        roleConfiguration.initializeBusinessRoles();

        SecurityDataStore dataStore = new SecurityDataStore(storage);
        service = new PrivilegeSecurityService(
            dataStore,
            new PrivilegeChecker(roleConfiguration),
            new RsqlFilterBuilder(dataStore, roleConfiguration),
            mock(SecurityEventPublisher.class),
            personDataProvider,
            mock(UserDataProvider.class),
            securityContextProvider
        );
    }

    @Test
    void shouldAllowDescendantRecordCoveredByTheHierarchyDownPrivilege() {
        givenRoleWithDownAndUpPrivileges();

        boolean allowed = service.checkCurrentUserPrivilegeOnResource(entity(30L, "|A|B|C|"), RESOURCE, PrivilegeOperation.READ);

        assertThat(allowed).isTrue();
    }

    @Test
    void shouldAllowAncestorRecordCoveredByTheHierarchyUpPrivilege() {
        givenRoleWithDownAndUpPrivileges();

        boolean allowed = service.checkCurrentUserPrivilegeOnResource(entity(5L, "|A|"), RESOURCE, PrivilegeOperation.READ);

        assertThat(allowed).isTrue();
    }

    @Test
    void shouldDenySiblingBranchCoveredByNeitherPrivilege() {
        // Subtree plus ancestors is a vertical spine - it must not reach into a sibling branch.
        givenRoleWithDownAndUpPrivileges();

        boolean allowed = service.checkCurrentUserPrivilegeOnResource(entity(40L, "|A|X|"), RESOURCE, PrivilegeOperation.READ);

        assertThat(allowed).isFalse();
    }

    @Test
    void shouldIgnorePrivilegesThatDoNotMatchTheRequestedOperation() {
        givenRoleWithDownAndUpPrivileges();

        boolean allowed = service.checkCurrentUserPrivilegeOnResource(entity(30L, "|A|B|C|"), RESOURCE, PrivilegeOperation.WRITE);

        assertThat(allowed).isFalse();
    }

    // --- company axis ---------------------------------------------------------------------------
    //
    // The company branch mirrors the org branch and the per-privilege loop does not distinguish them,
    // but it is the side a deployment with companyParentPath = null never exercises - so the same
    // three outcomes are pinned here explicitly.

    @Test
    void shouldAllowDescendantRecordCoveredByTheCompanyHierarchyDownPrivilege() {
        givenRoleWithCompanyDownAndUpPrivileges();

        boolean allowed = service.checkCurrentUserPrivilegeOnResource(
            companyEntity(300L, "|A|B|C|"),
            RESOURCE,
            PrivilegeOperation.READ
        );

        assertThat(allowed).isTrue();
    }

    @Test
    void shouldAllowAncestorRecordCoveredByTheCompanyHierarchyUpPrivilege() {
        givenRoleWithCompanyDownAndUpPrivileges();

        boolean allowed = service.checkCurrentUserPrivilegeOnResource(companyEntity(50L, "|A|"), RESOURCE, PrivilegeOperation.READ);

        assertThat(allowed).isTrue();
    }

    @Test
    void shouldDenyCompanySiblingBranchCoveredByNeitherPrivilege() {
        givenRoleWithCompanyDownAndUpPrivileges();

        boolean allowed = service.checkCurrentUserPrivilegeOnResource(companyEntity(400L, "|A|X|"), RESOURCE, PrivilegeOperation.READ);

        assertThat(allowed).isFalse();
    }

    /**
     * A cross-tree suffix collision must not be accepted. Before 1.0.4 the company branch compared
     * with {@code endsWith}, which let an unrelated company whose path merely ended with the
     * principal's path through.
     */
    @Test
    void shouldDenyCompanyCrossTreeSuffixCollision() {
        givenRoleWithCompanyDownAndUpPrivileges();

        boolean allowed = service.checkCurrentUserPrivilegeOnResource(
            companyEntity(500L, "|X|A|B|"),
            RESOURCE,
            PrivilegeOperation.READ
        );

        assertThat(allowed).isFalse();
    }

    private void givenRoleWithCompanyDownAndUpPrivileges() {
        PrivilegeDef down = companyPrivilege(PrivilegeDirection.HIERARCHY_DOWN);
        PrivilegeDef up = companyPrivilege(PrivilegeDirection.HIERARCHY_UP);

        ResourceDef resource = new ResourceDef(RESOURCE);
        resource.getPrivilegesList().add(down);
        resource.getPrivilegesList().add(up);
        resource.setAggregatedReadPrivilege(down.add(up));

        BusinessRoleDef role = new BusinessRoleDef(ROLE);
        role.resourcesMap.put(RESOURCE, resource);

        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = PRINCIPAL_ORG_ID;
        organization.parentPath = PRINCIPAL_PATH;
        organization.companyId = 100L;
        organization.companyParentPath = PRINCIPAL_PATH;
        organization.businessRolesMap.put(ROLE, role);

        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        person.organizationsMap.put(PRINCIPAL_ORG_ID, organization);

        when(storage.getPerson(PERSON_ID)).thenReturn(person);
    }

    private PrivilegeDef companyPrivilege(PrivilegeDirection company) {
        return new PrivilegeDef(RESOURCE + "_probe", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(company, PrivilegeDirection.NONE, false);
    }

    private SecurityEnabledDTO companyEntity(Long companyId, String companyPath) {
        Map<SecurityFieldType, Object> fields = new HashMap<>();
        OrganizationData company = new OrganizationData();
        company.setId(companyId);
        fields.put(SecurityFieldType.COMPANY, company);
        fields.put(SecurityFieldType.COMPANY_PATH, companyPath);

        return new SecurityEnabledDTO() {
            @Override
            public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
                return ROLE.equalsIgnoreCase(businessRole) ? fields.get(fieldType) : null;
            }

            @Override
            public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
                if (ROLE.equalsIgnoreCase(businessRole)) {
                    fields.put(fieldType, value);
                }
            }
        };
    }

    private void givenRoleWithDownAndUpPrivileges() {
        PrivilegeDef down = orgPrivilege(PrivilegeDirection.HIERARCHY_DOWN);
        PrivilegeDef up = orgPrivilege(PrivilegeDirection.HIERARCHY_UP);

        ResourceDef resource = new ResourceDef(RESOURCE);
        resource.getPrivilegesList().add(down);
        resource.getPrivilegesList().add(up);
        resource.setAggregatedReadPrivilege(down.add(up));

        BusinessRoleDef role = new BusinessRoleDef(ROLE);
        role.resourcesMap.put(RESOURCE, resource);

        OrganizationDef organization = new OrganizationDef();
        organization.organizationId = PRINCIPAL_ORG_ID;
        organization.parentPath = PRINCIPAL_PATH;
        organization.businessRolesMap.put(ROLE, role);

        PersonDef person = new PersonDef(PERSON_ID, "Alice");
        person.organizationsMap.put(PRINCIPAL_ORG_ID, organization);

        when(storage.getPerson(PERSON_ID)).thenReturn(person);
    }

    private PrivilegeDef orgPrivilege(PrivilegeDirection org) {
        return new PrivilegeDef(RESOURCE + "_probe", RESOURCE)
            .allowOperation(PrivilegeOperation.READ)
            .allowOrg(PrivilegeDirection.NONE, org, false);
    }

    private SecurityEnabledDTO entity(Long orgId, String orgPath) {
        Map<SecurityFieldType, Object> fields = new HashMap<>();
        OrganizationData org = new OrganizationData();
        org.setId(orgId);
        fields.put(SecurityFieldType.ORG, org);
        fields.put(SecurityFieldType.ORG_PATH, orgPath);

        return new SecurityEnabledDTO() {
            @Override
            public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
                return ROLE.equalsIgnoreCase(businessRole) ? fields.get(fieldType) : null;
            }

            @Override
            public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
                if (ROLE.equalsIgnoreCase(businessRole)) {
                    fields.put(fieldType, value);
                }
            }
        };
    }
}
