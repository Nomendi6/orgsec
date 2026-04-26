package com.nomendi6.orgsec.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Definition of an organization in the security context.
 */
public class OrganizationDef {

    @NotNull(message = "Organization ID is required")
    public Long organizationId; // organization/party id

    @Size(max = 255, message = "Organization name must not exceed 255 characters")
    public String organizationName; // organization name

    public Long positionId; // person position role in the organization

    @Size(max = 1000, message = "Path ID must not exceed 1000 characters")
    public String pathId; // organization pathId

    @Size(max = 1000, message = "Parent path must not exceed 1000 characters")
    public String parentPath; // organization parentPath

    public Long companyId; // company id

    @Size(max = 1000, message = "Company parent path must not exceed 1000 characters")
    public String companyParentPath; // company parentPath

    public Set<RoleDef> positionRolesSet; // roles assigned to a person position (if this is assigned organization)
    public Set<RoleDef> organizationRolesSet; // roles assigned to an organization

    // The businessRolesMap contains aggregated privileges on the business role level.
    // (business roles are owner, executor, supervisor, customer, contractor, ...)
    // Privileges are aggregated for both organization and positions
    // Filter contains the filter that should be applied to getOne a list of
    // resources that satisfies privileges.
    public Map<String, BusinessRoleDef> businessRolesMap;

    public OrganizationDef() {
        positionRolesSet = new HashSet<>();
        organizationRolesSet = new HashSet<>();
        businessRolesMap = new HashMap<>();
    }

    public OrganizationDef(
        String organizationName,
        Long organizationId,
        Long positionId,
        String pathId,
        String parentPath,
        Long companyId,
        String companyParentPath
    ) {
        this.organizationName = organizationName;
        this.organizationId = organizationId;
        this.positionId = positionId;
        this.pathId = pathId;
        this.parentPath = parentPath;
        this.companyId = companyId;
        this.companyParentPath = companyParentPath;
        positionRolesSet = new HashSet<>();
        organizationRolesSet = new HashSet<>();
        businessRolesMap = new HashMap<>();
    }

    public OrganizationDef setOrganizationName(String organizationName) {
        this.organizationName = organizationName;
        return this;
    }

    public OrganizationDef setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
        return this;
    }

    public OrganizationDef setPositionId(Long positionId) {
        this.positionId = positionId;
        return this;
    }

    public OrganizationDef setPathId(String pathId) {
        this.pathId = pathId;
        return this;
    }

    public OrganizationDef setParentPath(String parentPath) {
        this.parentPath = parentPath;
        return this;
    }

    public void addPositionRole(RoleDef role) {
        positionRolesSet.add(role);
    }

    public void addOrganizationRole(RoleDef role) {
        organizationRolesSet.add(role);
    }

    public static OrganizationDef copyFrom(OrganizationDef other) {
        OrganizationDef newOrganization = new OrganizationDef();

        newOrganization.organizationId = other.organizationId;
        newOrganization.organizationName = other.organizationName;
        newOrganization.positionId = other.positionId;
        newOrganization.pathId = other.pathId;
        newOrganization.parentPath = other.parentPath;
        newOrganization.companyId = other.companyId;
        newOrganization.companyParentPath = other.companyParentPath;
        newOrganization.positionRolesSet.addAll(other.positionRolesSet);
        newOrganization.organizationRolesSet.addAll(other.organizationRolesSet);
        newOrganization.businessRolesMap.putAll(other.businessRolesMap);

        return newOrganization;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrganizationDef)) return false;

        OrganizationDef that = (OrganizationDef) o;

        if (organizationId != null ? !organizationId.equals(that.organizationId) : that.organizationId != null) return false;
        if (organizationName != null ? !organizationName.equals(that.organizationName) : that.organizationName != null) return false;
        if (positionId != null ? !positionId.equals(that.positionId) : that.positionId != null) return false;
        if (pathId != null ? !pathId.equals(that.pathId) : that.pathId != null) return false;
        if (parentPath != null ? !parentPath.equals(that.parentPath) : that.parentPath != null) return false;
        if (companyId != null ? !companyId.equals(that.companyId) : that.companyId != null) return false;
        if (companyParentPath != null ? !companyParentPath.equals(that.companyParentPath) : that.companyParentPath != null) return false;
        if (positionRolesSet != null ? !positionRolesSet.equals(that.positionRolesSet) : that.positionRolesSet != null) return false;
        if (
            organizationRolesSet != null ? !organizationRolesSet.equals(that.organizationRolesSet) : that.organizationRolesSet != null
        ) return false;
        return businessRolesMap != null ? businessRolesMap.equals(that.businessRolesMap) : that.businessRolesMap == null;
    }

    @Override
    public int hashCode() {
        int result = organizationId != null ? organizationId.hashCode() : 0;
        result = 31 * result + (organizationName != null ? organizationName.hashCode() : 0);
        result = 31 * result + (positionId != null ? positionId.hashCode() : 0);
        result = 31 * result + (pathId != null ? pathId.hashCode() : 0);
        result = 31 * result + (parentPath != null ? parentPath.hashCode() : 0);
        result = 31 * result + (companyId != null ? companyId.hashCode() : 0);
        result = 31 * result + (companyParentPath != null ? companyParentPath.hashCode() : 0);
        result = 31 * result + (positionRolesSet != null ? positionRolesSet.hashCode() : 0);
        result = 31 * result + (organizationRolesSet != null ? organizationRolesSet.hashCode() : 0);
        result = 31 * result + (this.businessRolesMap != null ? businessRolesMap.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return (
            "OrganizationDef{" +
            "organizationId=" +
            organizationId +
            ", organizationName='" +
            organizationName +
            '\'' +
            ", positionId=" +
            positionId +
            ", pathId='" +
            pathId +
            '\'' +
            ", parentPath='" +
            parentPath +
            '\'' +
            ", companyId=" +
            companyId +
            ", companyParentPath='" +
            companyParentPath +
            '\'' +
            ", positionRolesSet=" +
            positionRolesSet +
            ", organizationRolesSet=" +
            organizationRolesSet +
            ", businessRolesMap=" +
            businessRolesMap +
            '}'
        );
    }
}
