package com.nomendi6.orgsec.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Exact-key error body for Person API 4xx responses consumed by the Keycloak mapper.
 */
public class PersonApiErrorDTO {

    @JsonProperty("code")
    private String code;

    public PersonApiErrorDTO() {
    }

    public PersonApiErrorDTO(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
