package com.nomendi6.orgsec.storage.redis.cache;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builder for Redis cache keys.
 * <p>
 * Generates cache keys following the naming convention:
 * - Person: orgsec:p:{userId}
 * - Organization: orgsec:o:{orgId}
 * - Role: orgsec:r:{roleId}
 * - Privilege: orgsec:priv:{privilegeId}
 * </p>
 * <p>
 * Supports optional key obfuscation (SHA-256 hash) for security.
 * </p>
 */
public class CacheKeyBuilder {

    private static final Logger log = LoggerFactory.getLogger(CacheKeyBuilder.class);

    private static final String KEY_PREFIX = "orgsec";
    private static final String PERSON_PREFIX = KEY_PREFIX + ":p:";
    private static final String ORGANIZATION_PREFIX = KEY_PREFIX + ":o:";
    private static final String ROLE_PREFIX = KEY_PREFIX + ":r:";
    private static final String PRIVILEGE_PREFIX = KEY_PREFIX + ":priv:";

    private static final String PERSON_PATTERN = KEY_PREFIX + ":p:*";
    private static final String ORGANIZATION_PATTERN = KEY_PREFIX + ":o:*";
    private static final String ROLE_PATTERN = KEY_PREFIX + ":r:*";
    private static final String ALL_PATTERN = KEY_PREFIX + ":*";

    private final boolean obfuscateKeys;

    /**
     * Constructs a new cache key builder.
     *
     * @param obfuscateKeys whether to obfuscate keys using SHA-256 hash
     */
    public CacheKeyBuilder(boolean obfuscateKeys) {
        this.obfuscateKeys = obfuscateKeys;
    }

    /**
     * Builds a cache key for a person.
     *
     * @param userId the user ID
     * @return the cache key
     */
    public String buildPersonKey(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID cannot be null");
        }
        return buildKey(PERSON_PREFIX + userId);
    }

    /**
     * Builds a cache key for an organization.
     *
     * @param orgId the organization ID
     * @return the cache key
     */
    public String buildOrganizationKey(Long orgId) {
        if (orgId == null) {
            throw new IllegalArgumentException("Organization ID cannot be null");
        }
        return buildKey(ORGANIZATION_PREFIX + orgId);
    }

    /**
     * Builds a cache key for a role.
     *
     * @param roleId the role ID
     * @return the cache key
     */
    public String buildRoleKey(Long roleId) {
        if (roleId == null) {
            throw new IllegalArgumentException("Role ID cannot be null");
        }
        return buildKey(ROLE_PREFIX + roleId);
    }

    /**
     * Builds a cache key for a privilege.
     *
     * @param privilegeId the privilege ID
     * @return the cache key
     */
    public String buildPrivilegeKey(Long privilegeId) {
        if (privilegeId == null) {
            throw new IllegalArgumentException("Privilege ID cannot be null");
        }
        return buildKey(PRIVILEGE_PREFIX + privilegeId);
    }

    /**
     * Returns the pattern for matching all person keys.
     *
     * @return the person keys pattern
     */
    public String personKeysPattern() {
        return PERSON_PATTERN;
    }

    /**
     * Returns the pattern for matching all organization keys.
     *
     * @return the organization keys pattern
     */
    public String organizationKeysPattern() {
        return ORGANIZATION_PATTERN;
    }

    /**
     * Returns the pattern for matching all role keys.
     *
     * @return the role keys pattern
     */
    public String roleKeysPattern() {
        return ROLE_PATTERN;
    }

    /**
     * Returns the pattern for matching all OrgSec cache keys.
     *
     * @return the all keys pattern
     */
    public String allKeysPattern() {
        return ALL_PATTERN;
    }

    /**
     * Builds the final key, applying obfuscation if enabled.
     *
     * @param plainKey the plain cache key
     * @return the final key (obfuscated or plain)
     */
    private String buildKey(String plainKey) {
        if (obfuscateKeys) {
            return hashKey(plainKey);
        }
        return plainKey;
    }

    /**
     * Hashes a key using SHA-256.
     *
     * @param key the key to hash
     * @return hexadecimal string representation of the hash
     */
    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + ":" + Hex.encodeHexString(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available, using plain key", e);
            return key;
        }
    }

    /**
     * Returns whether key obfuscation is enabled.
     *
     * @return true if obfuscation is enabled
     */
    public boolean isObfuscateKeys() {
        return obfuscateKeys;
    }
}
