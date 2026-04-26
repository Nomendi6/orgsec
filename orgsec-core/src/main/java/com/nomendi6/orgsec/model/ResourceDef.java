package com.nomendi6.orgsec.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class ResourceDef implements Serializable {

    private String resourceName;
    private List<PrivilegeDef> privilegesList;
    private PrivilegeDef aggregatedWritePrivilege;
    private PrivilegeDef aggregatedReadPrivilege;
    private PrivilegeDef aggregatedExecutePrivilege;

    public ResourceDef(String resourceName) {
        this.resourceName = resourceName;
        this.aggregatedWritePrivilege = new PrivilegeDef();
        this.aggregatedReadPrivilege = new PrivilegeDef();
        this.aggregatedExecutePrivilege = new PrivilegeDef();
        this.privilegesList = new ArrayList<>();
    }

    // Getter methods
    public String getResourceName() {
        return resourceName;
    }

    public List<PrivilegeDef> getPrivilegesList() {
        return privilegesList;
    }

    public PrivilegeDef getAggregatedWritePrivilege() {
        return aggregatedWritePrivilege;
    }

    public PrivilegeDef getAggregatedReadPrivilege() {
        return aggregatedReadPrivilege;
    }

    public PrivilegeDef getAggregatedExecutePrivilege() {
        return aggregatedExecutePrivilege;
    }

    // Setter methods
    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public void setPrivilegesList(List<PrivilegeDef> privilegesList) {
        this.privilegesList = privilegesList;
    }

    public void setAggregatedWritePrivilege(PrivilegeDef aggregatedWritePrivilege) {
        this.aggregatedWritePrivilege = aggregatedWritePrivilege;
    }

    public void setAggregatedReadPrivilege(PrivilegeDef aggregatedReadPrivilege) {
        this.aggregatedReadPrivilege = aggregatedReadPrivilege;
    }

    public void setAggregatedExecutePrivilege(PrivilegeDef aggregatedExecutePrivilege) {
        this.aggregatedExecutePrivilege = aggregatedExecutePrivilege;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResourceDef)) return false;

        ResourceDef that = (ResourceDef) o;

        if (resourceName != null ? !resourceName.equals(that.resourceName) : that.resourceName != null) return false;
        if (privilegesList != null ? !privilegesList.equals(that.privilegesList) : that.privilegesList != null) return false;
        if (
            aggregatedWritePrivilege != null
                ? !aggregatedWritePrivilege.equals(that.aggregatedWritePrivilege)
                : that.aggregatedWritePrivilege != null
        ) return false;
        if (
            aggregatedReadPrivilege != null
                ? !aggregatedReadPrivilege.equals(that.aggregatedReadPrivilege)
                : that.aggregatedReadPrivilege != null
        ) return false;
        return aggregatedExecutePrivilege != null
            ? aggregatedExecutePrivilege.equals(that.aggregatedExecutePrivilege)
            : that.aggregatedExecutePrivilege == null;
    }

    @Override
    public int hashCode() {
        int result = resourceName != null ? resourceName.hashCode() : 0;
        result = 31 * result + (privilegesList != null ? privilegesList.hashCode() : 0);
        result = 31 * result + (aggregatedWritePrivilege != null ? aggregatedWritePrivilege.hashCode() : 0);
        result = 31 * result + (aggregatedReadPrivilege != null ? aggregatedReadPrivilege.hashCode() : 0);
        result = 31 * result + (aggregatedExecutePrivilege != null ? aggregatedExecutePrivilege.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return (
            "ResourceDef{" +
            "resourceName='" +
            resourceName +
            '\'' +
            ", privilegesList=" +
            privilegesList +
            ", aggregatedWritePrivilege=" +
            aggregatedWritePrivilege +
            ", aggregatedReadPrivilege=" +
            aggregatedReadPrivilege +
            ", aggregatedExecutePrivilege=" +
            aggregatedExecutePrivilege +
            '}'
        );
    }
}
