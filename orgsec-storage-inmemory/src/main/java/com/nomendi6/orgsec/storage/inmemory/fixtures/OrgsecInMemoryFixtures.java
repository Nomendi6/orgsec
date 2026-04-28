package com.nomendi6.orgsec.storage.inmemory.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.common.service.BusinessRoleConfiguration;
import com.nomendi6.orgsec.helper.PathSanitizer;
import com.nomendi6.orgsec.helper.PrivilegeSecurityHelper;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.StorageSnapshot;
import com.nomendi6.orgsec.storage.inmemory.InMemorySecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPrivilegesStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;

/**
 * Programmatic fixture API for populating {@code orgsec-storage-inmemory}.
 * Intended for tests, examples, and demos. Production data should flow through
 * {@code SecurityQueryProvider}.
 */
@Component
public class OrgsecInMemoryFixtures {

    private final AllPersonsStore personsStore;
    private final AllOrganizationsStore organizationsStore;
    private final AllRolesStore rolesStore;
    private final AllPrivilegesStore privilegesStore;
    private final InMemorySecurityDataStorage storage;
    @SuppressWarnings("unused")
    private final BusinessRoleConfiguration businessRoles;

    /**
     * Creates a fixture API backed by the in-memory stores and storage instance.
     *
     * @param personsStore store that receives person definitions
     * @param organizationsStore store that receives organization definitions
     * @param rolesStore store that receives role definitions
     * @param privilegesStore store that receives privilege definitions
     * @param storage storage instance that is marked ready after fixture application
     * @param businessRoles configured business roles, kept for compatibility with application configuration
     */
    public OrgsecInMemoryFixtures(
        AllPersonsStore personsStore,
        AllOrganizationsStore organizationsStore,
        AllRolesStore rolesStore,
        AllPrivilegesStore privilegesStore,
        InMemorySecurityDataStorage storage,
        BusinessRoleConfiguration businessRoles
    ) {
        this.personsStore = personsStore;
        this.organizationsStore = organizationsStore;
        this.rolesStore = rolesStore;
        this.privilegesStore = privilegesStore;
        this.storage = storage;
        this.businessRoles = businessRoles;
    }

    /**
     * Starts a new fixture definition that is applied atomically with {@link FixtureBuilder#apply()}.
     *
     * @return a new fixture builder
     */
    public FixtureBuilder load() {
        return new FixtureBuilder(this);
    }

    /**
     * Removes all persons, organizations, roles, and privileges from the in-memory stores.
     */
    public void clear() {
        personsStore.clear();
        organizationsStore.clear();
        rolesStore.clear();
        privilegesStore.clear();
    }

    /**
     * Fluent builder for an in-memory security fixture.
     */
    public static class FixtureBuilder {

        private final OrgsecInMemoryFixtures fixtures;
        private final Map<String, PrivilegeDef> privileges = new LinkedHashMap<>();
        private final Map<Long, OrganizationDraft> organizations = new LinkedHashMap<>();
        private final Map<String, RoleDraft> roles = new LinkedHashMap<>();
        private final Map<Long, PersonDraft> persons = new LinkedHashMap<>();
        private final AtomicLong roleIds = new AtomicLong(1L);

        private OrganizationDraft currentOrganization;
        private RoleDraft currentRole;
        private PersonDraft currentPerson;
        private MembershipDraft currentMembership;

        private FixtureBuilder(OrgsecInMemoryFixtures fixtures) {
            this.fixtures = fixtures;
        }

        /**
         * Registers a privilege identifier using {@link PrivilegeLoader#createPrivilegeDefinition(String)}.
         *
         * @param identifier privilege identifier such as {@code DOCUMENT_ORGHD_R}
         * @return this builder
         * @throws IllegalArgumentException when the identifier is malformed
         */
        public FixtureBuilder privilege(String identifier) {
            privileges.put(identifier, PrivilegeLoader.createPrivilegeDefinition(identifier));
            return this;
        }

        /**
         * Registers a company organization with a root path based on its id.
         *
         * @param id company organization id
         * @param name company name
         * @return this builder
         */
        public FixtureBuilder company(long id, String name) {
            return organization(id, name, PathSanitizer.buildPath(null, Long.toString(id))).company(id);
        }

