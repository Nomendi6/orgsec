package com.nomendi6.orgsec.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.*;
import com.nomendi6.orgsec.constants.PrivilegeOperation;

/**
 * Definition of a role in the security context.
 */
public class RoleDef {

    @NotNull(message = "Role ID is required")
    public Long roleId;

    @Size(max = 255, message = "Role name must not exceed 255 characters")
    public String name;

    public Set<String> securityPrivilegeSet;
    public Map<String, ResourceDef> resourcesMap;
    public Set<String> businessRoles = new HashSet<>();

    public RoleDef() {
        securityPrivilegeSet = new HashSet<>();
        resourcesMap = new HashMap<>();
    }

    public RoleDef(Long roleId, String name) {
        this.roleId = roleId;
        this.name = name;
        securityPrivilegeSet = new HashSet<>();
        resourcesMap = new HashMap<>();
    }

    public RoleDef(Long roleId, String name, Set<String> securityPrivilegeSet) {
        this.roleId = roleId;
        this.name = name;
        this.securityPrivilegeSet = new HashSet<>();
        this.securityPrivilegeSet.addAll(securityPrivilegeSet);
    }

    public RoleDef addSecurityPrivilege(String privilege) {
        this.securityPrivilegeSet.add(privilege);
        return this;
    }

    public RoleDef addSecurityPrivilegeSet(Set<String> securityPrivilegeSet) {
        this.securityPrivilegeSet.addAll(securityPrivilegeSet);
        return this;
    }

    public RoleDef addBusinessRole(String businessRole) {
        if (businessRole != null && !businessRole.trim().isEmpty()) {
            this.businessRoles.add(businessRole);
        }
        return this;
    }

    public RoleDef removeBusinessRole(String businessRole) {
        this.businessRoles.remove(businessRole);
        return this;
    }

    public boolean hasBusinessRole(String businessRole) {
        return this.businessRoles.contains(businessRole);
    }

    public Set<String> getBusinessRoles() {
        return new HashSet<>(this.businessRoles);
    }

    /**
     * Add privilege def to a privilegeList and aggregated privileges (for write, read and execute)
     *
     * @param def the {@link PrivilegeDef} privilege definition.
     */
    public void addPrivilegeDef(PrivilegeDef def) {
        if (def == null) {
            return;
        }
        if (def.resourceName == null) {
            return;
        }

        if (resourcesMap.containsKey(def.resourceName)) {
            ResourceDef resourceDef = resourcesMap.get(def.resourceName);
            resourceDef.getPrivilegesList().add(def);

            // Handle write privileges
            if (def.operation == PrivilegeOperation.WRITE) {
                if (resourceDef.getAggregatedWritePrivilege() == null) {
                    resourceDef.setAggregatedWritePrivilege(def);
                } else {
                    resourceDef.setAggregatedWritePrivilege(resourceDef.getAggregatedWritePrivilege().add(def));
                }
            }

            // Handle read privileges (both READ and WRITE operations grant read access)
            if (def.operation == PrivilegeOperation.READ || def.operation == PrivilegeOperation.WRITE) {
                if (resourceDef.getAggregatedReadPrivilege() == null) {
                    resourceDef.setAggregatedReadPrivilege(def);
                } else {
                    resourceDef.setAggregatedReadPrivilege(resourceDef.getAggregatedReadPrivilege().add(def));
                }
            }

            // Handle execute privileges
            if (def.operation == PrivilegeOperation.EXECUTE) {
                if (resourceDef.getAggregatedExecutePrivilege() == null) {
                    resourceDef.setAggregatedExecutePrivilege(def);
                } else {
                    resourceDef.setAggregatedExecutePrivilege(resourceDef.getAggregatedExecutePrivilege().add(def));
                }
            }
        } else {
            // Create new resource definition
            ResourceDef resourceDef = new ResourceDef(def.resourceName);
            resourceDef.getPrivilegesList().add(def);

            if (def.operation == PrivilegeOperation.WRITE) {
                resourceDef.setAggregatedWritePrivilege(def);
            }
            if (def.operation == PrivilegeOperation.READ || def.operation == PrivilegeOperation.WRITE) {
                resourceDef.setAggregatedReadPrivilege(def);
            }
            if (def.operation == PrivilegeOperation.EXECUTE) {
                resourceDef.setAggregatedExecutePrivilege(def);
            }

            resourcesMap.put(def.resourceName, resourceDef);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoleDef)) return false;

        RoleDef roleDef = (RoleDef) o;

        if (!businessRoles.equals(roleDef.businessRoles)) return false;
        if (roleId != null ? !roleId.equals(roleDef.roleId) : roleDef.roleId != null) return false;
        return !(name != null ? !name.equals(roleDef.name) : roleDef.name != null);
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (roleId != null ? roleId.hashCode() : 0);
        result = 31 * result + businessRoles.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "RoleDef{" + "roleId=" + roleId + ", name='" + name + '\'' + ", businessRoles=" + businessRoles + '}';
    }
}
