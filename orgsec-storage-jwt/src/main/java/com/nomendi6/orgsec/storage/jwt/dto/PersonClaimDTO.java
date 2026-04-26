package com.nomendi6.orgsec.storage.jwt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for Person claim in JWT token.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PersonClaimDTO {

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

    public PersonClaimDTO() {
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
}
