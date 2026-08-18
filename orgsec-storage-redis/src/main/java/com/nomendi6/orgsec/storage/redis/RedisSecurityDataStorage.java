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
import com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotCoordinator;
import com.nomendi6.orgsec.storage.redis.resilience.RedisStorageMigrationRequiredException;
import com.nomendi6.orgsec.storage.redis.resilience.RedisStorageNotReadyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
    /** Party-role cache; kept under the historic field name for binary/source continuity. */
    private final L1Cache<Long, RoleDef> roleL1Cache;
    private final L1Cache<Long, RoleDef> positionRoleL1Cache;
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

    // Managed 1.1 instances wait for the library-owned snapshot coordinator.
    private final boolean managedSnapshotProtocol;
    private volatile RedisSnapshotCoordinator snapshotCoordinator;

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

        this(
            properties,
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            new L1Cache<>(Math.max(1, roleL1Cache.getMaxSize())),
            privilegeL1Cache,
            personL2Cache,
            organizationL2Cache,
            roleL2Cache,
            privilegeL2Cache,
            cacheKeyBuilder,
            invalidationPublisher,
            cacheWarmer
        );
    }

    /**
     * Constructor with independent party-role and position-role L1 caches.
     */
    public RedisSecurityDataStorage(
            RedisStorageProperties properties,
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            L1Cache<Long, RoleDef> roleL1Cache,
            L1Cache<Long, RoleDef> positionRoleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            L2RedisCache<PersonDef> personL2Cache,
            L2RedisCache<OrganizationDef> organizationL2Cache,
            L2RedisCache<RoleDef> roleL2Cache,
            L2RedisCache<PrivilegeDef> privilegeL2Cache,
            CacheKeyBuilder cacheKeyBuilder,
            InvalidationEventPublisher invalidationPublisher,
            CacheWarmer cacheWarmer) {

        this(
            properties,
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            positionRoleL1Cache,
            privilegeL1Cache,
            personL2Cache,
            organizationL2Cache,
            roleL2Cache,
            privilegeL2Cache,
            cacheKeyBuilder,
            invalidationPublisher,
            cacheWarmer,
            false
        );

        throw new RedisStorageMigrationRequiredException(
            "direct construction through a legacy <= 1.0.5 constructor is unsupported. "
                + "Register the 1.1 SecurityDatasetFenceStore and RedisSnapshotLoader and let "
                + "RedisStorageAutoConfiguration create the fence-enabled storage."
        );
    }

    /**
     * Creates the managed 1.1 data plane in its fail-closed startup state.
     *
     * <p>Package-private by design: applications provide the migration-gated SPI beans, but only
     * the library-owned coordinator may consume them and eventually publish a verified runtime
     * view. This factory cannot create a ready instance.</p>
     */
    static RedisSecurityDataStorage managedWaiting(
            RedisStorageProperties properties,
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            L1Cache<Long, RoleDef> roleL1Cache,
            L1Cache<Long, RoleDef> positionRoleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            L2RedisCache<PersonDef> personL2Cache,
            L2RedisCache<OrganizationDef> organizationL2Cache,
            L2RedisCache<RoleDef> roleL2Cache,
            L2RedisCache<PrivilegeDef> privilegeL2Cache,
            CacheKeyBuilder cacheKeyBuilder,
            InvalidationEventPublisher invalidationPublisher,
            CacheWarmer cacheWarmer) {

        return new RedisSecurityDataStorage(
            properties,
            personL1Cache,
            organizationL1Cache,
            roleL1Cache,
            positionRoleL1Cache,
            privilegeL1Cache,
            personL2Cache,
            organizationL2Cache,
            roleL2Cache,
            privilegeL2Cache,
            cacheKeyBuilder,
            invalidationPublisher,
            cacheWarmer,
            true
        );
    }

    private RedisSecurityDataStorage(
            RedisStorageProperties properties,
            L1Cache<Long, PersonDef> personL1Cache,
            L1Cache<Long, OrganizationDef> organizationL1Cache,
            L1Cache<Long, RoleDef> roleL1Cache,
            L1Cache<Long, RoleDef> positionRoleL1Cache,
            L1Cache<String, PrivilegeDef> privilegeL1Cache,
            L2RedisCache<PersonDef> personL2Cache,
            L2RedisCache<OrganizationDef> organizationL2Cache,
            L2RedisCache<RoleDef> roleL2Cache,
            L2RedisCache<PrivilegeDef> privilegeL2Cache,
            CacheKeyBuilder cacheKeyBuilder,
            InvalidationEventPublisher invalidationPublisher,
            CacheWarmer cacheWarmer,
            boolean managedSnapshotProtocol) {

        this.properties = properties;
        this.personL1Cache = personL1Cache;
        this.organizationL1Cache = organizationL1Cache;
        this.roleL1Cache = roleL1Cache;
        this.positionRoleL1Cache = positionRoleL1Cache;
        this.privilegeL1Cache = privilegeL1Cache;
        this.personL2Cache = personL2Cache;
        this.organizationL2Cache = organizationL2Cache;
        this.roleL2Cache = roleL2Cache;
        this.privilegeL2Cache = privilegeL2Cache;
        this.cacheKeyBuilder = cacheKeyBuilder;
        this.invalidationPublisher = invalidationPublisher;
        this.cacheWarmer = cacheWarmer;
        this.managedSnapshotProtocol = managedSnapshotProtocol;
    }

    /**
     * Attaches the library-owned snapshot coordinator and bootstraps the local authorization view.
     */
    public void attachSnapshotCoordinator(RedisSnapshotCoordinator coordinator) {
        this.snapshotCoordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        coordinator.onReadinessChanged(() -> ready.set(coordinator.isReady()));
        ready.set(coordinator.isReady());
    }

    // ========== GET OPERATIONS ==========

    @Override
    public PersonDef getPerson(Long personId) {
        if (managedSnapshotProtocol) {
            return snapshotCoordinator == null ? null : snapshotCoordinator.person(personId);
        }
        if (denyReadWhileWaiting("getPerson")) {
            return null;
        }
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
        if (managedSnapshotProtocol) {
            return snapshotCoordinator == null ? null : snapshotCoordinator.organization(orgId);
        }
        if (denyReadWhileWaiting("getOrganization")) {
            return null;
        }
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
        return getRole(roleId, roleL1Cache, true);
    }

    @Override
    public RoleDef getPositionRole(Long roleId) {
        return getRole(roleId, positionRoleL1Cache, false);
    }

    /**
     * Gets a typed role, falling back to the 1.0.4 untyped Redis key during rolling upgrades.
     * A legacy hit is copied into the typed namespace so subsequent reads are unambiguous.
     */
    private RoleDef getRole(
        Long roleId,
        L1Cache<Long, RoleDef> localCache,
        boolean partyRole
    ) {
        if (managedSnapshotProtocol) {
            if (snapshotCoordinator == null) {
                return null;
            }
            return partyRole
                ? snapshotCoordinator.partyRole(roleId)
                : snapshotCoordinator.positionRole(roleId);
        }
        if (denyReadWhileWaiting(partyRole ? "getPartyRole" : "getPositionRole")) {
            return null;
        }
        if (roleId == null) {
            return null;
        }

        // L1 cache hit
        RoleDef role = localCache.get(roleId);
        if (role != null) {
            log.debug("L1 cache hit for {} role: {}", partyRole ? "party" : "position", roleId);
            return role;
        }

        String typedKey = partyRole
            ? cacheKeyBuilder.buildPartyRoleKey(roleId)
            : cacheKeyBuilder.buildPositionRoleKey(roleId);
        role = roleL2Cache.get(typedKey);
        if (role != null) {
            log.debug("L2 cache hit for {} role: {}", partyRole ? "party" : "position", roleId);
            localCache.put(roleId, role);
            return role;
        }

        String legacyKey = cacheKeyBuilder.buildRoleKey(roleId);
        role = roleL2Cache.get(legacyKey);
        if (role != null) {
            log.debug("Legacy L2 cache hit for {} role: {}", partyRole ? "party" : "position", roleId);
            localCache.put(roleId, role);
            roleL2Cache.set(typedKey, role, properties.getTtl().getRole());
            return role;
        }

        log.debug("Cache miss for {} role: {}", partyRole ? "party" : "position", roleId);
        return null;
    }

    @Override
    public PrivilegeDef getPrivilege(String privilegeIdentifier) {
        if (managedSnapshotProtocol) {
            return snapshotCoordinator == null
                ? null
                : snapshotCoordinator.privilege(privilegeIdentifier);
        }
        if (denyReadWhileWaiting("getPrivilege")) {
            return null;
        }
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
        requireReadyForMutation("updatePerson");
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
        requireReadyForMutation("updateOrganization");
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
        requireReadyForMutation("updateRole");
        if (roleId == null || role == null) {
            return;
        }

        log.warn(
            "Updating legacy untyped role {} in party, position, and 1.0.4 namespaces; " +
                "prefer updatePartyRole or updatePositionRole",
            roleId
        );
        roleL1Cache.put(roleId, role);
        positionRoleL1Cache.put(roleId, role);
        long ttl = properties.getTtl().getRole();
        roleL2Cache.set(cacheKeyBuilder.buildPartyRoleKey(roleId), role, ttl);
        roleL2Cache.set(cacheKeyBuilder.buildPositionRoleKey(roleId), role, ttl);
        roleL2Cache.set(cacheKeyBuilder.buildRoleKey(roleId), role, ttl);
        invalidationPublisher.publishRoleChanged(roleId);
    }

    @Override
    public void updatePartyRole(Long roleId, RoleDef role) {
        requireReadyForMutation("updatePartyRole");
        updateTypedRole(roleId, role, roleL1Cache, true);
    }

    @Override
    public void updatePositionRole(Long roleId, RoleDef role) {
        requireReadyForMutation("updatePositionRole");
        updateTypedRole(roleId, role, positionRoleL1Cache, false);
    }

    private void updateTypedRole(
        Long roleId,
        RoleDef role,
        L1Cache<Long, RoleDef> localCache,
        boolean partyRole
    ) {
        if (roleId == null || role == null) {
            return;
        }

        log.debug("Updating {} role: {}", partyRole ? "party" : "position", roleId);
        localCache.put(roleId, role);
        long ttl = properties.getTtl().getRole();
        String typedKey = partyRole
            ? cacheKeyBuilder.buildPartyRoleKey(roleId)
            : cacheKeyBuilder.buildPositionRoleKey(roleId);
        roleL2Cache.set(typedKey, role, ttl);
        // Dual-write the 1.0.4 key while mixed-version application instances may coexist.
        roleL2Cache.set(cacheKeyBuilder.buildRoleKey(roleId), role, ttl);
        invalidationPublisher.publishRoleChanged(roleId);
    }

    @Override
    public void updatePrivilege(String privilegeIdentifier, PrivilegeDef privilege) {
        requireReadyForMutation("updatePrivilege");
        if (privilegeIdentifier == null || privilegeIdentifier.isBlank() || privilege == null) {
            return;
        }

        log.debug("Updating privilege: {}", privilegeIdentifier);
        privilegeL1Cache.put(privilegeIdentifier, privilege);
        String key = cacheKeyBuilder.buildPrivilegeKey(privilegeIdentifier);
        long ttl = properties.getTtl().getPrivilege();
        privilegeL2Cache.set(key, privilege, ttl);
        // Privilege IDs are strings but the 1.x invalidation payload carries only Long IDs.
        invalidationPublisher.publishSecurityRefresh();
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
        if (managedSnapshotProtocol) {
            return snapshotCoordinator == null
                ? Map.of()
                : snapshotCoordinator.persons(personIds);
        }
        if (denyReadWhileWaiting("getPersons")) {
            return Map.of();
        }
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
        requireReadyForMutation("updatePersons");
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
        if (managedSnapshotProtocol) {
            return snapshotCoordinator == null
                ? Map.of()
                : snapshotCoordinator.organizations(orgIds);
        }
        if (denyReadWhileWaiting("getOrganizations")) {
            return Map.of();
        }
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
        requireReadyForMutation("updateOrganizations");
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
        if (denyReadWhileWaiting("getRoles")) {
            return Map.of();
        }
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
        requireReadyForMutation("updateRoles");
        if (roles == null || roles.isEmpty()) {
            return;
        }

        Map<String, RoleDef> cacheEntries = new HashMap<>();
        for (Map.Entry<Long, RoleDef> entry : roles.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                roleL1Cache.put(entry.getKey(), entry.getValue());
                positionRoleL1Cache.put(entry.getKey(), entry.getValue());
                cacheEntries.put(cacheKeyBuilder.buildPartyRoleKey(entry.getKey()), entry.getValue());
                cacheEntries.put(cacheKeyBuilder.buildPositionRoleKey(entry.getKey()), entry.getValue());
                cacheEntries.put(cacheKeyBuilder.buildRoleKey(entry.getKey()), entry.getValue());
            }
        }

        if (!cacheEntries.isEmpty()) {
            long ttl = properties.getTtl().getRole();
            roleL2Cache.multiSet(cacheEntries, ttl);
        }

        log.debug("Batch update roles: {} entries stored", cacheEntries.size());
    }

    /**
     * Stores multiple party roles in the typed namespace and in the 1.0.4 compatibility namespace.
     *
     * @param roles map of party-role ID to RoleDef
     */
    public void updatePartyRoles(Map<Long, RoleDef> roles) {
        requireReadyForMutation("updatePartyRoles");
        updateTypedRoles(roles, roleL1Cache, true);
    }

    /**
     * Stores multiple position roles in the typed namespace and in the 1.0.4 compatibility namespace.
     *
     * @param roles map of position-role ID to RoleDef
     */
    public void updatePositionRoles(Map<Long, RoleDef> roles) {
        requireReadyForMutation("updatePositionRoles");
        updateTypedRoles(roles, positionRoleL1Cache, false);
    }

    private void updateTypedRoles(
        Map<Long, RoleDef> roles,
        L1Cache<Long, RoleDef> localCache,
        boolean partyRole
    ) {
        if (roles == null || roles.isEmpty()) {
            return;
        }

        Map<String, RoleDef> cacheEntries = new HashMap<>();
        int storedRoles = 0;
        for (Map.Entry<Long, RoleDef> entry : roles.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                localCache.put(entry.getKey(), entry.getValue());
                String typedKey = partyRole
                    ? cacheKeyBuilder.buildPartyRoleKey(entry.getKey())
                    : cacheKeyBuilder.buildPositionRoleKey(entry.getKey());
                cacheEntries.put(typedKey, entry.getValue());
                cacheEntries.put(cacheKeyBuilder.buildRoleKey(entry.getKey()), entry.getValue());
                storedRoles++;
            }
        }

        if (!cacheEntries.isEmpty()) {
            roleL2Cache.multiSet(cacheEntries, properties.getTtl().getRole());
        }

        log.debug(
            "Batch update {} roles: {} entries stored",
            partyRole ? "party" : "position",
            storedRoles
        );
    }

    // ========== LIFECYCLE OPERATIONS ==========

    @Override
    public void initialize() {
        log.info("Initializing RedisSecurityDataStorage...");

        if (managedSnapshotProtocol) {
            ready.set(false);
            clearLocalCaches();
            log.info(
                "RedisSecurityDataStorage is WAITING_FOR_LOADER; "
                    + "the snapshot coordinator must verify and publish a complete snapshot"
            );
            return;
        }

        // Configure cache warmer with batch store callbacks
        cacheWarmer.setPersonBatchStore(this::updatePersons);
        cacheWarmer.setOrganizationBatchStore(this::updateOrganizations);
        cacheWarmer.setRoleBatchStore(this::updateRoles);
        cacheWarmer.setPartyRoleBatchStore(this::updatePartyRoles);
        cacheWarmer.setPositionRoleBatchStore(this::updatePositionRoles);

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
        if (managedSnapshotProtocol) {
            if (snapshotCoordinator == null) {
                throw new RedisStorageNotReadyException("refresh");
            }
            snapshotCoordinator.refresh();
            ready.set(snapshotCoordinator.isReady());
            return;
        }
        requireReadyForMutation("refresh");
        log.info("Refreshing RedisSecurityDataStorage...");

        clearLocalCaches();

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

    /**
     * Clears every process-local (L1) OrgSec cache without touching Redis (L2).
     */
    public void clearLocalCaches() {
        personL1Cache.clear();
        organizationL1Cache.clear();
        roleL1Cache.clear();
        positionRoleL1Cache.clear();
        privilegeL1Cache.clear();
    }

    @Override
    public boolean isReady() {
        if (managedSnapshotProtocol && snapshotCoordinator != null) {
            return snapshotCoordinator.isReady();
        }
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
        if (refreshManagedSnapshot("notifyPartyRoleChanged")) {
            return;
        }
        requireReadyForMutation("notifyPartyRoleChanged");
        log.debug("Redis storage notified: party role {} changed - invalidating cache", roleId);

        // Delete the shared value before dropping the local copy. Otherwise this process and
        // peers that receive the Pub/Sub event immediately refill L1 from the revoked L2 value.
        roleL2Cache.multiDelete(List.of(
            cacheKeyBuilder.buildPartyRoleKey(roleId),
            cacheKeyBuilder.buildRoleKey(roleId)
        ));
        roleL1Cache.invalidate(roleId);

        // Tell peers to drop their process-local copy after the shared value is gone.
        invalidationPublisher.publishRoleChanged(roleId);
    }

    /**
     * Notify that position role data has changed - invalidate cache
     */
    @Override
    public void notifyPositionRoleChanged(Long roleId) {
        if (refreshManagedSnapshot("notifyPositionRoleChanged")) {
            return;
        }
        requireReadyForMutation("notifyPositionRoleChanged");
        log.debug("Redis storage notified: position role {} changed - invalidating cache", roleId);

        roleL2Cache.multiDelete(List.of(
            cacheKeyBuilder.buildPositionRoleKey(roleId),
            cacheKeyBuilder.buildRoleKey(roleId)
        ));
        positionRoleL1Cache.invalidate(roleId);

        // Publish distributed invalidation event
        invalidationPublisher.publishRoleChanged(roleId);
    }

    /**
     * Notify that organization data has changed - invalidate cache
     */
    @Override
    public void notifyOrganizationChanged(Long orgId) {
        if (refreshManagedSnapshot("notifyOrganizationChanged")) {
            return;
        }
        requireReadyForMutation("notifyOrganizationChanged");
        log.debug("Redis storage notified: organization {} changed - invalidating cache", orgId);

        organizationL2Cache.delete(cacheKeyBuilder.buildOrganizationKey(orgId));
        organizationL1Cache.invalidate(orgId);

        // Publish distributed invalidation event
        invalidationPublisher.publishOrganizationChanged(orgId);
    }

    /**
     * Notify that person data has changed - invalidate cache
     */
    @Override
    public void notifyPersonChanged(Long personId) {
        if (refreshManagedSnapshot("notifyPersonChanged")) {
            return;
        }
        requireReadyForMutation("notifyPersonChanged");
        log.debug("Redis storage notified: person {} changed - invalidating cache", personId);

        personL2Cache.delete(cacheKeyBuilder.buildPersonKey(personId));
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

    public L1Cache.CacheStats getPositionRoleL1Stats() {
        return positionRoleL1Cache.getStats();
    }

    public L1Cache.CacheStats getPrivilegeL1Stats() {
        return privilegeL1Cache.getStats();
    }

    private boolean denyReadWhileWaiting(String operation) {
        if (managedSnapshotProtocol && !isReady()) {
            log.debug("Denying Redis {} while the verified snapshot is not READY", operation);
            return true;
        }
        return false;
    }

    private boolean refreshManagedSnapshot(String operation) {
        if (!managedSnapshotProtocol) {
            return false;
        }
        if (snapshotCoordinator == null) {
            throw new RedisStorageNotReadyException(operation);
        }
        snapshotCoordinator.refresh();
        ready.set(snapshotCoordinator.isReady());
        if (!snapshotCoordinator.isReady()) {
            throw new RedisStorageNotReadyException(operation);
        }
        return true;
    }

    private void requireReadyForMutation(String operation) {
        if (managedSnapshotProtocol) {
            throw new RedisStorageNotReadyException(operation);
        }
    }
}
