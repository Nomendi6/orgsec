package com.nomendi6.orgsec.api;

import java.util.Map;
import com.nomendi6.orgsec.model.PrivilegeDef;

/**
 * Provider interface for supplying privilege definitions to the orgsec library.
 * Applications implement this interface to define their custom privileges.
 */
public interface PrivilegeDefinitionProvider {
    /**
     * Provide all privilege definitions for the application.
     *
     * @return Map of privilege identifier to privilege definition
     */
    Map<String, PrivilegeDef> getPrivilegeDefinitions();

    /**
     * Create a privilege definition for a given identifier.
     *
     * @param identifier The privilege identifier
     * @return The privilege definition, or null if not applicable
     */
    PrivilegeDef createPrivilegeDefinition(String identifier);

    /**
     * Get the default privilege definitions that should be loaded.
     * This can be a subset of all available privileges.
     *
     * @return Map of default privilege definitions
     */
    default Map<String, PrivilegeDef> getDefaultPrivilegeDefinitions() {
        return getPrivilegeDefinitions();
    }
}
