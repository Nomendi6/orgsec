package com.nomendi6.orgsec.storage.redis;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.redis.cache.CacheKeyBuilder;
import com.nomendi6.orgsec.storage.redis.cache.L1Cache;
import com.nomendi6.orgsec.storage.redis.cache.L2RedisCache;
import com.nomendi6.orgsec.storage.redis.config.RedisStorageProperties;
import com.nomendi6.orgsec.storage.redis.invalidation.InvalidationEventPublisher;
import com.nomendi6.orgsec.storage.redis.preload.CacheWarmer;
import com.nomendi6.orgsec.storage.redis.preload.WarmupStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Redis-based implementation of SecurityDataStorage.
 * <p>
 * Provides a 2-level cache architecture:
 * - L1: In-memory LRU cache (fast, local)
 * - L2: Redis distributed cache (shared across instances)
 * </p>
 * <p>
 * Features:
 * - Cache invalidation via Redis Pub/Sub
 * - Automatic preloading on startup (configurable)
 * - TTL-based expiration
 * - Circuit breaker for resilience
 * </p>
 */
public class RedisSecurityDataStorage implements SecurityDataStorage {

    private static final Logger log = LoggerFactory.getLogger(RedisSecurityDataStorage.class);

    private final RedisStorageProperties properties;

    // L1 Caches (in-memory)
    private final L1Cache<Long, PersonDef> personL1Cache;
    private final L1Cache<Long, OrganizationDef> organizationL1Cache;
    private final L1Cache<Long, RoleDef> roleL1Cache;
    private final L1Cache<String, PrivilegeDef> privilegeL1Cache;

    // L2 Caches (Redis)
    private final L2RedisCache<PersonDef> personL2Cache;
    private final L2RedisCache<OrganizationDef> organizationL2Cache;
    private final L2RedisCache<RoleDef> roleL2Cache;
    private final L2RedisCache<PrivilegeDef> privilegeL2Cache;

    // Cache key builder
    private final CacheKeyBuilder cacheKeyBuilder;

    // Invalidation publisher
    private final InvalidationEventPublisher invalidationPublisher;

    // Cache warmer (for preload)
    private final CacheWarmer cacheWarmer;

    // Ready state
    private final AtomicBoolean ready = new AtomicBoolean(false);

    /**
     * Constructor.
     */
    public RedisSecurityDataStorage(
            RedisStorageProperties properties,
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            L1Cache<Long, RoleDef> roleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            L2RedisCache<PersonDef> personL2Cache,
            L2RedisCache<OrganizationDef> organizationL2Cache,
            L2RedisCache<RoleDef> roleL2Cache,
            L2RedisCache<PrivilegeDef> privilegeL2Cache,
            CacheKeyBuilder cacheKeyBuilder,
            InvalidationEventPublisher invalidationPublisher,
            CacheWarmer cacheWarmer) {

        this.properties = properties;
        this.personL1Cache = personL1Cache;
        this.organizationL1Cache = organizationL1Cache;
        this.roleL1Cache = roleL1Cache;
        this.privilegeL1Cache = privilegeL1Cache;
        this.personL2Cache = personL2Cache;
        this.organizationL2Cache = organizationL2Cache;
        this.roleL2Cache = roleL2Cache;
        this.privilegeL2Cache = privilegeL2Cache;
        this.cacheKeyBuilder = cacheKeyBuilder;
        this.invalidationPublisher = invalidationPublisher;
        this.cacheWarmer = cacheWarmer;
    }

    // ========== GET OPERATIONS ==========

    @Override
    public PersonDef getPerson(Long personId) {
        if (personId == null) {
            return null;
        }

        // L1 cache hit
        PersonDef person = personL1Cache.get(personId);
        if (person != null) {
            log.debug("L1 cache hit for person: {}", personId);
            return person;
        }

        // L2 cache hit
        String key = cacheKeyBuilder.buildPersonKey(personId);
        person = personL2Cache.get(key);
        if (person != null) {
            log.debug("L2 cache hit for person: {}", personId);
            // Populate L1 cache
            personL1Cache.put(personId, person);
            return person;
        }

        // Cache miss - return null
        // Note: Loading from source should be handled by higher layers (e.g., SecurityDataService)
        log.debug("Cache miss for person: {}", personId);
        return null;
    }