        /**
         * Assigns the current organization to a company.
         *
         * @param companyId company organization id already registered in this builder
         * @return this builder
         * @throws IllegalStateException when no current organization exists or the company is missing at apply time
         */
        public FixtureBuilder company(long companyId) {
            requireCurrentOrganization("company(long)");
            currentOrganization.companyId = companyId;
            return this;
        }

        /**
         * Registers an organization with an explicit path.
         *
         * @param id organization id
         * @param name organization name
         * @param pathId complete organization path in {@code |1|10|} form
         * @return this builder
         * @throws com.nomendi6.orgsec.exceptions.OrgsecSecurityException when the path is invalid
         */
        public FixtureBuilder organization(long id, String name, String pathId) {
            OrganizationDraft draft = new OrganizationDraft(id, name, PathSanitizer.validatePath(pathId));
            draft.parentPath = parentPath(pathId);
            organizations.put(id, draft);
            currentOrganization = draft;
            currentRole = null;
            currentPerson = null;
            currentMembership = null;
            return this;
        }

        /**
         * Registers an organization under a previously registered parent and derives its path.
         *
         * @param id organization id
         * @param name organization name
         * @param parentOrgId parent organization id
         * @return this builder
         * @throws IllegalStateException when the parent organization is missing at apply time
         */
        public FixtureBuilder organizationUnder(long id, String name, long parentOrgId) {
            OrganizationDraft parent = organizations.get(parentOrgId);
            String path = parent != null ? PathSanitizer.buildPath(parent.pathId, Long.toString(id)) : PathSanitizer.buildPath(null, Long.toString(id));
            OrganizationDraft draft = new OrganizationDraft(id, name, path);
            draft.parentId = parentOrgId;
            draft.parentPath = parent != null ? parent.pathId : null;
            draft.companyId = parent != null ? parent.companyId : null;
            organizations.put(id, draft);
            currentOrganization = draft;
            currentRole = null;
            currentPerson = null;
            currentMembership = null;
            return this;
        }

        /**
         * Registers a position role. The role name is matched case-insensitively by {@link #withRole(String)}.
         *
         * @param name role name or code
         * @return this builder
         */
        public FixtureBuilder role(String name) {
            RoleDraft draft = new RoleDraft(roleIds.getAndIncrement(), normalizedRoleName(name));
            roles.put(normalizedRoleName(name), draft);
            currentRole = draft;
            currentOrganization = null;
            currentPerson = null;
            currentMembership = null;
            return this;
        }

        /**
         * Adds a privilege identifier to the current role.
         *
         * @param privilegeId registered privilege identifier
         * @return this builder
         * @throws IllegalStateException when no current role exists or the privilege is missing at apply time
         */
        public FixtureBuilder grants(String privilegeId) {
            requireCurrentRole("grants(String)");
            currentRole.privilegeIds.add(privilegeId);
            return this;
        }

        /**
         * Adds a business role tag to the current role.
         *
         * @param businessRole business role name such as {@code owner}
         * @return this builder
         * @throws IllegalStateException when no current role exists
         */
        public FixtureBuilder asBusinessRole(String businessRole) {
            requireCurrentRole("asBusinessRole(String)");
            currentRole.businessRoles.add(businessRole);
            return this;
        }

        /**
         * Adds another business role tag to the current role.
         *
         * @param businessRole business role name such as {@code executor}
         * @return this builder
         * @throws IllegalStateException when no current role exists
         */
        public FixtureBuilder andBusinessRole(String businessRole) {
            return asBusinessRole(businessRole);
        }

        /**
         * Registers a person.
         *
         * @param id person id
         * @param name person name
         * @return this builder
         */
        public FixtureBuilder person(long id, String name) {
            PersonDraft draft = new PersonDraft(id, name);
            persons.put(id, draft);
            currentPerson = draft;
            currentMembership = null;
            currentOrganization = null;
            currentRole = null;
            return this;
        }

        /**
         * Sets the default company of the current person.
         *
         * @param companyId registered company organization id
         * @return this builder
         * @throws IllegalStateException when no current person exists or the company is missing at apply time
         */
        public FixtureBuilder defaultCompany(long companyId) {
            requireCurrentPerson("defaultCompany(long)");
            currentPerson.defaultCompanyId = companyId;
            return this;
        }

