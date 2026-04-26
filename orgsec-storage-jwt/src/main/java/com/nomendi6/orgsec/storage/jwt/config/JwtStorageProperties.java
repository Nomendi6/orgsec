package com.nomendi6.orgsec.storage.jwt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JWT storage.
 */
@ConfigurationProperties(prefix = "orgsec.storage.jwt")
public class JwtStorageProperties {

    /**
     * Name of the claim containing OrgSec data.
     */
    private String claimName = "orgsec";

    /**
     * Expected version of claims structure.
     */
    private String claimVersion = "1.0";

    /**
     * HTTP header containing the JWT token.
     */
    private String tokenHeader = "Authorization";

    /**
     * Prefix before the token in the header.
     */
    private String tokenPrefix = "Bearer ";

    /**
     * Whether to cache parsed PersonDef per request.
     */
    private boolean cacheParsedPerson = true;

    /**
     * Cache TTL in seconds (for future use with cross-request caching).
     */
    private int cacheTtlSeconds = 60;

    public String getClaimName() {
        return claimName;
    }

    public void setClaimName(String claimName) {
        this.claimName = claimName;
    }

    public String getClaimVersion() {
        return claimVersion;
    }

    public void setClaimVersion(String claimVersion) {
        this.claimVersion = claimVersion;
    }

    public String getTokenHeader() {
        return tokenHeader;
    }

    public void setTokenHeader(String tokenHeader) {
        this.tokenHeader = tokenHeader;
    }

    public String getTokenPrefix() {
        return tokenPrefix;
    }

    public void setTokenPrefix(String tokenPrefix) {
        this.tokenPrefix = tokenPrefix;
    }

    public boolean isCacheParsedPerson() {
        return cacheParsedPerson;
    }

    public void setCacheParsedPerson(boolean cacheParsedPerson) {
        this.cacheParsedPerson = cacheParsedPerson;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }
}