    @Override
    public OrganizationDef getOrganization(Long orgId) {
        if (orgId == null) {
            return null;
        }

        // L1 cache hit
        OrganizationDef organization = organizationL1Cache.get(orgId);
        if (organization != null) {
            log.debug("L1 cache hit for organization: {}", orgId);
            return organization;
        }

        // L2 cache hit
        String key = cacheKeyBuilder.buildOrganizationKey(orgId);
        organization = organizationL2Cache.get(key);
        if (organization != null) {
            log.debug("L2 cache hit for organization: {}", orgId);
            // Populate L1 cache
            organizationL1Cache.put(orgId, organization);
            return organization;
        }

        // Cache miss - return null
        log.debug("Cache miss for organization: {}", orgId);
        return null;
    }

    @Override
    public RoleDef getPartyRole(Long roleId) {
        return getRole(roleId);
    }

    @Override
    public RoleDef getPositionRole(Long roleId) {
        return getRole(roleId);
    }

    /**
     * Internal method to get role (both party and position roles use same logic).
     */
    private RoleDef getRole(Long roleId) {
        if (roleId == null) {
            return null;
        }

        // L1 cache hit
        RoleDef role = roleL1Cache.get(roleId);
        if (role != null) {
            log.debug("L1 cache hit for role: {}", roleId);
            return role;
        }

        // L2 cache hit
        String key = cacheKeyBuilder.buildRoleKey(roleId);
        role = roleL2Cache.get(key);
        if (role != null) {
            log.debug("L2 cache hit for role: {}", roleId);
            // Populate L1 cache
            roleL1Cache.put(roleId, role);
            return role;
        }

        // Cache miss - return null
        log.debug("Cache miss for role: {}", roleId);
        return null;
    }

    @Override
    public PrivilegeDef getPrivilege(String privilegeIdentifier) {
        if (privilegeIdentifier == null || privilegeIdentifier.trim().isEmpty()) {
            return null;
        }

        // L1 cache hit
        PrivilegeDef privilege = privilegeL1Cache.get(privilegeIdentifier);
        if (privilege != null) {
            log.debug("L1 cache hit for privilege: {}", privilegeIdentifier);
            return privilege;
        }

        // L2 cache hit
        String key = cacheKeyBuilder.buildPrivilegeKey(privilegeIdentifier);
        privilege = privilegeL2Cache.get(key);
        if (privilege != null) {
            log.debug("L2 cache hit for privilege: {}", privilegeIdentifier);
            // Populate L1 cache
            privilegeL1Cache.put(privilegeIdentifier, privilege);
            return privilege;
        }

        // Cache miss - return null
        log.debug("Cache miss for privilege: {}", privilegeIdentifier);
        return null;
    }

    // ========== UPDATE OPERATIONS ==========

    @Override
    public void updatePerson(Long personId, PersonDef person) {
        if (personId == null || person == null) {
            return;
        }

        log.debug("Updating person: {}", personId);

        // Update both caches
        personL1Cache.put(personId, person);
        String key = cacheKeyBuilder.buildPersonKey(personId);
        long ttl = properties.getTtl().getPerson();
        personL2Cache.set(key, person, ttl);

        // Publish invalidation event
        invalidationPublisher.publishPersonChanged(personId);
    }

    @Override
    public void updateOrganization(Long orgId, OrganizationDef organization) {
        if (orgId == null || organization == null) {
            return;
        }

        log.debug("Updating organization: {}", orgId);

        // Update both caches
        organizationL1Cache.put(orgId, organization);
        String key = cacheKeyBuilder.buildOrganizationKey(orgId);
        long ttl = properties.getTtl().getOrganization();
        organizationL2Cache.set(key, organization, ttl);

        // Publish invalidation event
        invalidationPublisher.publishOrganizationChanged(orgId);
    }