        /**
         * Sets the default organization unit of the current person.
         *
         * @param orgId registered organization id
         * @return this builder
         * @throws IllegalStateException when no current person exists or the organization is missing at apply time
         */
        public FixtureBuilder defaultOrgunit(long orgId) {
            requireCurrentPerson("defaultOrgunit(long)");
            currentPerson.defaultOrgunitId = orgId;
            return this;
        }

        /**
         * Adds an organization membership to the current person.
         *
         * @param orgId registered organization id
         * @return this builder
         * @throws IllegalStateException when no current person exists or the organization is missing at apply time
         */
        public FixtureBuilder memberOf(long orgId) {
            requireCurrentPerson("memberOf(long)");
            MembershipDraft draft = new MembershipDraft(orgId);
            currentPerson.memberships.add(draft);
            currentMembership = draft;
            return this;
        }

        /**
         * Adds a position role to the current membership.
         *
         * @param roleName registered role name or code
         * @return this builder
         * @throws IllegalStateException when no current membership exists or the role is missing at apply time
         */
        public FixtureBuilder withRole(String roleName) {
            if (currentMembership == null) {
                throw new IllegalStateException("withRole(String) requires a preceding memberOf(long)");
            }
            currentMembership.roleNames.add(normalizedRoleName(roleName));
            return this;
        }

        /**
         * Validates and writes the fixture to the in-memory stores.
         *
         * @throws IllegalStateException when any person, organization, role, or privilege reference is inconsistent
         */
        public void apply() {
            validate();

            Map<String, PrivilegeDef> privilegeDefs = new HashMap<>(privileges);
            Map<Long, RoleDef> positionRoles = buildRoles(privilegeDefs);
            Map<Long, OrganizationDef> organizationDefs = buildOrganizations(positionRoles);
            Map<Long, PersonDef> personDefs = buildPersons(organizationDefs, positionRoles);

            fixtures.storage.restoreSnapshot(new StorageSnapshot(personDefs, organizationDefs, Map.of(), positionRoles, privilegeDefs));
        }

        private void validate() {
            for (OrganizationDraft organization : organizations.values()) {
                if (organization.parentId != null && !organizations.containsKey(organization.parentId)) {
                    throw new IllegalStateException(
                        "Organization " + organization.id + " references missing parent organization " + organization.parentId
                    );
                }
                if (organization.companyId != null && !organizations.containsKey(organization.companyId)) {
                    throw new IllegalStateException(
                        "Organization " + organization.id + " references missing company " + organization.companyId
                    );
                }
            }

            for (RoleDraft role : roles.values()) {
                for (String privilegeId : role.privilegeIds) {
                    if (!privileges.containsKey(privilegeId)) {
                        throw new IllegalStateException("Role " + role.name + " grants missing privilege " + privilegeId);
                    }
                }
            }

            for (PersonDraft person : persons.values()) {
                if (person.defaultCompanyId != null && !organizations.containsKey(person.defaultCompanyId)) {
                    throw new IllegalStateException(
                        "Person " + person.id + " references missing default company " + person.defaultCompanyId
                    );
                }
                if (person.defaultOrgunitId != null && !organizations.containsKey(person.defaultOrgunitId)) {
                    throw new IllegalStateException(
                        "Person " + person.id + " references missing default organization " + person.defaultOrgunitId
                    );
                }
                for (MembershipDraft membership : person.memberships) {
                    if (!organizations.containsKey(membership.orgId)) {
                        throw new IllegalStateException(
                            "Person " + person.id + " references missing membership organization " + membership.orgId
                        );
                    }
                    for (String roleName : membership.roleNames) {
                        if (!roles.containsKey(roleName)) {
                            throw new IllegalStateException("Person " + person.id + " references missing role " + roleName);
                        }
                    }
                }
            }
        }

        private Map<Long, RoleDef> buildRoles(Map<String, PrivilegeDef> privilegeDefs) {
            Map<Long, RoleDef> result = new LinkedHashMap<>();
            for (RoleDraft draft : roles.values()) {
                RoleDef role = new RoleDef(draft.id, draft.name);
                role.addSecurityPrivilegeSet(draft.privilegeIds);
                draft.businessRoles.forEach(role::addBusinessRole);
                for (String privilegeId : draft.privilegeIds) {
                    role.addPrivilegeDef(privilegeDefs.get(privilegeId));
                }
                result.put(role.roleId, role);
            }
            return result;
        }

