package com.nomendi6.orgsec.storage.jwt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO for Membership claim in JWT token.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MembershipClaimDTO {

    @JsonProperty("organizationId")
    private Long organizationId;

    @JsonProperty("companyId")
    private Long companyId;

    @JsonProperty("pathId")
    private String pathId;

    @JsonProperty("positionRoleIds")
    private List<Long> positionRoleIds;

    public MembershipClaimDTO() {
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public String getPathId() {
        return pathId;
    }

    public void setPathId(String pathId) {
        this.pathId = pathId;
    }

    public List<Long> getPositionRoleIds() {
        return positionRoleIds;
    }

    public void setPositionRoleIds(List<Long> positionRoleIds) {
        this.positionRoleIds = positionRoleIds;
    }
}
