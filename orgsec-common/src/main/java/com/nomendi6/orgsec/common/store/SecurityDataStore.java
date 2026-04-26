package com.nomendi6.orgsec.common.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;

/**
 * Unified data store that provides access to security data through the SecurityDataStorage abstraction.
 * This replaces direct access to individual Store classes and enables pluggable storage strategies.
 *
 * This class serves as a bridge between the existing orgsec services and the new storage abstraction,
 * maintaining API compatibility while allowing different storage backends.
 */
@Component
public class SecurityDataStore {

    private static final Logger log = LoggerFactory.getLogger(SecurityDataStore.class);

    private final SecurityDataStorage storage;

    public SecurityDataStore(SecurityDataStorage storage) {
        this.storage = storage;
        log.info("SecurityDataStore initialized with storage provider: {}", storage.getProviderType());
    }

    // ========== PERSON OPERATIONS ==========

    public PersonDef getPerson(Long personId) {
        if (personId == null) {
            log.debug("Cannot get person: personId is null");
            return null;
        }

        try {
            PersonDef person = storage.getPerson(personId);
            if (person == null) {
                log.debug("Person not found: {}", personId);
            }
            return person;
        } catch (Exception e) {
            log.error("Error getting person: {}", personId, e);
            return null;
        }
    }

    public void putPerson(Long personId, PersonDef person) {
        if (personId == null || person == null) {
            log.warn("Cannot put person: personId={}, person={}", personId, person);
            return;
        }

        try {
            storage.updatePerson(personId, person);
            log.debug("Person updated: {}", personId);
        } catch (UnsupportedOperationException e) {
            log.debug("Storage provider {} does not support person updates", storage.getProviderType());
        } catch (Exception e) {
            log.error("Error updating person: {}", personId, e);
        }
    }

    // ========== ORGANIZATION OPERATIONS ==========

    public OrganizationDef getOrganization(Long orgId) {
        if (orgId == null) {
            log.debug("Cannot get organization: orgId is null");
            return null;
        }

        try {
            OrganizationDef org = storage.getOrganization(orgId);
            if (org == null) {
                log.debug("Organization not found: {}", orgId);
            }
            return org;
        } catch (Exception e) {
            log.error("Error getting organization: {}", orgId, e);
            return null;
        }
    }

    public void putOrganization(Long orgId, OrganizationDef organization) {
        if (orgId == null || organization == null) {
            log.warn("Cannot put organization: orgId={}, organization={}", orgId, organization);
            return;
        }

        try {
            storage.updateOrganization(orgId, organization);
            log.debug("Organization updated: {}", orgId);
        } catch (UnsupportedOperationException e) {
            log.debug("Storage provider {} does not support organization updates", storage.getProviderType());
        } catch (Exception e) {
            log.error("Error updating organization: {}", orgId, e);
        }
    }

    // ========== ROLE OPERATIONS ==========

    public RoleDef getPartyRole(Long roleId) {
        if (roleId == null) {
            log.debug("Cannot get party role: roleId is null");
            return null;
        }

        try {
            RoleDef role = storage.getPartyRole(roleId);
            if (role == null) {
                log.debug("Party role not found: {}", roleId);
            }
            return role;
        } catch (Exception e) {
            log.error("Error getting party role: {}", roleId, e);
            return null;
        }
    }

    public RoleDef getPositionRole(Long roleId) {
        if (roleId == null) {
            log.debug("Cannot get position role: roleId is null");
            return null;
        }

        try {
            RoleDef role = storage.getPositionRole(roleId);
            if (role == null) {
                log.debug("Position role not found: {}", roleId);
            }
            return role;
        } catch (Exception e) {
            log.error("Error getting position role: {}", roleId, e);
            return null;
        }
    }

    public void updateRole(Long roleId, RoleDef role) {
        if (roleId == null || role == null) {
            log.warn("Cannot update role: roleId={}, role={}", roleId, role);
            return;
        }

        try {
            storage.updateRole(roleId, role);
            log.debug("Role updated: {}", roleId);
        } catch (UnsupportedOperationException e) {
            log.debug("Storage provider {} does not support role updates", storage.getProviderType());
        } catch (Exception e) {
            log.error("Error updating role: {}", roleId, e);
        }
    }

    // ========== PRIVILEGE OPERATIONS ==========

    public PrivilegeDef getPrivilege(String privilegeIdentifier) {
        if (privilegeIdentifier == null) {
            log.debug("Cannot get privilege: privilege identifier is null");
            return null;
        }

        try {
            PrivilegeDef privilegeDef = storage.getPrivilege(privilegeIdentifier);
            if (privilegeDef == null) {
                log.debug("Privilege not found: {}", privilegeIdentifier);
            }
            return privilegeDef;
        } catch (Exception e) {
            log.error("Error getting privilege: {}", privilegeIdentifier, e);
            return null;
        }
    }

    // ========== STORAGE MANAGEMENT ==========

    public void initialize() {
        try {
            log.info("Initializing security data store with provider: {}", storage.getProviderType());
            storage.initialize();
            log.info("Security data store initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize security data store", e);
            throw new RuntimeException("Security data store initialization failed", e);
        }
    }

    public void refresh() {
        try {
            log.info("Refreshing security data store with provider: {}", storage.getProviderType());
            storage.refresh();
            log.info("Security data store refreshed successfully");
        } catch (Exception e) {
            log.error("Failed to refresh security data store", e);
            throw new RuntimeException("Security data store refresh failed", e);
        }
    }

    public boolean isReady() {
        return storage.isReady();
    }

    public String getStorageProviderType() {
        return storage.getProviderType();
    }

    public SecurityDataStorage getStorage() {
        return storage;
    }
}
