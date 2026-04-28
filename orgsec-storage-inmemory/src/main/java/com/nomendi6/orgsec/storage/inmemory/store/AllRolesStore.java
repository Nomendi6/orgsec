package com.nomendi6.orgsec.storage.inmemory.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.model.RoleDef;

/**
 * Store class that holds all role data in memory.
 * Separated from loading logic for better separation of concerns.
 */
@Component
public class AllRolesStore {

    private volatile Map<Long, RoleDef> organizationRolesMap;
    private volatile Map<Long, RoleDef> positionRolesMap;

    public AllRolesStore() {
        this.organizationRolesMap = new ConcurrentHashMap<>();
        this.positionRolesMap = new ConcurrentHashMap<>();
    }

    /**
     * Get all organizational roles
     * @return Map with role ID as key and RoleDef as value
     */
    public Map<Long, RoleDef> getOrganizationRolesMap() {
        return organizationRolesMap;
    }

    /**
     * Replace the entire organizational roles map
     * @param organizationRolesMap new organizational roles map
     */
    public void setOrganizationRolesMap(Map<Long, RoleDef> organizationRolesMap) {
        this.organizationRolesMap = new ConcurrentHashMap<>(organizationRolesMap);
    }

    /**
     * Get all positional roles
     * @return Map with role ID as key and RoleDef as value
     */
    public Map<Long, RoleDef> getPositionRolesMap() {
        return positionRolesMap;
    }

    /**
     * Replace the entire positional roles map
     * @param positionRolesMap new positional roles map
     */
    public void setPositionRolesMap(Map<Long, RoleDef> positionRolesMap) {
        this.positionRolesMap = new ConcurrentHashMap<>(positionRolesMap);
    }

    /**
     * Get organizational role by ID
     * @param roleId role ID
     * @return RoleDef or null if not found
     */
    public RoleDef getOrganizationRole(Long roleId) {
        if (roleId == null) {
            return null;
        }
        return organizationRolesMap.get(roleId);
    }

    /**
     * Get positional role by ID
     * @param roleId role ID
     * @return RoleDef or null if not found
     */
    public RoleDef getPositionRole(Long roleId) {
        if (roleId == null) {
            return null;
        }
        return positionRolesMap.get(roleId);
    }

    /**
     * Get organizational role by code
     * @param roleCode role code
     * @return RoleDef or null if not found
     */
    public RoleDef getOrganizationRole(String roleCode) {
        if (roleCode == null) {
            return null;
        }
        for (Map.Entry<Long, RoleDef> entry : organizationRolesMap.entrySet()) {
            RoleDef roleDef = entry.getValue();
            if (roleDef.name.equals(roleCode)) {
                return this.getOrganizationRole(entry.getKey());
            }
        }
        return null;
    }

    /**
     * Add or update organizational role
     * @param roleId role ID
     * @param role role definition
     */
    public void putOrganizationRole(Long roleId, RoleDef role) {
        organizationRolesMap.put(roleId, role);
    }

    /**
     * Add or update positional role
     * @param roleId role ID
     * @param role role definition
     */
    public void putPositionRole(Long roleId, RoleDef role) {
        positionRolesMap.put(roleId, role);
    }

    /**
     * Remove organizational role
     * @param roleId role ID
     */
    public void removeOrganizationRole(Long roleId) {
        organizationRolesMap.remove(roleId);
    }

    /**
     * Remove positional role
     * @param roleId role ID
     */
    public void removePositionRole(Long roleId) {
        positionRolesMap.remove(roleId);
    }

    /**
     * Clear all roles
     */
    public void clear() {
        organizationRolesMap.clear();
        positionRolesMap.clear();
    }
}
