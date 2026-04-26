package com.nomendi6.orgsec.api;

import java.util.Map;
import java.util.Set;
import com.nomendi6.orgsec.model.PrivilegeDef;

/**
 * Central registry for managing privilege definitions.
 * This interface allows external applications to register their privileges
 * without the orgsec library depending on application-specific enums.
 */
public interface PrivilegeRegistry {
    /**
     * Register a privilege definition with a string identifier.
     *
     * @param identifier Unique string identifier for the privilege
     * @param definition The privilege definition
     */
    void registerPrivilege(String identifier, PrivilegeDef definition);

    /**
     * Get a privilege definition by its identifier.
     *
     * @param identifier The privilege identifier
     * @return The privilege definition, or null if not found
     */
    PrivilegeDef getPrivilege(String identifier);

    /**
     * Get all registered privilege identifiers.
     *
     * @return Set of all privilege identifiers
     */
    Set<String> getAllPrivilegeIdentifiers();

    /**
     * Register multiple privileges at once.
     *
     * @param privileges Map of identifier to privilege definition
     */
    void registerBulk(Map<String, PrivilegeDef> privileges);

    /**
     * Check if a privilege is registered.
     *
     * @param identifier The privilege identifier
     * @return true if the privilege is registered
     */
    boolean hasPrivilege(String identifier);

    /**
     * Clear all registered privileges.
     * Useful for testing or reinitialization.
     */
    void clear();
}
