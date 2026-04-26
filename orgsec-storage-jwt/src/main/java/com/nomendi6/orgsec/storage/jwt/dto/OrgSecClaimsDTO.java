package com.nomendi6.orgsec.storage.jwt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Root DTO for OrgSec claims in JWT token.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrgSecClaimsDTO {

    @JsonProperty("version")
    private String version;

    @JsonProperty("person")
    private PersonClaimDTO person;

    @JsonProperty("memberships")
    private List<MembershipClaimDTO> memberships;

    public OrgSecClaimsDTO() {
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public PersonClaimDTO getPerson() {
        return person;
    }

    public void setPerson(PersonClaimDTO person) {
        this.person = person;
    }

    public List<MembershipClaimDTO> getMemberships() {
        return memberships;
    }

    public void setMemberships(List<MembershipClaimDTO> memberships) {
        this.memberships = memberships;
    }
}
