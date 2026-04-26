package com.nomendi6.orgsec.model;

import java.util.HashMap;
import java.util.Map;
import com.nomendi6.orgsec.constants.PrivilegeOperation;

public class BusinessRoleDef {

    // BusinessRoleDef contains aggregated privileges on the business role level.
    // (business roles are owner, executor, supervisor, customer, contractor, ...)
    // Privileges are aggregated for both organization and positions
    // Filter contains the filter that should be applied to getOne a list of
    // resources that satisfies privileges.

    public String businessRoleName;
    public Map<String, ResourceDef> resourcesMap;
    public String filter;
    public boolean allowAll = false;

    public BusinessRoleDef(String businessRoleName) {
        this.businessRoleName = businessRoleName;
        resourcesMap = new HashMap<>();
    }

    public void addResourceDefinition(ResourceDef otherResource) {
        if (resourcesMap.containsKey(otherResource.getResourceName())) {
            ResourceDef resourceDef = resourcesMap.get(otherResource.getResourceName());

            // Add privileges from other resource
            resourceDef.getPrivilegesList().addAll(otherResource.getPrivilegesList());

            // Aggregate write privileges
            if (
                resourceDef.getAggregatedWritePrivilege() == null ||
                resourceDef.getAggregatedWritePrivilege().operation == PrivilegeOperation.NONE
            ) {
                resourceDef.setAggregatedWritePrivilege(otherResource.getAggregatedWritePrivilege());
            } else {
                resourceDef.setAggregatedWritePrivilege(
                    resourceDef.getAggregatedWritePrivilege().add(otherResource.getAggregatedWritePrivilege())
                );
            }

            // Aggregate read privileges
            if (
                resourceDef.getAggregatedReadPrivilege() == null ||
                resourceDef.getAggregatedReadPrivilege().operation == PrivilegeOperation.NONE
            ) {
                resourceDef.setAggregatedReadPrivilege(otherResource.getAggregatedReadPrivilege());
            } else {
                resourceDef.setAggregatedReadPrivilege(
                    resourceDef.getAggregatedReadPrivilege().add(otherResource.getAggregatedReadPrivilege())
                );
            }

            // Aggregate execute privileges
            if (
                resourceDef.getAggregatedExecutePrivilege() == null ||
                resourceDef.getAggregatedExecutePrivilege().operation == PrivilegeOperation.NONE
            ) {
                resourceDef.setAggregatedExecutePrivilege(otherResource.getAggregatedExecutePrivilege());
            } else {
                resourceDef.setAggregatedExecutePrivilege(
                    resourceDef.getAggregatedExecutePrivilege().add(otherResource.getAggregatedExecutePrivilege())
                );
            }
        } else {
            // Create new resource definition
            ResourceDef resourceDef = new ResourceDef(otherResource.getResourceName());

            // Copy privileges list
            resourceDef.setPrivilegesList(otherResource.getPrivilegesList());
            resourceDef.setAggregatedWritePrivilege(otherResource.getAggregatedWritePrivilege());
            resourceDef.setAggregatedReadPrivilege(otherResource.getAggregatedReadPrivilege());
            resourceDef.setAggregatedExecutePrivilege(otherResource.getAggregatedExecutePrivilege());

            resourcesMap.put(otherResource.getResourceName(), resourceDef);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BusinessRoleDef)) return false;

        BusinessRoleDef that = (BusinessRoleDef) o;

        if (allowAll != that.allowAll) return false;
        if (businessRoleName != null ? !businessRoleName.equals(that.businessRoleName) : that.businessRoleName != null) return false;
        if (resourcesMap != null ? !resourcesMap.equals(that.resourcesMap) : that.resourcesMap != null) return false;
        return filter != null ? filter.equals(that.filter) : that.filter == null;
    }

    @Override
    public int hashCode() {
        int result = businessRoleName != null ? businessRoleName.hashCode() : 0;
        result = 31 * result + (resourcesMap != null ? resourcesMap.hashCode() : 0);
        result = 31 * result + (filter != null ? filter.hashCode() : 0);
        result = 31 * result + (allowAll ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return (
            "BusinessRoleDef{" +
            "businessRoleName='" +
            businessRoleName +
            '\'' +
            ", resourcesMap=" +
            resourcesMap +
            ", filter='" +
            filter +
            '\'' +
            ", allowAll=" +
            allowAll +
            '}'
        );
    }
}
