package com.nomendi6.orgsec.api.dto;

/**
 * Stable Person API error codes. Status without the matching code is a contract break
 * for the Keycloak mapper (not a user deny).
 */
public final class PersonApiErrorCodes {

    public static final String API_VERSION = "1.0";

    public static final String PERSON_NOT_FOUND = "PERSON_NOT_FOUND";
    public static final String CALLBACK_UNAUTHENTICATED = "CALLBACK_UNAUTHENTICATED";
    public static final String CALLBACK_FORBIDDEN = "CALLBACK_FORBIDDEN";

    private PersonApiErrorCodes() {
    }
}
