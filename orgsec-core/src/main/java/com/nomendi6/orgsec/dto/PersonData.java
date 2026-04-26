package com.nomendi6.orgsec.dto;

import java.io.Serializable;

/**
 * Data transfer object for person information.
 * This class contains minimal person data needed by the orgsec module,
 * abstracting away the underlying entity structure.
 */
public class PersonData implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String relatedUserId;
    private String relatedUserLogin;
    private Long defaultCompanyId;
    private Long defaultOrgunitId;
    private String status;

    // Constructors

    public PersonData() {}

    public PersonData(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public PersonData(Long id, String name, String relatedUserId, String relatedUserLogin) {
        this.id = id;
        this.name = name;
        this.relatedUserId = relatedUserId;
        this.relatedUserLogin = relatedUserLogin;
    }

    // Builder pattern for convenient construction

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private PersonData personData = new PersonData();

        public Builder id(Long id) {
            personData.id = id;
            return this;
        }

        public Builder name(String name) {
            personData.name = name;
            return this;
        }

        public Builder relatedUserId(String relatedUserId) {
            personData.relatedUserId = relatedUserId;
            return this;
        }

        public Builder relatedUserLogin(String relatedUserLogin) {
            personData.relatedUserLogin = relatedUserLogin;
            return this;
        }

        public Builder defaultCompanyId(Long defaultCompanyId) {
            personData.defaultCompanyId = defaultCompanyId;
            return this;
        }

        public Builder defaultOrgunitId(Long defaultOrgunitId) {
            personData.defaultOrgunitId = defaultOrgunitId;
            return this;
        }

        public Builder status(String status) {
            personData.status = status;
            return this;
        }

        public PersonData build() {
            return personData;
        }
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRelatedUserId() {
        return relatedUserId;
    }

    public void setRelatedUserId(String relatedUserId) {
        this.relatedUserId = relatedUserId;
    }

    public String getRelatedUserLogin() {
        return relatedUserLogin;
    }

    public void setRelatedUserLogin(String relatedUserLogin) {
        this.relatedUserLogin = relatedUserLogin;
    }

    public Long getDefaultCompanyId() {
        return defaultCompanyId;
    }

    public void setDefaultCompanyId(Long defaultCompanyId) {
        this.defaultCompanyId = defaultCompanyId;
    }

    public Long getDefaultOrgunitId() {
        return defaultOrgunitId;
    }

    public void setDefaultOrgunitId(Long defaultOrgunitId) {
        this.defaultOrgunitId = defaultOrgunitId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Check if the person is active.
     * @return true if status is "ACTIVE", false otherwise
     */
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    @Override
    public String toString() {
        return (
            "PersonData{" +
            "id=" +
            id +
            ", name='" +
            name +
            '\'' +
            ", relatedUserLogin='" +
            relatedUserLogin +
            '\'' +
            ", status='" +
            status +
            '\'' +
            '}'
        );
    }
}
