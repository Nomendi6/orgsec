package com.nomendi6.orgsec.dto;

import java.io.Serializable;

/**
 * Data transfer object for organization information.
 * This class contains minimal organization data needed by the orgsec module,
 * abstracting away the underlying entity structure.
 */
public class OrganizationData implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String code;
    private Long parentOrganizationId;
    private String status;
    private String type;

    // Constructors

    public OrganizationData() {}

    public OrganizationData(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public OrganizationData(Long id, String name, String code) {
        this.id = id;
        this.name = name;
        this.code = code;
    }

    // Builder pattern for convenient construction

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private OrganizationData organizationData = new OrganizationData();

        public Builder id(Long id) {
            organizationData.id = id;
            return this;
        }

        public Builder name(String name) {
            organizationData.name = name;
            return this;
        }

        public Builder code(String code) {
            organizationData.code = code;
            return this;
        }

        public Builder parentOrganizationId(Long parentOrganizationId) {
            organizationData.parentOrganizationId = parentOrganizationId;
            return this;
        }

        public Builder status(String status) {
            organizationData.status = status;
            return this;
        }

        public Builder type(String type) {
            organizationData.type = type;
            return this;
        }

        public OrganizationData build() {
            return organizationData;
        }
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Alias for getId() to match PartyDTO interface
     * @return the organization ID
     */
    public Long getPartyId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public Long getParentOrganizationId() {
        return parentOrganizationId;
    }

    public void setParentOrganizationId(Long parentOrganizationId) {
        this.parentOrganizationId = parentOrganizationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * Check if the organization is active.
     * @return true if status is "ACTIVE", false otherwise
     */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    @Override
    public String toString() {
        return (
            "OrganizationData{" + "id=" + id + ", name='" + name + '\'' + ", code='" + code + '\'' + ", status='" + status + '\'' + '}'
        );
    }
}