    @Override
    public void updateRole(Long roleId, RoleDef role) {
        if (roleId == null || role == null) {
            return;
        }

        log.debug("Updating role: {}", roleId);

        // Update both caches
        roleL1Cache.put(roleId, role);
        String key = cacheKeyBuilder.buildRoleKey(roleId);
        long ttl = properties.getTtl().getRole();
        roleL2Cache.set(key, role, ttl);

        // Publish invalidation event
        invalidationPublisher.publishRoleChanged(roleId);
    }

    // ========== BATCH OPERATIONS ==========

    /**
     * Retrieves multiple persons from cache in a single operation.
     * <p>
     * This is significantly faster than multiple individual get calls
     * as it reduces network round-trips to Redis.
     * </p>
     *
     * @param personIds the collection of person IDs
     * @return map of person ID to PersonDef (missing entries are not included)
     */
    public Map<Long, PersonDef> getPersons(Collection<Long> personIds) {
        if (personIds == null || personIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, PersonDef> result = new HashMap<>();
        Map<Long, String> keysToFetch = new HashMap<>();

        // Check L1 cache first
        for (Long personId : personIds) {
            if (personId == null) continue;

            PersonDef person = personL1Cache.get(personId);
            if (person != null) {
                result.put(personId, person);
            } else {
                keysToFetch.put(personId, cacheKeyBuilder.buildPersonKey(personId));
            }
        }

        // Batch fetch from L2 cache
        if (!keysToFetch.isEmpty()) {
            Map<String, PersonDef> l2Results = personL2Cache.multiGet(keysToFetch.values());

            // Map back to person IDs and populate L1 cache
            for (Map.Entry<Long, String> entry : keysToFetch.entrySet()) {
                PersonDef person = l2Results.get(entry.getValue());
                if (person != null) {
                    result.put(entry.getKey(), person);
                    personL1Cache.put(entry.getKey(), person);
                }
            }
        }

        log.debug("Batch get persons: {} requested, {} found", personIds.size(), result.size());
        return result;
    }

    /**
     * Stores multiple persons in cache in a single operation.
     *
     * @param persons map of person ID to PersonDef
     */
    public void updatePersons(Map<Long, PersonDef> persons) {
        if (persons == null || persons.isEmpty()) {
            return;
        }

        // Build cache entries
        Map<String, PersonDef> cacheEntries = new HashMap<>();
        for (Map.Entry<Long, PersonDef> entry : persons.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                // Update L1 cache
                personL1Cache.put(entry.getKey(), entry.getValue());
                // Prepare L2 entry
                String key = cacheKeyBuilder.buildPersonKey(entry.getKey());
                cacheEntries.put(key, entry.getValue());
            }
        }

        // Batch write to L2 cache
        if (!cacheEntries.isEmpty()) {
            long ttl = properties.getTtl().getPerson();
            personL2Cache.multiSet(cacheEntries, ttl);
        }

        log.debug("Batch update persons: {} entries stored", cacheEntries.size());
    }

    /**
     * Retrieves multiple organizations from cache in a single operation.
     *
     * @param orgIds the collection of organization IDs
     * @return map of org ID to OrganizationDef (missing entries are not included)
     */
    public Map<Long, OrganizationDef> getOrganizations(Collection<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, OrganizationDef> result = new HashMap<>();
        Map<Long, String> keysToFetch = new HashMap<>();

        // Check L1 cache first
        for (Long orgId : orgIds) {
            if (orgId == null) continue;

            OrganizationDef org = organizationL1Cache.get(orgId);
            if (org != null) {
                result.put(orgId, org);
            } else {
                keysToFetch.put(orgId, cacheKeyBuilder.buildOrganizationKey(orgId));
            }
        }

        // Batch fetch from L2 cache
        if (!keysToFetch.isEmpty()) {
            Map<String, OrganizationDef> l2Results = organizationL2Cache.multiGet(keysToFetch.values());

            for (Map.Entry<Long, String> entry : keysToFetch.entrySet()) {
                OrganizationDef org = l2Results.get(entry.getValue());
                if (org != null) {
                    result.put(entry.getKey(), org);
                    organizationL1Cache.put(entry.getKey(), org);
                }
            }
        }

        log.debug("Batch get organizations: {} requested, {} found", orgIds.size(), result.size());
        return result;
    }

