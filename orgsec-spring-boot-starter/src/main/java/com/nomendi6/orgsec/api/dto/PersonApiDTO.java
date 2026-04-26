package com.nomendi6.orgsec.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO for Person API response.
 * This is returned by the Person API endpoint for Keycloak mapper.
 */
public class PersonApiDTO {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("relatedUserId")
    private String relatedUserId;

    @JsonProperty("relatedUserLogin")
    private String relatedUserLogin;

    @JsonProperty("defaultCompanyId")
    private Long defaultCompanyId;

    @JsonProperty("defaultOrgunitId")
    private Long defaultOrgunitId;

    @JsonProperty("memberships")
    private List<OrganizationMembershipApiDTO> memberships;

    public PersonApiDTO() {
    }

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

    public List<OrganizationMembershipApiDTO> getMemberships() {
        return memberships;
    }

    public void setMemberships(List<OrganizationMembershipApiDTO> memberships) {
        this.memberships = memberships;
    }
}
