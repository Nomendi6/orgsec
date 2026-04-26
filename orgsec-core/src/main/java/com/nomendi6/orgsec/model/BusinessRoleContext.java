package com.nomendi6.orgsec.model;

/**
 * Immutable context object that holds security-related information for a business role.
 * Used to encapsulate company, organization, and person security context data.
 */
public class BusinessRoleContext {

    private final Long companyId;
    private final String companyPath;
    private final Long orgId;
    private final String orgPath;
    private final Long personId;
    private final boolean checkCompany;
    private final boolean checkOrg;
    private final boolean checkPerson;

    private BusinessRoleContext(Builder builder) {
        this.companyId = builder.companyId;
        this.companyPath = builder.companyPath;
        this.orgId = builder.orgId;
        this.orgPath = builder.orgPath;
        this.personId = builder.personId;
        this.checkCompany = builder.checkCompany;
        this.checkOrg = builder.checkOrg;
        this.checkPerson = builder.checkPerson;
    }

    // Getters
    public Long getCompanyId() {
        return companyId;
    }

    public String getCompanyPath() {
        return companyPath;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getOrgPath() {
        return orgPath;
    }

    public Long getPersonId() {
        return personId;
    }

    public boolean isCheckCompany() {
        return checkCompany;
    }

    public boolean isCheckOrg() {
        return checkOrg;
    }

    public boolean isCheckPerson() {
        return checkPerson;
    }

    /**
     * Builder for creating BusinessRoleContext instances.
     */
    public static class Builder {

        private Long companyId;
        private String companyPath;
        private Long orgId;
        private String orgPath;
        private Long personId;
        private boolean checkCompany;
        private boolean checkOrg;
        private boolean checkPerson;

        public Builder companyId(Long companyId) {
            this.companyId = companyId;
            return this;
        }

        public Builder companyPath(String companyPath) {
            this.companyPath = companyPath;
            return this;
        }

        public Builder orgId(Long orgId) {
            this.orgId = orgId;
            return this;
        }

        public Builder orgPath(String orgPath) {
            this.orgPath = orgPath;
            return this;
        }

        public Builder personId(Long personId) {
            this.personId = personId;
            return this;
        }

        public Builder checkCompany(boolean checkCompany) {
            this.checkCompany = checkCompany;
            return this;
        }

        public Builder checkOrg(boolean checkOrg) {
            this.checkOrg = checkOrg;
            return this;
        }

        public Builder checkPerson(boolean checkPerson) {
            this.checkPerson = checkPerson;
            return this;
        }

        public BusinessRoleContext build() {
            return new BusinessRoleContext(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BusinessRoleContext)) return false;

        BusinessRoleContext that = (BusinessRoleContext) o;

        if (checkCompany != that.checkCompany) return false;
        if (checkOrg != that.checkOrg) return false;
        if (checkPerson != that.checkPerson) return false;
        if (companyId != null ? !companyId.equals(that.companyId) : that.companyId != null) return false;
        if (companyPath != null ? !companyPath.equals(that.companyPath) : that.companyPath != null) return false;
        if (orgId != null ? !orgId.equals(that.orgId) : that.orgId != null) return false;
        if (orgPath != null ? !orgPath.equals(that.orgPath) : that.orgPath != null) return false;
        return personId != null ? personId.equals(that.personId) : that.personId == null;
    }

    @Override
    public int hashCode() {
        int result = companyId != null ? companyId.hashCode() : 0;
        result = 31 * result + (companyPath != null ? companyPath.hashCode() : 0);
        result = 31 * result + (orgId != null ? orgId.hashCode() : 0);
        result = 31 * result + (orgPath != null ? orgPath.hashCode() : 0);
        result = 31 * result + (personId != null ? personId.hashCode() : 0);
        result = 31 * result + (checkCompany ? 1 : 0);
        result = 31 * result + (checkOrg ? 1 : 0);
        result = 31 * result + (checkPerson ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return (
            "BusinessRoleContext{" +
            "companyId=" +
            companyId +
            ", companyPath='" +
            companyPath +
            '\'' +
            ", orgId=" +
            orgId +
            ", orgPath='" +
            orgPath +
            '\'' +
            ", personId=" +
            personId +
            ", checkCompany=" +
            checkCompany +
            ", checkOrg=" +
            checkOrg +
            ", checkPerson=" +
            checkPerson +
            '}'
        );
    }
}
