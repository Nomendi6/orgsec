package com.nomendi6.orgsec.storage.inmemory.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.nomendi6.orgsec.common.service.BusinessRoleConfiguration;
import com.nomendi6.orgsec.common.service.PrivilegeChecker;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.model.RoleDef;
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
import jakarta.persistence.Tuple;

class OrgsecInMemoryFixturesTest {

    private AllPersonsStore personsStore;
    private AllOrganizationsStore organizationsStore;
    private AllRolesStore rolesStore;
    private AllPrivilegesStore privilegesStore;
    private InMemorySecurityDataStorage storage;
    private OrgsecInMemoryFixtures fixtures;

    @BeforeEach
    void setUp() {
        personsStore = new AllPersonsStore();
        organizationsStore = new AllOrganizationsStore();
        rolesStore = new AllRolesStore();
        privilegesStore = new AllPrivilegesStore();

        storage = new InMemorySecurityDataStorage(
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
    }

    @Test
    void shouldPopulateCanonicalFixtureAndAllowRead() {
        loadCanonicalFixture(fixtures);

        PersonDef alice = storage.getPerson(1L);
        BusinessRoleDef owner = alice.organizationsMap.get(22L).businessRolesMap.get("owner");
        ResourceDef document = owner.resourcesMap.get("DOCUMENT");

        PrivilegeChecker checker = new PrivilegeChecker(mock(BusinessRoleConfiguration.class));
        PrivilegeDef readPrivilege = checker.getResourcePrivileges(document, PrivilegeOperation.READ);

        assertThat(storage.isReady()).isTrue();
        assertThat(readPrivilege).isNotNull();
        assertThat(checker.hasRequiredOperation(readPrivilege, PrivilegeOperation.READ)).isTrue();
    }

    @Test
    void shouldBuildAggregatedBusinessRoleResources() {
        loadCanonicalFixture(fixtures);

        PersonDef alice = personsStore.getPerson(1L);

        assertThat(alice.organizationsMap.get(22L).businessRolesMap.get("owner").resourcesMap.get("DOCUMENT").getAggregatedReadPrivilege())
            .isNotNull();
    }

    @Test
    void shouldFailFastWhenMembershipOrganizationIsMissing() {
        assertThatThrownBy(() ->
            fixtures
                .load()
                .privilege("DOCUMENT_ORGHD_R")
                .company(1L, "Acme")
                .role("SHOP_MANAGER")
                .grants("DOCUMENT_ORGHD_R")
                .asBusinessRole("owner")
                .person(1L, "Alice")
                .memberOf(22L)
                .withRole("SHOP_MANAGER")
                .apply()
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing membership organization 22");
    }

    @Test
    void shouldFailFastWhenRolePrivilegeIsMissing() {
        assertThatThrownBy(() -> fixtures.load().role("SHOP_MANAGER").grants("DOCUMENT_ORGHD_R").apply())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("grants missing privilege DOCUMENT_ORGHD_R");
    }

    @Test
    void shouldFailFastWhenParentOrganizationIsMissing() {
        assertThatThrownBy(() -> fixtures.load().organizationUnder(22L, "Shop-22", 10L).apply())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing parent organization 10");
    }

    @Test
    void shouldProduceGraphEquivalentToLoaderPath() {
        loadCanonicalFixture(fixtures);

        AllPersonsStore loaderPersons = new AllPersonsStore();
        AllOrganizationsStore loaderOrganizations = new AllOrganizationsStore();
        AllRolesStore loaderRoles = new AllRolesStore();
        AllPrivilegesStore loaderPrivileges = new AllPrivilegesStore();
        loaderPrivileges.registerPrivilege("DOCUMENT_ORGHD_R", PrivilegeLoader.createPrivilegeDefinition("DOCUMENT_ORGHD_R"));

        RoleLoader roleLoader = new RoleLoader(loaderPrivileges, loaderRoles);
        roleLoader.loadRolesFromQueryResults(
            List.of(),
            List.of(),
            List.of(roleTuple(1L, "SHOP_MANAGER", true)),
            List.of(privilegeTuple(1L, "DOCUMENT_ORGHD_R"))
        );

        OrganizationLoader organizationLoader = new OrganizationLoader(loaderRoles, loaderOrganizations);
        organizationLoader.loadOrganizationsFromQueryResults(
            List.of(
                partyTuple(1L, "Acme", "|1|", null, 1L, "|1|"),
                partyTuple(10L, "EU Region", "|1|10|", "|1|", 1L, "|1|"),
                partyTuple(22L, "Shop-22", "|1|10|22|", "|1|10|", 1L, "|1|")
            ),
            List.of()
        );

        PersonLoader personLoader = new PersonLoader(loaderRoles, loaderPersons);
        personLoader.loadPersonsFromQueryResults(
            List.of(personTuple(1L, "Alice", 1L, 22L)),
            List.of(personPartyTuple(1L, 22L, "Shop-22", null, "|1|10|22|", "|1|10|", 1L, "|1|")),
            List.of(),
            List.of(personPositionRoleTuple(1L, 22L, 1L))
        );

        assertThat(personsStore.getPersonsMap()).isEqualTo(loaderPersons.getPersonsMap());
        assertThat(organizationsStore.getOrganizationMap()).isEqualTo(loaderOrganizations.getOrganizationMap());
        assertThat(rolesStore.getPositionRolesMap()).isEqualTo(loaderRoles.getPositionRolesMap());
        assertThat(privilegesStore.getPrivilegesMap()).isEqualTo(loaderPrivileges.getPrivilegesMap());

        RoleDef fixtureRole = rolesStore.getPositionRole(1L);
        RoleDef loaderRole = loaderRoles.getPositionRole(1L);
        assertThat(fixtureRole.securityPrivilegeSet).isEqualTo(loaderRole.securityPrivilegeSet);
        assertThat(fixtureRole.resourcesMap).isEqualTo(loaderRole.resourcesMap);
    }

    private void loadCanonicalFixture(OrgsecInMemoryFixtures fixtures) {
        fixtures
            .load()
            .privilege("DOCUMENT_ORGHD_R")
            .company(1L, "Acme")
            .organization(10L, "EU Region", "|1|10|")
            .company(1L)
            .organization(22L, "Shop-22", "|1|10|22|")
            .company(1L)
            .role("SHOP_MANAGER")
            .grants("DOCUMENT_ORGHD_R")
            .asBusinessRole("owner")
            .person(1L, "Alice")
            .defaultCompany(1L)
            .defaultOrgunit(22L)
            .memberOf(22L)
            .withRole("SHOP_MANAGER")
            .apply();
    }

    private Tuple roleTuple(Long id, String code, boolean owner) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("id", Long.class)).thenReturn(id);
        when(tuple.get("code", String.class)).thenReturn(code);
        when(tuple.get("owner", Boolean.class)).thenReturn(owner);
        when(tuple.get("contractor", Boolean.class)).thenReturn(false);
        when(tuple.get("customer", Boolean.class)).thenReturn(false);
        when(tuple.get("partner", Boolean.class)).thenReturn(false);
        return tuple;
    }

