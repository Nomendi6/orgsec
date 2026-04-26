package com.nomendi6.orgsec.storage.inmemory.store;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.api.PrivilegeRegistry;
import com.nomendi6.orgsec.model.PrivilegeDef;

/**
 * Store class that holds all privilege data in memory.
 * Implements PrivilegeRegistry to work with string-based privilege identifiers
 * instead of application-specific enums.
 */
@Component
public class AllPrivilegesStore implements PrivilegeRegistry {

    private Map<String, PrivilegeDef> privilegesMap;

    public AllPrivilegesStore() {
        this.privilegesMap = new HashMap<>();
    }

    /**
     * Get all privileges
     * @return Map with String identifier as key and PrivilegeDef as value
     */
    public Map<String, PrivilegeDef> getPrivilegesMap() {
        return privilegesMap;
    }

    /**
     * Replace the entire privileges map
     * @param privilegesMap new privileges map
     */
    public void setPrivilegesMap(Map<String, PrivilegeDef> privilegesMap) {
        this.privilegesMap = privilegesMap;
    }

    // PrivilegeRegistry implementation methods

    @Override
    public void registerPrivilege(String identifier, PrivilegeDef definition) {
        privilegesMap.put(identifier, definition);
    }

    @Override
    public PrivilegeDef getPrivilege(String identifier) {
        return privilegesMap.get(identifier);
    }

    @Override
    public Set<String> getAllPrivilegeIdentifiers() {
        return privilegesMap.keySet();
    }

    @Override
    public void registerBulk(Map<String, PrivilegeDef> privileges) {
        privilegesMap.putAll(privileges);
    }

    @Override
    public boolean hasPrivilege(String identifier) {
        return privilegesMap.containsKey(identifier);
    }

    @Override
    public void clear() {
        privilegesMap.clear();
    }

    // Legacy support methods (to be deprecated)

    /**
     * Add or update privilege using string identifier
     * @param identifier String identifier
     * @param privilegeDef privilege definition
     */
    public void putPrivilege(String identifier, PrivilegeDef privilegeDef) {
        registerPrivilege(identifier, privilegeDef);
    }

    /**
     * Remove privilege
     * @param identifier String identifier
     */
    public void removePrivilege(String identifier) {
        privilegesMap.remove(identifier);
    }

    /**
     * Get number of privileges
     * @return size of privileges map
     */
    public int size() {
        return privilegesMap.size();
    }
}
