package com.nomendi6.orgsec.storage.redis.cache;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builder for Redis cache keys.
 * <p>
 * Generates cache keys following the naming convention:
 * - Person: orgsec:p:{userId}
 * - Organization: orgsec:o:{orgId}
 * - Legacy role: orgsec:r:{roleId}
 * - Party role: orgsec:r:party:{roleId}
 * - Position role: orgsec:r:position:{roleId}
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
    private static final String PARTY_ROLE_PREFIX = ROLE_PREFIX + "party:";
    private static final String POSITION_ROLE_PREFIX = ROLE_PREFIX + "position:";
    private static final String PRIVILEGE_PREFIX = KEY_PREFIX + ":priv:";

    private static final String PERSON_PATTERN = KEY_PREFIX + ":p:*";
    private static final String ORGANIZATION_PATTERN = KEY_PREFIX + ":o:*";
    private static final String ROLE_PATTERN = KEY_PREFIX + ":r:*";
    private static final String PRIVILEGE_PATTERN = KEY_PREFIX + ":priv:*";
    private static final String OBFUSCATED_KEY_PREFIX = KEY_PREFIX + ":";
    private static final String OBFUSCATED_KEY_PATTERN =
        OBFUSCATED_KEY_PREFIX + "?".repeat(64);
    private static final String ALL_PATTERN = KEY_PREFIX + ":*";
    private static final Pattern OBFUSCATED_LEGACY_KEY = Pattern.compile(
        "^" + OBFUSCATED_KEY_PREFIX + "[0-9a-f]{64}$"
    );
    private static final List<String> LEGACY_DATA_KEY_PATTERNS = List.of(
        PERSON_PATTERN,
        ORGANIZATION_PATTERN,
        ROLE_PATTERN,
        PRIVILEGE_PATTERN,
        OBFUSCATED_KEY_PATTERN
    );

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
     * Builds a cache key for a party/organization role.
     *
     * @param roleId the party role ID
     * @return the cache key
     */
    public String buildPartyRoleKey(Long roleId) {
        if (roleId == null) {
            throw new IllegalArgumentException("Party role ID cannot be null");
        }
        return buildKey(PARTY_ROLE_PREFIX + roleId);
    }

    /**
     * Builds a cache key for a position role.
     *
     * @param roleId the position role ID
     * @return the cache key
     */
    public String buildPositionRoleKey(Long roleId) {
        if (roleId == null) {
            throw new IllegalArgumentException("Position role ID cannot be null");
        }
        return buildKey(POSITION_ROLE_PREFIX + roleId);
    }

    /**
     * Builds a cache key for a privilege.
     *
     * @param privilegeIdentifier the privilege identifier
     * @return the cache key
     */
    public String buildPrivilegeKey(String privilegeIdentifier) {
        if (privilegeIdentifier == null || privilegeIdentifier.trim().isEmpty()) {
            throw new IllegalArgumentException("Privilege identifier cannot be null or empty");
        }
        return buildKey(PRIVILEGE_PREFIX + privilegeIdentifier);
    }

    /**
     * Builds a cache key for a legacy numeric privilege ID.
     *
     * @param privilegeId the privilege ID
     * @return the cache key
     * @deprecated use {@link #buildPrivilegeKey(String)} to avoid 32-bit hash collisions.
     * This legacy method maps numeric values into the same Redis key namespace as
     * string privilege identifiers and is kept only for source compatibility.
     */
    @Deprecated
    public String buildPrivilegeKey(Long privilegeId) {
        if (privilegeId == null) {
            throw new IllegalArgumentException("Privilege ID cannot be null");
        }
        return buildPrivilegeKey(String.valueOf(privilegeId));
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
     * Returns the broad pattern for matching every OrgSec key.
     *
     * <p>This is a diagnostic compatibility API. It must never be used for deletion because it
     * also matches versioned durable protocol keys. Legacy L2 clearing uses the closed allow-list
     * returned by {@link #legacyDataKeyPatterns()} instead.</p>
     *
     * @return the all keys pattern
     */
    public String allKeysPattern() {
        return ALL_PATTERN;
    }

    /**
     * Returns only the closed legacy L2 data-key pattern allow-list.
     *
     * <p>The broad {@code orgsec:*} pattern is deliberately excluded because it also contains
     * versioned durable protocol keys such as control, lease and future protocol records.</p>
     */
    List<String> legacyDataKeyPatterns() {
        return LEGACY_DATA_KEY_PATTERNS;
    }

    /**
     * Defense-in-depth validation for keys returned by a legacy clear scan.
     */
    boolean isLegacyDataKey(String key) {
        if (key == null) {
            return false;
        }
        return hasCanonicalLongSuffix(key, PERSON_PREFIX)
            || hasCanonicalLongSuffix(key, ORGANIZATION_PREFIX)
            || hasCanonicalLongSuffix(key, ROLE_PREFIX)
            || hasCanonicalLongSuffix(key, PARTY_ROLE_PREFIX)
            || hasCanonicalLongSuffix(key, POSITION_ROLE_PREFIX)
            || hasNonBlankSuffix(key, PRIVILEGE_PREFIX)
            || OBFUSCATED_LEGACY_KEY.matcher(key).matches();
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

    private static boolean hasCanonicalLongSuffix(String key, String prefix) {
        if (!key.startsWith(prefix)) {
            return false;
        }
        String suffix = key.substring(prefix.length());
        try {
            long value = Long.parseLong(suffix);
            return Long.toString(value).equals(suffix);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static boolean hasNonBlankSuffix(String key, String prefix) {
        return key.startsWith(prefix) && !key.substring(prefix.length()).trim().isEmpty();
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
