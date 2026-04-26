package com.nomendi6.orgsec.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashMap;
import java.util.Map;

/**
 * Definition of a person in the security context.
 */
public class PersonDef {

    @NotNull(message = "Person ID is required")
    public Long personId;

    @Size(max = 255, message = "Person name must not exceed 255 characters")
    public String personName;

    public Long defaultCompanyId;
    public Long defaultOrgunitId;

    @Size(max = 255, message = "Related user ID must not exceed 255 characters")
    public String relatedUserId;

    @Size(max = 255, message = "Related user login must not exceed 255 characters")
    public String relatedUserLogin;

    public Map<Long, OrganizationDef> organizationsMap;

    public PersonDef(Long personId, String personName) {
        this.personId = personId;
        this.personName = personName;
        organizationsMap = new HashMap<>();
    }

    public PersonDef setPersonName(String personName) {
        this.personName = personName;
        return this;
    }

    public PersonDef setPersonId(Long personId) {
        this.personId = personId;
        return this;
    }

    public PersonDef setDefaultCompanyId(Long defaultCompanyId) {
        this.defaultCompanyId = defaultCompanyId;
        return this;
    }

    public PersonDef setDefaultOrgunitId(Long defaultOrgunitID) {
        this.defaultOrgunitId = defaultOrgunitID;
        return this;
    }

    public PersonDef setRelatedUserId(String relatedUserId) {
        this.relatedUserId = relatedUserId;
        return this;
    }

    public PersonDef setRelatedUserLogin(String relatedUserLogin) {
        this.relatedUserLogin = relatedUserLogin;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PersonDef)) return false;

        PersonDef personDef = (PersonDef) o;

        if (personName != null ? !personName.equals(personDef.personName) : personDef.personName != null) return false;
        if (personId != null ? !personId.equals(personDef.personId) : personDef.personId != null) return false;
        if (
            defaultCompanyId != null ? !defaultCompanyId.equals(personDef.defaultCompanyId) : personDef.defaultCompanyId != null
        ) return false;
        if (
            defaultOrgunitId != null ? !defaultOrgunitId.equals(personDef.defaultOrgunitId) : personDef.defaultOrgunitId != null
        ) return false;
        if (relatedUserId != null ? !relatedUserId.equals(personDef.relatedUserId) : personDef.relatedUserId != null) return false;
        if (
            relatedUserLogin != null ? !relatedUserLogin.equals(personDef.relatedUserLogin) : personDef.relatedUserLogin != null
        ) return false;
        return organizationsMap != null ? organizationsMap.equals(personDef.organizationsMap) : personDef.organizationsMap == null;
    }

    @Override
    public int hashCode() {
        int result = personName != null ? personName.hashCode() : 0;
        result = 31 * result + (personId != null ? personId.hashCode() : 0);
        result = 31 * result + (defaultCompanyId != null ? defaultCompanyId.hashCode() : 0);
        result = 31 * result + (defaultOrgunitId != null ? defaultOrgunitId.hashCode() : 0);
        result = 31 * result + (relatedUserId != null ? relatedUserId.hashCode() : 0);
        result = 31 * result + (relatedUserLogin != null ? relatedUserLogin.hashCode() : 0);
        result = 31 * result + (organizationsMap != null ? organizationsMap.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return (
            "PersonDef{" +
            "personName='" +
            personName +
            '\'' +
            ", personId=" +
            personId +
            ", defaultCompanyId=" +
            defaultCompanyId +
            ", defaultOrgunitId=" +
            defaultOrgunitId +
            ", relatedUserId='" +
            relatedUserId +
            '\'' +
            ", relatedUserLogin='" +
            relatedUserLogin +
            '\'' +
            ", organizationsMap=" +
            organizationsMap +
            '}'
        );
    }
}