    /**
     * Stores multiple organizations in cache in a single operation.
     *
     * @param organizations map of org ID to OrganizationDef
     */
    public void updateOrganizations(Map<Long, OrganizationDef> organizations) {
        if (organizations == null || organizations.isEmpty()) {
            return;
        }

        Map<String, OrganizationDef> cacheEntries = new HashMap<>();
        for (Map.Entry<Long, OrganizationDef> entry : organizations.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                organizationL1Cache.put(entry.getKey(), entry.getValue());
                String key = cacheKeyBuilder.buildOrganizationKey(entry.getKey());
                cacheEntries.put(key, entry.getValue());
            }
        }

        if (!cacheEntries.isEmpty()) {
            long ttl = properties.getTtl().getOrganization();
            organizationL2Cache.multiSet(cacheEntries, ttl);
        }

        log.debug("Batch update organizations: {} entries stored", cacheEntries.size());
    }

    /**
     * Retrieves multiple roles from cache in a single operation.
     *
     * @param roleIds the collection of role IDs
     * @return map of role ID to RoleDef (missing entries are not included)
     */
    public Map<Long, RoleDef> getRoles(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, RoleDef> result = new HashMap<>();
        Map<Long, String> keysToFetch = new HashMap<>();

        // Check L1 cache first
        for (Long roleId : roleIds) {
            if (roleId == null) continue;

            RoleDef role = roleL1Cache.get(roleId);
            if (role != null) {
                result.put(roleId, role);
            } else {
                keysToFetch.put(roleId, cacheKeyBuilder.buildRoleKey(roleId));
            }
        }

        // Batch fetch from L2 cache
        if (!keysToFetch.isEmpty()) {
            Map<String, RoleDef> l2Results = roleL2Cache.multiGet(keysToFetch.values());

            for (Map.Entry<Long, String> entry : keysToFetch.entrySet()) {
                RoleDef role = l2Results.get(entry.getValue());
                if (role != null) {
                    result.put(entry.getKey(), role);
                    roleL1Cache.put(entry.getKey(), role);
                }
            }
        }

        log.debug("Batch get roles: {} requested, {} found", roleIds.size(), result.size());
        return result;
    }

    /**
     * Stores multiple roles in cache in a single operation.
     *
     * @param roles map of role ID to RoleDef
     */
    public void updateRoles(Map<Long, RoleDef> roles) {
        if (roles == null || roles.isEmpty()) {
            return;
        }

        Map<String, RoleDef> cacheEntries = new HashMap<>();
        for (Map.Entry<Long, RoleDef> entry : roles.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                roleL1Cache.put(entry.getKey(), entry.getValue());
                String key = cacheKeyBuilder.buildRoleKey(entry.getKey());
                cacheEntries.put(key, entry.getValue());
            }
        }

        if (!cacheEntries.isEmpty()) {
            long ttl = properties.getTtl().getRole();
            roleL2Cache.multiSet(cacheEntries, ttl);
        }

        log.debug("Batch update roles: {} entries stored", cacheEntries.size());
    }

    // ========== LIFECYCLE OPERATIONS ==========

    @Override
    public void initialize() {
        log.info("Initializing RedisSecurityDataStorage...");

        // Configure cache warmer with batch store callbacks
        cacheWarmer.setPersonBatchStore(this::updatePersons);
        cacheWarmer.setOrganizationBatchStore(this::updateOrganizations);
        cacheWarmer.setRoleBatchStore(this::updateRoles);

        // Perform cache warmup if enabled
        if (properties.getPreload().isEnabled()) {
            log.info("Starting cache warmup...");
            WarmupStats stats = cacheWarmer.warmup();
            log.info("Cache warmup completed: {}", stats);
        } else {
            log.info("Cache warmup disabled");
        }

        ready.set(true);
        log.info("RedisSecurityDataStorage initialized successfully");
    }

    /**
     * Returns the cache warmer for external configuration.
     * <p>
     * Applications can use this to set data loaders for cache warming.
     * </p>
     *
     * @return the cache warmer
     */
    public CacheWarmer getCacheWarmer() {
        return cacheWarmer;
    }

    @Override
    public void refresh() {
        log.info("Refreshing RedisSecurityDataStorage...");

        // Clear L1 caches
        personL1Cache.clear();
        organizationL1Cache.clear();
        roleL1Cache.clear();
        privilegeL1Cache.clear();

        // Optionally clear L2 caches (Redis)
        // This would require deleting all keys matching patterns
        // For now, we rely on TTL expiration

        // Re-warmup if enabled
        if (properties.getPreload().isEnabled()) {
            log.info("Re-warming cache...");
            WarmupStats stats = cacheWarmer.warmup();
            log.info("Cache re-warmup completed: {}", stats);
        }

        // Publish global refresh event
        invalidationPublisher.publishSecurityRefresh();

        log.info("RedisSecurityDataStorage refreshed successfully");
    }

    @Override
    public boolean isReady() {
        return ready.get();
    }

    @Override
    public String getProviderType() {
        return "redis";
    }

    // ========== NOTIFICATION OPERATIONS ==========

    /**
     * Notify that party role data has changed - invalidate cache
     */
    @Override
    public void notifyPartyRoleChanged(Long roleId) {
        log.debug("Redis storage notified: party role {} changed - invalidating cache", roleId);

        // Invalidate L1 cache
        roleL1Cache.invalidate(roleId);

        // Publish distributed invalidation event (will also invalidate L2)
        invalidationPublisher.publishRoleChanged(roleId);
    }

    /**
     * Notify that position role data has changed - invalidate cache
     */
    @Override
    public void notifyPositionRoleChanged(Long roleId) {
        log.debug("Redis storage notified: position role {} changed - invalidating cache", roleId);

        // Invalidate L1 cache
        roleL1Cache.invalidate(roleId);

        // Publish distributed invalidation event
        invalidationPublisher.publishRoleChanged(roleId);
    }

    /**
     * Notify that organization data has changed - invalidate cache
     */
    @Override
    public void notifyOrganizationChanged(Long orgId) {
        log.debug("Redis storage notified: organization {} changed - invalidating cache", orgId);

        // Invalidate L1 cache
        organizationL1Cache.invalidate(orgId);

        // Publish distributed invalidation event
        invalidationPublisher.publishOrganizationChanged(orgId);
    }

    /**
     * Notify that person data has changed - invalidate cache
     */
    @Override
    public void notifyPersonChanged(Long personId) {
        log.debug("Redis storage notified: person {} changed - invalidating cache", personId);

        // Invalidate L1 cache
        personL1Cache.invalidate(personId);

        // Publish distributed invalidation event
        invalidationPublisher.publishPersonChanged(personId);
    }

    /**
     * Get L1 cache statistics for monitoring.
     */
    public L1Cache.CacheStats getPersonL1Stats() {
        return personL1Cache.getStats();
    }

    public L1Cache.CacheStats getOrganizationL1Stats() {
        return organizationL1Cache.getStats();
    }

    public L1Cache.CacheStats getRoleL1Stats() {
        return roleL1Cache.getStats();
    }

    public L1Cache.CacheStats getPrivilegeL1Stats() {
        return privilegeL1Cache.getStats();
    }
}
