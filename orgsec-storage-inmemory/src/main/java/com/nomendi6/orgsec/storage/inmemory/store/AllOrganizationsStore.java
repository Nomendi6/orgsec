package com.nomendi6.orgsec.storage.inmemory.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.model.OrganizationDef;

/**
 * Store class that holds all organization data in memory.
 * Separated from loading logic for better separation of concerns.
 */
@Component
public class AllOrganizationsStore {

    private volatile Map<Long, OrganizationDef> organizationMap;

    public AllOrganizationsStore() {
        this.organizationMap = new ConcurrentHashMap<>();
    }

    /**
     * Get the organization map
     * @return Map with organization ID as key and OrganizationDef as value
     */
    public Map<Long, OrganizationDef> getOrganizationMap() {
        return organizationMap;
    }

    /**
     * Replace the entire organization map
     * @param organizationMap new organization map
     */
    public void setOrganizationMap(Map<Long, OrganizationDef> organizationMap) {
        this.organizationMap = new ConcurrentHashMap<>(organizationMap);
    }

    /**
     * Get organization by ID
     * @param organizationId organization ID
     * @return OrganizationDef or null if not found
     */
    public OrganizationDef getOrganization(Long organizationId) {
        if (organizationId == null) {
            return null;
        }
        return organizationMap.get(organizationId);
    }

    /**
     * Add or update organization
     * @param organizationId organization ID
     * @param organization organization definition
     */
    public void putOrganization(Long organizationId, OrganizationDef organization) {
        organizationMap.put(organizationId, organization);
    }

    /**
     * Remove organization
     * @param organizationId organization ID
     */
    public void removeOrganization(Long organizationId) {
        organizationMap.remove(organizationId);
    }

    /**
     * Get number of organizations
     * @return size of organization map
     */
    public int size() {
        return organizationMap.size();
    }

    /**
     * Clear all organizations
     */
    public void clear() {
        organizationMap.clear();
    }
}
