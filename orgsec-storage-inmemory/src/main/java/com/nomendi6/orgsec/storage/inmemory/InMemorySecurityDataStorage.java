package com.nomendi6.orgsec.storage.inmemory;

import jakarta.persistence.Tuple;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.storage.inmemory.loader.OrganizationLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import com.nomendi6.orgsec.storage.inmemory.loader.RoleLoader;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllPrivilegesStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;

import com.nomendi6.orgsec.api.PrivilegeDefinitionProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.StorageSnapshot;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * In-memory storage implementation that wraps existing Store classes.
 * This maintains backward compatibility while providing the new storage abstraction.
 * All data is cached in memory using existing loader/store pattern.
 *
 * NOTE: This component is NOT marked as @Primary. Primary designation is handled
 * dynamically in StorageConfiguration based on which storage modules are enabled.
 * When JWT or Redis modules are enabled, they become @Primary and this serves as delegate.
 */
@Component("inMemorySecurityDataStorage")
public class InMemorySecurityDataStorage implements SecurityDataStorage {

    private static final Logger log = LoggerFactory.getLogger(InMemorySecurityDataStorage.class);

    // Existing store classes - these hold the cached data
    private final AllPersonsStore personsStore;
    private final AllOrganizationsStore organizationsStore;
    private final AllRolesStore rolesStore;
    private final AllPrivilegesStore privilegesStore;

    // Existing loader classes - these process data
    private final PersonLoader personLoader;
    private final OrganizationLoader organizationLoader;
    private final RoleLoader roleLoader;
    private final PrivilegeLoader privilegeLoader;

    // Query provider - executes database queries
    private final SecurityQueryProvider queryProvider;

    // Optional privilege definition provider - for auto-initialization of privileges
    // This is injected via setter to allow optional dependency
    private PrivilegeDefinitionProvider privilegeDefinitionProvider;

    // Thread synchronization for cache operations
    private final ReadWriteLock cacheLock = new ReentrantReadWriteLock();

    private volatile boolean isInitialized = false;

    public InMemorySecurityDataStorage(
        AllPersonsStore personsStore,
        AllOrganizationsStore organizationsStore,
        AllRolesStore rolesStore,
        AllPrivilegesStore privilegesStore,
        PersonLoader personLoader,
        OrganizationLoader organizationLoader,
        RoleLoader roleLoader,
        PrivilegeLoader privilegeLoader,
        SecurityQueryProvider queryProvider
    ) {
        this.personsStore = personsStore;
        this.organizationsStore = organizationsStore;
        this.rolesStore = rolesStore;
        this.privilegesStore = privilegesStore;
        this.personLoader = personLoader;
        this.organizationLoader = organizationLoader;
        this.roleLoader = roleLoader;
        this.privilegeLoader = privilegeLoader;
        this.queryProvider = queryProvider;
    }

    /**
     * Optional setter for PrivilegeDefinitionProvider.
     * When provided, privileges will be auto-initialized during storage initialization
     * if they haven't been initialized yet.
     *
     * This solves the initialization order problem where storage.initialize() may be called
     * before ApplicationPrivilegeInitializer.@PostConstruct runs.
     *
     * @param privilegeDefinitionProvider the provider for privilege definitions
     */
    @Autowired(required = false)
    public void setPrivilegeDefinitionProvider(PrivilegeDefinitionProvider privilegeDefinitionProvider) {
        this.privilegeDefinitionProvider = privilegeDefinitionProvider;
        log.debug("PrivilegeDefinitionProvider injected: {}",
            privilegeDefinitionProvider != null ? privilegeDefinitionProvider.getClass().getSimpleName() : "null");
    }

    // ========== GET OPERATIONS ==========

