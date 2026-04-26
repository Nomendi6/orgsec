package com.nomendi6.orgsec.storage;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;

/**
 * Interface for storing and retrieving security data.
 * Provides abstraction layer that allows different storage strategies
 * (in-memory, JWT tokens, Redis cache, etc.)
 * Works with string-based privilege identifiers instead of application-specific enums.
 */
public interface SecurityDataStorage {
    // ========== GET OPERATIONS ==========

    /**
     * Get person by ID
     * @param personId person ID
     * @return PersonDef or null if not found
     */
    PersonDef getPerson(Long personId);

    /**
     * Get organization by ID
     * @param orgId organization ID
     * @return OrganizationDef or null if not found
     */
    OrganizationDef getOrganization(Long orgId);

    /**
     * Get party role by ID
     * @param roleId role ID
     * @return RoleDef or null if not found
     */
    RoleDef getPartyRole(Long roleId);

    /**
     * Get position role by ID
     * @param roleId role ID
     * @return RoleDef or null if not found
     */
    RoleDef getPositionRole(Long roleId);

    /**
     * Get privilege definition by string identifier
     * @param privilegeIdentifier privilege string identifier
     * @return PrivilegeDef or null if not found
     */
    PrivilegeDef getPrivilege(String privilegeIdentifier);

    // ========== UPDATE OPERATIONS ==========

    /**
     * Update person data (optional - depends on storage type)
     * @param personId person ID
     * @param person person definition
     */
    default void updatePerson(Long personId, PersonDef person) {
        throw new UnsupportedOperationException("Update not supported by this storage provider");
    }

    /**
     * Update organization data (optional - depends on storage type)
     * @param orgId organization ID
     * @param organization organization definition
     */
    default void updateOrganization(Long orgId, OrganizationDef organization) {
        throw new UnsupportedOperationException("Update not supported by this storage provider");
    }

    /**
     * Update role data (optional - depends on storage type)
     * @param roleId role ID
     * @param role role definition
     */
    default void updateRole(Long roleId, RoleDef role) {
        throw new UnsupportedOperationException("Update not supported by this storage provider");
    }

    // ========== LIFECYCLE OPERATIONS ==========

    /**
     * Initialize the storage provider
     * This is called at application startup or when storage provider is changed
     */
    void initialize();

    /**
     * Refresh/reload data from source
     * This is called when data needs to be reloaded (e.g., after configuration changes)
     */
    void refresh();

    /**
     * Check if storage is ready for use
     * @return true if storage is initialized and ready
     */
    boolean isReady();

    /**
     * Get storage provider type name for logging/monitoring
     * @return storage provider name (e.g., "in-memory", "jwt", "redis")
     */
    default String getProviderType() {
        return this.getClass().getSimpleName();
    }

    // ========== NOTIFICATION OPERATIONS (optional) ==========

    /**
     * Notify storage that party role data has changed.
     * Storage providers can use this to invalidate caches, reload data, publish events, etc.
     * Default implementation is no-op - storage providers can choose to ignore notifications.
     *
     * @param roleId party role ID that changed
     */
    default void notifyPartyRoleChanged(Long roleId) {
        // Default no-op implementation
        // InMemory: will sync data from database
        // Redis: will invalidate cache
        // JWT: will do nothing (stateless)
    }

    /**
     * Notify storage that position role data has changed.
     *
     * @param roleId position role ID that changed
     */
    default void notifyPositionRoleChanged(Long roleId) {
        // Default no-op implementation
    }

    /**
     * Notify storage that organization data has changed.
     *
     * @param orgId organization ID that changed
     */
    default void notifyOrganizationChanged(Long orgId) {
        // Default no-op implementation
    }

    /**
     * Notify storage that person data has changed.
     *
     * @param personId person ID that changed
     */
    default void notifyPersonChanged(Long personId) {
        // Default no-op implementation
    }

    // ========== SNAPSHOT OPERATIONS (optional - for testing) ==========

    /**
     * Create a snapshot of the current storage state.
     * Useful for integration tests to save state before making changes.
     *
     * @return StorageSnapshot containing copies of all data
     * @throws UnsupportedOperationException if storage doesn't support snapshots
     */
    default StorageSnapshot createSnapshot() {
        throw new UnsupportedOperationException("Snapshot not supported by this storage provider: " + getProviderType());
    }

    /**
     * Restore storage state from a previously created snapshot.
     * Useful for integration tests to restore state after test scenarios.
     *
     * @param snapshot the snapshot to restore from
     * @throws UnsupportedOperationException if storage doesn't support snapshots
     * @throws IllegalArgumentException if snapshot is null
     */
    default void restoreSnapshot(StorageSnapshot snapshot) {
        throw new UnsupportedOperationException("Snapshot not supported by this storage provider: " + getProviderType());
    }

    /**
     * Check if this storage provider supports snapshot operations.
     *
     * @return true if createSnapshot() and restoreSnapshot() are supported
     */
    default boolean supportsSnapshot() {
        return false;
    }
}