    private Tuple privilegeTuple(Long roleId, String privilege) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("roleId", Long.class)).thenReturn(roleId);
        when(tuple.get("privilege", String.class)).thenReturn(privilege);
        return tuple;
    }

    private Tuple partyTuple(Long id, String name, String pathId, String parentPath, Long companyId, String companyParentPath) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("id", Long.class)).thenReturn(id);
        when(tuple.get("name", String.class)).thenReturn(name);
        when(tuple.get("pathId", String.class)).thenReturn(pathId);
        when(tuple.get("parentPath", String.class)).thenReturn(parentPath);
        when(tuple.get("companyId", Long.class)).thenReturn(companyId);
        when(tuple.get("companyParentPath", String.class)).thenReturn(companyParentPath);
        return tuple;
    }

    private Tuple personTuple(Long id, String name, Long defaultCompanyId, Long defaultOrgunitId) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("id", Long.class)).thenReturn(id);
        when(tuple.get("name", String.class)).thenReturn(name);
        when(tuple.get("dfltCompanyId", Long.class)).thenReturn(defaultCompanyId);
        when(tuple.get("dfltOrgunitId", Long.class)).thenReturn(defaultOrgunitId);
        when(tuple.get("relatedUserId", String.class)).thenReturn(null);
        when(tuple.get("login", String.class)).thenReturn(null);
        return tuple;
    }

    private Tuple personPartyTuple(
        Long personId,
        Long orgunitId,
        String orgunitName,
        Long positionId,
        String pathId,
        String parentPath,
        Long companyId,
        String companyParentPath
    ) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("empId", Long.class)).thenReturn(personId);
        when(tuple.get("orgunitId", Long.class)).thenReturn(orgunitId);
        when(tuple.get("orgunitName", String.class)).thenReturn(orgunitName);
        when(tuple.get("positionId", Long.class)).thenReturn(positionId);
        when(tuple.get("pathId", String.class)).thenReturn(pathId);
        when(tuple.get("parentPath", String.class)).thenReturn(parentPath);
        when(tuple.get("companyId", Long.class)).thenReturn(companyId);
        when(tuple.get("companyParentPath", String.class)).thenReturn(companyParentPath);
        return tuple;
    }

    private Tuple personPositionRoleTuple(Long personId, Long orgunitId, Long roleId) {
        Tuple tuple = mock(Tuple.class);
        when(tuple.get("empId", Long.class)).thenReturn(personId);
        when(tuple.get("orgunitId", Long.class)).thenReturn(orgunitId);
        when(tuple.get("roleId", Long.class)).thenReturn(roleId);
        return tuple;
    }
}