    @Override
    public PersonDef getPerson(Long personId) {
        cacheLock.readLock().lock();
        try {
            if (!isReady()) {
                log.warn("Storage not ready, returning null for person: {}", personId);
                return null;
            }
            return personsStore.getPerson(personId);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    @Override
    public OrganizationDef getOrganization(Long orgId) {
        cacheLock.readLock().lock();
        try {
            if (!isReady()) {
                log.warn("Storage not ready, returning null for organization: {}", orgId);
                return null;
            }
            return organizationsStore.getOrganization(orgId);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    @Override
    public RoleDef getPartyRole(Long roleId) {
        cacheLock.readLock().lock();
        try {
            if (!isReady()) {
                log.warn("Storage not ready, returning null for party role: {}", roleId);
                return null;
            }
            return rolesStore.getOrganizationRole(roleId);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    @Override
    public RoleDef getPositionRole(Long roleId) {
        cacheLock.readLock().lock();
        try {
            if (!isReady()) {
                log.warn("Storage not ready, returning null for position role: {}", roleId);
                return null;
            }
            return rolesStore.getPositionRole(roleId);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    @Override
    public PrivilegeDef getPrivilege(String privilegeIdentifier) {
        cacheLock.readLock().lock();
        try {
            if (!isReady()) {
                log.warn("Storage not ready, returning null for privilege: {}", privilegeIdentifier);
                return null;
            }
            return privilegesStore.getPrivilege(privilegeIdentifier);
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    // ========== UPDATE OPERATIONS ==========

    @Override
    public void updatePerson(Long personId, PersonDef person) {
        cacheLock.writeLock().lock();
        try {
            if (!isReady()) {
                log.warn("Storage not ready, cannot update person: {}", personId);
                return;
            }
            personsStore.putPerson(personId, person);
            log.debug("Updated person in memory: {}", personId);
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    @Override
    public void updateOrganization(Long orgId, OrganizationDef organization) {
        cacheLock.writeLock().lock();
        try {
            if (!isReady()) {
                log.warn("Storage not ready, cannot update organization: {}", orgId);
                return;
            }
            organizationsStore.putOrganization(orgId, organization);
            log.debug("Updated organization in memory: {}", orgId);
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    @Override
    public void updateRole(Long roleId, RoleDef role) {
        cacheLock.writeLock().lock();
        try {
            if (!isReady()) {
                log.warn("Storage not ready, cannot update role: {}", roleId);
                return;
            }
            // Note: AllRolesStore doesn't have a direct update method,
            // so we'd need to reload from database
            log.debug("Role update requested for: {}, triggering reload", roleId);
            // Call refresh without additional locking since we're already in write lock
            refreshInternal();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    // ========== LIFECYCLE OPERATIONS ==========

    @Override
    public void initialize() {
        cacheLock.writeLock().lock();
        try {
            log.info("Initializing in-memory security data storage...");

            // Auto-initialize privileges if not already loaded and provider is available
            // This solves initialization order issues where initialize() is called before
            // ApplicationPrivilegeInitializer.@PostConstruct runs
            ensurePrivilegesInitialized();

            // Load roles using query provider
            List<Tuple> partyRoles = queryProvider.loadAllPartyRoles();
            List<Tuple> partyRolePrivileges = queryProvider.loadAllPartyRolePrivilegesAsStrings();
            List<Tuple> positionRoles = queryProvider.loadAllPositionRoles();
            List<Tuple> positionRolePrivileges = queryProvider.loadAllPositionRolePrivilegesAsStrings();
            roleLoader.loadRolesFromQueryResults(partyRoles, partyRolePrivileges, positionRoles, positionRolePrivileges);
            log.debug("Roles loaded from database");

            // Load organizations using query provider
            List<Tuple> parties = queryProvider.loadAllParties();
            List<Tuple> partyAssignedRoles = queryProvider.loadAllPartyAssignedRoles();
            organizationLoader.loadOrganizationsFromQueryResults(parties, partyAssignedRoles);
            log.debug("Organizations loaded from database");

            // Load persons using query provider
            List<Tuple> persons = queryProvider.loadAllPersons();
            List<Tuple> personParties = queryProvider.loadAllPersonParties();
            List<Tuple> personPartyRoles = queryProvider.loadAllPersonPartyRoles();
            List<Tuple> personPositionRoles = queryProvider.loadAllPersonPositionRoles();
            personLoader.loadPersonsFromQueryResults(persons, personParties, personPartyRoles, personPositionRoles);
            log.debug("Persons loaded from database");

            isInitialized = true;

            log.info(
                "In-memory security data storage initialized successfully. " + "Loaded {} persons, {} organizations, {} privileges",
                personsStore.size(),
                organizationsStore.size(),
                privilegesStore.size()
            );
        } catch (Exception e) {
            isInitialized = false;
            log.error("Failed to initialize in-memory security data storage", e);
            throw new RuntimeException("Storage initialization failed", e);
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    @Override
    public void refresh() {
        cacheLock.writeLock().lock();
        try {
            refreshInternal();
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Internal refresh method without locking - used when already holding write lock
     */
    private void refreshInternal() {
        log.info("Refreshing in-memory security data storage...");

        // Clear existing data
        personsStore.clear();
        organizationsStore.clear();
        rolesStore.clear();
        privilegesStore.clear();

        isInitialized = false;

        // Reload everything - call initialize without additional locking
        initializeInternal();
    }

    /**
     * Internal initialize method without locking - used when already holding write lock
     */
    private void initializeInternal() {
        try {
            log.info("Initializing in-memory security data storage (internal)...");

            // Auto-initialize privileges if not already loaded and provider is available
            ensurePrivilegesInitialized();

            // Load roles using query provider
            List<Tuple> partyRoles = queryProvider.loadAllPartyRoles();
            List<Tuple> partyRolePrivileges = queryProvider.loadAllPartyRolePrivilegesAsStrings();
            List<Tuple> positionRoles = queryProvider.loadAllPositionRoles();
            List<Tuple> positionRolePrivileges = queryProvider.loadAllPositionRolePrivilegesAsStrings();
            roleLoader.loadRolesFromQueryResults(partyRoles, partyRolePrivileges, positionRoles, positionRolePrivileges);
            log.debug("Roles loaded from database");

            // Load organizations using query provider
            List<Tuple> parties = queryProvider.loadAllParties();
            List<Tuple> partyAssignedRoles = queryProvider.loadAllPartyAssignedRoles();
            organizationLoader.loadOrganizationsFromQueryResults(parties, partyAssignedRoles);
            log.debug("Organizations loaded from database");

            // Load persons using query provider
            List<Tuple> persons = queryProvider.loadAllPersons();
            List<Tuple> personParties = queryProvider.loadAllPersonParties();
            List<Tuple> personPartyRoles = queryProvider.loadAllPersonPartyRoles();
            List<Tuple> personPositionRoles = queryProvider.loadAllPersonPositionRoles();
            personLoader.loadPersonsFromQueryResults(persons, personParties, personPartyRoles, personPositionRoles);
            log.debug("Persons loaded from database");

            isInitialized = true;

            log.info(
                "In-memory security data storage initialized successfully. " + "Loaded {} persons, {} organizations, {} privileges",
                personsStore.size(),
                organizationsStore.size(),
                privilegesStore.size()
            );
        } catch (Exception e) {
            isInitialized = false;
            log.error("Failed to initialize in-memory security data storage", e);
            throw new RuntimeException("Storage initialization failed", e);
        }
    }

    @Override
    public boolean isReady() {
        cacheLock.readLock().lock();
        try {
            return isInitialized && personsStore != null && organizationsStore != null && rolesStore != null && privilegesStore != null;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    @Override
    public String getProviderType() {
        return "in-memory";
    }

    /**
     * Ensures privileges are initialized before other data is loaded.
     * This method is called internally during initialize() and will:
     * 1. Check if privileges are already loaded
     * 2. If not, and PrivilegeDefinitionProvider is available, auto-load them
     * 3. Log appropriate messages based on the outcome
     *
     * This solves the initialization order problem where initialize() may be called
     * before ApplicationPrivilegeInitializer.@PostConstruct runs.
     */
    private void ensurePrivilegesInitialized() {
        if (privilegesStore.size() > 0) {
            log.debug("Privileges already initialized ({} privileges loaded)", privilegesStore.size());
            return;
        }

        if (privilegeDefinitionProvider != null) {
            log.info("Privileges not initialized, auto-loading from PrivilegeDefinitionProvider...");
            try {
                privilegeLoader.initializePrivileges(privilegeDefinitionProvider);
                log.info("Auto-initialized {} privileges from provider", privilegesStore.size());
            } catch (Exception e) {
                log.error("Failed to auto-initialize privileges from provider", e);
                throw new RuntimeException("Privilege auto-initialization failed", e);
            }
        } else {
            log.warn("Privileges not initialized and no PrivilegeDefinitionProvider available. " +
                "Ensure ApplicationPrivilegeInitializer runs before storage initialization, " +
                "or provide a PrivilegeDefinitionProvider bean.");
        }
    }

    /**
     * Manually trigger privilege initialization from the provider.
     * This can be used by applications that need explicit control over initialization order.
     *
     * @return true if privileges were initialized, false if already initialized or no provider
     */
    public boolean initializePrivilegesIfNeeded() {
        cacheLock.writeLock().lock();
        try {
            if (privilegesStore.size() > 0) {
                log.debug("Privileges already initialized, skipping");
                return false;
            }

            if (privilegeDefinitionProvider == null) {
                log.warn("Cannot initialize privileges: no PrivilegeDefinitionProvider available");
                return false;
            }

            log.info("Manually initializing privileges from provider...");
            privilegeLoader.initializePrivileges(privilegeDefinitionProvider);
            log.info("Manually initialized {} privileges", privilegesStore.size());
            return true;
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    // ========== NOTIFICATION OPERATIONS ==========

    /**
     * Notify that party role data has changed - reload from database
     */
    @Override
    public void notifyPartyRoleChanged(Long roleId) {
        log.debug("InMemory storage notified: party role {} changed - syncing from database", roleId);
        syncPartyRole(roleId);
        refresh(); // Refresh to ensure all related data is updated
    }

    /**
     * Notify that position role data has changed - reload from database
     */
    @Override
    public void notifyPositionRoleChanged(Long roleId) {
        log.debug("InMemory storage notified: position role {} changed - syncing from database", roleId);
        syncPositionRole(roleId);
        refresh(); // Refresh to ensure all related data is updated
    }

    /**
     * Notify that organization data has changed - reload from database
     */
    @Override
    public void notifyOrganizationChanged(Long orgId) {
        log.debug("InMemory storage notified: organization {} changed - syncing from database", orgId);
        syncOrganization(orgId);
        refresh(); // Refresh to ensure all related data is updated
    }

    /**
     * Notify that person data has changed - reload from database
     */
    @Override
    public void notifyPersonChanged(Long personId) {
        log.debug("InMemory storage notified: person {} changed - syncing from database", personId);
        syncPerson(personId);
    }

    // ========== ADDITIONAL UTILITY METHODS ==========

    /**
     * Get current storage statistics for monitoring
     */
    public String getStorageStats() {
        cacheLock.readLock().lock();
        try {
            if (!isReady()) {
                return "Storage not ready";
            }

            return String.format(
                "In-memory storage stats: persons=%d, organizations=%d, privileges=%d",
                personsStore.size(),
                organizationsStore.size(),
                privilegesStore.size()
            );
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    /**
     * Sync specific person by ID (used by SecurityEventPublisher)
     */
    public void syncPerson(Long personId) {
        cacheLock.writeLock().lock();
        try {
            if (isReady()) {
                List<Tuple> person = queryProvider.loadPersonById(personId);
                List<Tuple> personParties = queryProvider.loadPersonPartiesByPersonId(personId);
                List<Tuple> personPartyRoles = queryProvider.loadPersonPartyRolesByPersonId(personId);
                List<Tuple> personPositionRoles = queryProvider.loadPersonPositionRolesByPersonId(personId);
                personLoader.syncPerson(personId, person, personParties, personPartyRoles, personPositionRoles);
                log.debug("Synced person: {}", personId);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Sync specific organization by ID (used by SecurityEventPublisher)
     */
    public void syncOrganization(Long orgId) {
        cacheLock.writeLock().lock();
        try {
            if (isReady()) {
                List<Tuple> party = queryProvider.loadPartyById(orgId);
                List<Tuple> assignedRoles = queryProvider.loadPartyAssignedRolesByPartyId(orgId);
                organizationLoader.syncParty(orgId, party, assignedRoles);
                log.debug("Synced organization: {}", orgId);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Sync specific party role by ID (used by SecurityEventPublisher)
     */
    public void syncPartyRole(Long roleId) {
        cacheLock.writeLock().lock();
        try {
            if (isReady()) {
                List<Tuple> partyRoles = queryProvider.loadPartyRoleById(roleId);
                List<Tuple> privileges = queryProvider.loadPartyRolePrivilegesByRoleIdAsStrings(roleId);
                roleLoader.syncPartyRole(roleId, partyRoles, privileges);
                log.debug("Synced party role: {}", roleId);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    /**
     * Sync specific position role by ID (used by SecurityEventPublisher)
     */
    public void syncPositionRole(Long roleId) {
        cacheLock.writeLock().lock();
        try {
            if (isReady()) {
                List<Tuple> positionRoles = queryProvider.loadPositionRoleById(roleId);
                List<Tuple> privileges = queryProvider.loadPositionRolePrivilegesByRoleIdAsStrings(roleId);
                roleLoader.syncPositionRole(roleId, positionRoles, privileges);
                log.debug("Synced position role: {}", roleId);
            }
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    // ========== SNAPSHOT OPERATIONS ==========

    @Override
    public boolean supportsSnapshot() {
        return true;
    }

    @Override
    public StorageSnapshot createSnapshot() {
        cacheLock.readLock().lock();
        try {
            if (!isReady()) {
                throw new IllegalStateException("Cannot create snapshot: storage not ready");
            }

            log.debug("Creating storage snapshot...");

            // Deep copy all data
            Map<Long, PersonDef> personsCopy = deepCopyPersons(personsStore.getPersonsMap());
            Map<Long, OrganizationDef> organizationsCopy = deepCopyOrganizations(organizationsStore.getOrganizationMap());
            Map<Long, RoleDef> partyRolesCopy = deepCopyRoles(rolesStore.getOrganizationRolesMap());
            Map<Long, RoleDef> positionRolesCopy = deepCopyRoles(rolesStore.getPositionRolesMap());
            Map<String, PrivilegeDef> privilegesCopy = deepCopyPrivileges(privilegesStore.getPrivilegesMap());

            StorageSnapshot snapshot = new StorageSnapshot(
                personsCopy,
                organizationsCopy,
                partyRolesCopy,
                positionRolesCopy,
                privilegesCopy
            );

            log.info("Storage snapshot created: {}", snapshot);
            return snapshot;
        } finally {
            cacheLock.readLock().unlock();
        }
    }

    @Override
    public void restoreSnapshot(StorageSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot cannot be null");
        }

        cacheLock.writeLock().lock();
        try {
            log.debug("Restoring storage from snapshot: {}", snapshot);

            // Clear current data
            personsStore.clear();
            organizationsStore.clear();
            rolesStore.clear();
            privilegesStore.clear();

            // Restore from snapshot (deep copy again to avoid sharing references)
            personsStore.setPersonsMap(deepCopyPersons(snapshot.getPersons()));
            organizationsStore.setOrganizationMap(deepCopyOrganizations(snapshot.getOrganizations()));
            rolesStore.setOrganizationRolesMap(deepCopyRoles(snapshot.getPartyRoles()));
            rolesStore.setPositionRolesMap(deepCopyRoles(snapshot.getPositionRoles()));
            privilegesStore.setPrivilegesMap(deepCopyPrivileges(snapshot.getPrivileges()));

            isInitialized = true;

            log.info(
                "Storage restored from snapshot. Loaded {} persons, {} organizations, {} privileges",
                personsStore.size(),
                organizationsStore.size(),
                privilegesStore.size()
            );
        } finally {
            cacheLock.writeLock().unlock();
        }
    }

    // ========== DEEP COPY HELPER METHODS ==========

    private Map<Long, PersonDef> deepCopyPersons(Map<Long, PersonDef> source) {
        Map<Long, PersonDef> copy = new HashMap<>();
        for (Map.Entry<Long, PersonDef> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyPerson(entry.getValue()));
        }
        return copy;
    }

    private PersonDef deepCopyPerson(PersonDef source) {
        PersonDef copy = new PersonDef(source.personId, source.personName);
        copy.setDefaultCompanyId(source.defaultCompanyId);
        copy.setDefaultOrgunitId(source.defaultOrgunitId);
        copy.setRelatedUserId(source.relatedUserId);
        copy.setRelatedUserLogin(source.relatedUserLogin);

        // Deep copy organizations map
        for (Map.Entry<Long, OrganizationDef> entry : source.organizationsMap.entrySet()) {
            copy.organizationsMap.put(entry.getKey(), deepCopyOrganization(entry.getValue()));
        }
        return copy;
    }

    private Map<Long, OrganizationDef> deepCopyOrganizations(Map<Long, OrganizationDef> source) {
        Map<Long, OrganizationDef> copy = new HashMap<>();
        for (Map.Entry<Long, OrganizationDef> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyOrganization(entry.getValue()));
        }
        return copy;
    }

    private OrganizationDef deepCopyOrganization(OrganizationDef source) {
        OrganizationDef copy = new OrganizationDef();
        copy.organizationId = source.organizationId;
        copy.organizationName = source.organizationName;
        copy.positionId = source.positionId;
        copy.pathId = source.pathId;
        copy.parentPath = source.parentPath;
        copy.companyId = source.companyId;
        copy.companyParentPath = source.companyParentPath;

        // Deep copy role sets
        for (RoleDef role : source.positionRolesSet) {
            copy.positionRolesSet.add(deepCopyRole(role));
        }
        for (RoleDef role : source.organizationRolesSet) {
            copy.organizationRolesSet.add(deepCopyRole(role));
        }

        // Deep copy business roles map
        for (Map.Entry<String, BusinessRoleDef> entry : source.businessRolesMap.entrySet()) {
            copy.businessRolesMap.put(entry.getKey(), deepCopyBusinessRole(entry.getValue()));
        }

        return copy;
    }

    private Map<Long, RoleDef> deepCopyRoles(Map<Long, RoleDef> source) {
        Map<Long, RoleDef> copy = new HashMap<>();
        for (Map.Entry<Long, RoleDef> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyRole(entry.getValue()));
        }
        return copy;
    }

    private RoleDef deepCopyRole(RoleDef source) {
        RoleDef copy = new RoleDef(source.roleId, source.name);
        copy.securityPrivilegeSet = new HashSet<>(source.securityPrivilegeSet);
        copy.businessRoles = new HashSet<>(source.businessRoles);

        // Deep copy resources map
        for (Map.Entry<String, ResourceDef> entry : source.resourcesMap.entrySet()) {
            copy.resourcesMap.put(entry.getKey(), deepCopyResource(entry.getValue()));
        }

        return copy;
    }

    private BusinessRoleDef deepCopyBusinessRole(BusinessRoleDef source) {
        BusinessRoleDef copy = new BusinessRoleDef(source.businessRoleName);
        copy.filter = source.filter;
        copy.allowAll = source.allowAll;

        // Deep copy resources map
        for (Map.Entry<String, ResourceDef> entry : source.resourcesMap.entrySet()) {
            copy.resourcesMap.put(entry.getKey(), deepCopyResource(entry.getValue()));
        }

        return copy;
    }

    private ResourceDef deepCopyResource(ResourceDef source) {
        ResourceDef copy = new ResourceDef(source.getResourceName());

        // Deep copy privileges list
        for (PrivilegeDef privilege : source.getPrivilegesList()) {
            copy.getPrivilegesList().add(deepCopyPrivilege(privilege));
        }

        // Copy aggregated privileges
        if (source.getAggregatedWritePrivilege() != null) {
            copy.setAggregatedWritePrivilege(deepCopyPrivilege(source.getAggregatedWritePrivilege()));
        }
        if (source.getAggregatedReadPrivilege() != null) {
            copy.setAggregatedReadPrivilege(deepCopyPrivilege(source.getAggregatedReadPrivilege()));
        }
        if (source.getAggregatedExecutePrivilege() != null) {
            copy.setAggregatedExecutePrivilege(deepCopyPrivilege(source.getAggregatedExecutePrivilege()));
        }

        return copy;
    }

    private Map<String, PrivilegeDef> deepCopyPrivileges(Map<String, PrivilegeDef> source) {
        Map<String, PrivilegeDef> copy = new HashMap<>();
        for (Map.Entry<String, PrivilegeDef> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyPrivilege(entry.getValue()));
        }
        return copy;
    }

    private PrivilegeDef deepCopyPrivilege(PrivilegeDef source) {
        if (source == null) {
            return null;
        }
        PrivilegeDef copy = new PrivilegeDef(source.name, source.resourceName);
        copy.operation = source.operation;
        copy.all = source.all;
        copy.company = source.company;
        copy.org = source.org;
        copy.person = source.person;
        return copy;
    }
}