        private Map<Long, OrganizationDef> buildOrganizations(Map<Long, RoleDef> positionRoles) {
            Map<Long, OrganizationDef> result = new LinkedHashMap<>();
            for (OrganizationDraft draft : organizations.values()) {
                OrganizationDef organization = toOrganizationDef(draft);
                result.put(organization.organizationId, organization);
            }
            return result;
        }

        private Map<Long, PersonDef> buildPersons(Map<Long, OrganizationDef> organizationDefs, Map<Long, RoleDef> positionRoles) {
            Map<String, RoleDef> rolesByName = new HashMap<>();
            positionRoles.values().forEach(role -> rolesByName.put(role.name, role));

            Map<Long, PersonDef> result = new LinkedHashMap<>();
            for (PersonDraft draft : persons.values()) {
                PersonDef person = new PersonDef(draft.id, draft.name)
                    .setDefaultCompanyId(draft.defaultCompanyId)
                    .setDefaultOrgunitId(draft.defaultOrgunitId);

                for (MembershipDraft membership : draft.memberships) {
                    OrganizationDef organization = OrganizationDef.copyFrom(organizationDefs.get(membership.orgId));
                    for (String roleName : membership.roleNames) {
                        organization.addPositionRole(rolesByName.get(roleName));
                    }
                    organization.positionRolesSet.forEach(role ->
                        PrivilegeSecurityHelper.buildBusinessRoleResourceMap(organization.businessRolesMap, role)
                    );
                    organization.organizationRolesSet.forEach(role ->
                        PrivilegeSecurityHelper.buildBusinessRoleResourceMap(organization.businessRolesMap, role)
                    );
                    person.organizationsMap.put(organization.organizationId, organization);
                }

                result.put(person.personId, person);
            }
            return result;
        }

        private OrganizationDef toOrganizationDef(OrganizationDraft draft) {
            OrganizationDef organization = new OrganizationDef(
                draft.name,
                draft.id,
                null,
                draft.pathId,
                draft.parentPath,
                draft.companyId,
                companyPath(draft)
            );
            return organization;
        }

        private String companyPath(OrganizationDraft draft) {
            if (draft.companyId == null) {
                return null;
            }
            OrganizationDraft company = organizations.get(draft.companyId);
            return company != null ? company.pathId : null;
        }

        private String parentPath(String path) {
            String sanitized = PathSanitizer.validatePath(path);
            String withoutTrailing = sanitized.substring(0, sanitized.length() - 1);
            int previousSeparator = withoutTrailing.lastIndexOf('|');
            if (previousSeparator <= 0) {
                return null;
            }
            return sanitized.substring(0, previousSeparator + 1);
        }

        private String normalizedRoleName(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Role name cannot be null or blank");
            }
            return name.toLowerCase();
        }

        private void requireCurrentOrganization(String method) {
            if (currentOrganization == null) {
                throw new IllegalStateException(method + " requires a preceding organization(...) or organizationUnder(...)");
            }
        }

        private void requireCurrentRole(String method) {
            if (currentRole == null) {
                throw new IllegalStateException(method + " requires a preceding role(String)");
            }
        }

        private void requireCurrentPerson(String method) {
            if (currentPerson == null) {
                throw new IllegalStateException(method + " requires a preceding person(long, String)");
            }
        }
    }

    private static class OrganizationDraft {

        private final long id;
        private final String name;
        private final String pathId;
        private Long parentId;
        private String parentPath;
        private Long companyId;

        private OrganizationDraft(long id, String name, String pathId) {
            this.id = id;
            this.name = name;
            this.pathId = pathId;
        }
    }

    private static class RoleDraft {

        private final long id;
        private final String name;
        private final Set<String> privilegeIds = new HashSet<>();
        private final Set<String> businessRoles = new HashSet<>();

        private RoleDraft(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static class PersonDraft {

        private final long id;
        private final String name;
        private Long defaultCompanyId;
        private Long defaultOrgunitId;
        private final List<MembershipDraft> memberships = new ArrayList<>();

        private PersonDraft(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static class MembershipDraft {

        private final long orgId;
        private final List<String> roleNames = new ArrayList<>();

        private MembershipDraft(long orgId) {
            this.orgId = orgId;
        }
    }
}
