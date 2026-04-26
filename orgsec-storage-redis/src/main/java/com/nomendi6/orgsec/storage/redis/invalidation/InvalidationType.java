package com.nomendi6.orgsec.storage.redis.invalidation;

/**
 * Type of invalidation event.
 */
public enum InvalidationType {
    /**
     * Person entity changed.
     */
    PERSON_CHANGED,

    /**
     * Organization entity changed.
     */
    ORG_CHANGED,

    /**
     * Role entity changed.
     */
    ROLE_CHANGED,

    /**
     * Privilege entity changed.
     */
    PRIVILEGE_CHANGED,

    /**
     * Full security cache refresh triggered (admin operation).
     */
    SECURITY_REFRESH
}
