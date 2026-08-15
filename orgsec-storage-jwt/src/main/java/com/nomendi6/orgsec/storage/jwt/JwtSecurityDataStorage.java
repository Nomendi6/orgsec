package com.nomendi6.orgsec.storage.jwt;

import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nomendi6.orgsec.helper.PrivilegeSecurityHelper;
import org.apache.commons.lang3.SerializationUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * JWT-based SecurityDataStorage implementation.
 *
 * This is a hybrid storage that:
 * - Gets Person data from JWT token claims
 * - Delegates Organization, Role, and Privilege queries to a delegate storage (InMemory/Redis)
 *
 * The Person data is parsed from the current request's JWT token using JwtTokenContextHolder.
 */
public class JwtSecurityDataStorage implements SecurityDataStorage {

    private static final Logger log = LoggerFactory.getLogger(JwtSecurityDataStorage.class);

    private final JwtClaimsParser claimsParser;
    private final JwtTokenContextHolder tokenContextHolder;
    private final SecurityDataStorage delegateStorage;

    private static final int MAX_TOKEN_CACHE_SIZE = 1024;

    /**
     * Bounded cache for enriched PersonDef keyed by full token value.
     * <p>
     * The cached value is the <em>enriched</em> principal: {@code enrichPersonWithRoles} copies the
     * organization name, both hierarchy anchors, the organization roles and the business roles out of the
     * delegate storage into it. None of that comes from the token, so every one of those values goes stale
     * when the delegate changes - and the anchors decide hierarchy privileges directly.
     * <p>
     * Nothing evicts an entry on its own: the key is the whole token, which in an OIDC session flow is
     * stable for the life of the session, and the LRU bound is only reached by a thousand other principals
     * being seen more recently. So every {@code notifyXxxChanged} that can alter copied state has to clear
     * this map, or a cached principal keeps deciding on the state the delegate has already left behind.
     */
    private final Map<String, CachedPrincipal> personCache = Collections.synchronizedMap(
        new LinkedHashMap<String, CachedPrincipal>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedPrincipal> eldest) {
                return size() > MAX_TOKEN_CACHE_SIZE;
            }
        }
    );

    private final boolean cacheParsedPerson;
    private final long cacheTtlMillis;
    private final LongSupplier clock;

    /** An enriched principal together with the moment it was enriched, so {@code cacheTtlSeconds} can expire it. */
    private record CachedPrincipal(PersonDef person, long enrichedAtMillis) {}

    /**
     * Creates a storage whose cached principals never expire on their own.
     * <p>
     * Kept for callers that construct this class directly; the Spring path uses the constructor below so
     * that {@code orgsec.storage.jwt.cache-parsed-person} and {@code cache-ttl-seconds} take effect.
     */
    public JwtSecurityDataStorage(
            JwtClaimsParser claimsParser,
            JwtTokenContextHolder tokenContextHolder,
            SecurityDataStorage delegateStorage) {
        this(claimsParser, tokenContextHolder, delegateStorage, true, 0);
    }

    /**
     * @param cacheParsedPerson when false, every read re-parses and re-enriches; nothing is cached
     * @param cacheTtlSeconds how long an enriched principal may be reused; zero or less means no expiry, so
     *                        only the {@code notifyXxxChanged} methods invalidate it
     */
    public JwtSecurityDataStorage(
            JwtClaimsParser claimsParser,
            JwtTokenContextHolder tokenContextHolder,
            SecurityDataStorage delegateStorage,
            boolean cacheParsedPerson,
            int cacheTtlSeconds) {
        this(claimsParser, tokenContextHolder, delegateStorage, cacheParsedPerson, cacheTtlSeconds, System::currentTimeMillis);
    }

    JwtSecurityDataStorage(
            JwtClaimsParser claimsParser,
            JwtTokenContextHolder tokenContextHolder,
            SecurityDataStorage delegateStorage,
            boolean cacheParsedPerson,
            int cacheTtlSeconds,
            LongSupplier clock) {
        this.claimsParser = claimsParser;
        this.tokenContextHolder = tokenContextHolder;
        this.delegateStorage = delegateStorage;
        this.cacheParsedPerson = cacheParsedPerson;
        this.cacheTtlMillis = cacheTtlSeconds > 0 ? cacheTtlSeconds * 1000L : 0L;
        this.clock = clock;
    }

    // ========== GET OPERATIONS ==========

    @Override
    public PersonDef getPerson(Long personId) {
        String token = tokenContextHolder.getToken();
        if (token == null) {
            log.debug("No JWT token in context, cannot get person");
            return null;
        }

        PersonDef person = getEnrichedPersonFromToken(token);
        if (person != null && person.personId.equals(personId)) {
            return person;
        }

        log.debug("Person ID {} not found in JWT token (token has personId={})",
                personId, person != null ? person.personId : "null");
        return null;
    }

    /**
     * Get person by login from JWT token.
     * This is the primary method for getting the current user.
     */
    public PersonDef getPersonByLogin(String login) {
        String token = tokenContextHolder.getToken();
        if (token == null) {
            log.debug("No JWT token in context, cannot get person by login");
            return null;
        }

        PersonDef person = getEnrichedPersonFromToken(token);
        if (person != null && login.equals(person.relatedUserLogin)) {
            return person;
        }

        log.debug("Login {} not found in JWT token (token has login={})",
                login, person != null ? person.relatedUserLogin : "null");
        return null;
    }

    @Override
    public OrganizationDef getOrganization(Long orgId) {
        return delegateStorage.getOrganization(orgId);
    }

    @Override
    public RoleDef getPartyRole(Long roleId) {
        return delegateStorage.getPartyRole(roleId);
    }

    @Override
    public RoleDef getPositionRole(Long roleId) {
        return delegateStorage.getPositionRole(roleId);
    }

    @Override
    public PrivilegeDef getPrivilege(String privilegeIdentifier) {
        return delegateStorage.getPrivilege(privilegeIdentifier);
    }

    // ========== LIFECYCLE OPERATIONS ==========

    @Override
    public void initialize() {
        log.info("Initializing JWT security data storage...");
        delegateStorage.initialize();
        log.info("JWT security data storage initialized (delegate: {})", delegateStorage.getProviderType());
    }

    @Override
    public void refresh() {
        log.info("Refreshing JWT security data storage...");
        personCache.clear();
        delegateStorage.refresh();
        log.info("JWT security data storage refreshed");
    }

    @Override
    public boolean isReady() {
        return delegateStorage.isReady();
    }

    @Override
    public String getProviderType() {
        return "jwt";
    }

    // ========== NOTIFICATION OPERATIONS ==========

    @Override
    public void notifyPartyRoleChanged(Long roleId) {
        log.debug("JWT storage notified: party role {} changed - clearing enriched principals and delegating", roleId);
        // Clear before delegating: enrichment copies the delegate's organization roles and rebuilds business roles from them,
        // so a cached principal would keep the pre-change copy indefinitely. See the personCache javadoc.
        personCache.clear();
        delegateStorage.notifyPartyRoleChanged(roleId);
    }

    @Override
    public void notifyPositionRoleChanged(Long roleId) {
        log.debug("JWT storage notified: position role {} changed - clearing enriched principals and delegating", roleId);
        // Clear before delegating: enrichment resolves position roles through the delegate and merges them into the business roles,
        // so a cached principal would keep the pre-change copy indefinitely. See the personCache javadoc.
        personCache.clear();
        delegateStorage.notifyPositionRoleChanged(roleId);
    }

    @Override
    public void notifyOrganizationChanged(Long orgId) {
        log.debug("JWT storage notified: organization {} changed - clearing enriched principals and delegating", orgId);
        // Clear before delegating: enrichment copies the delegate's name, both hierarchy anchors and business roles,
        // so a cached principal would keep the pre-change copy indefinitely. See the personCache javadoc.
        personCache.clear();
        delegateStorage.notifyOrganizationChanged(orgId);
    }

    @Override
    public void notifyPersonChanged(Long personId) {
        log.debug("JWT storage notified: person {} changed - clearing cache", personId);
        personCache.clear();
    }

    // ========== PRIVATE METHODS ==========

    /**
     * Get enriched PersonDef from JWT token with caching.
     * The cache stores the already-enriched person (with roles loaded from delegate storage).
     */
    private PersonDef getEnrichedPersonFromToken(String token) {
        if (!cacheParsedPerson) {
            return parseAndEnrich(token);
        }
        CachedPrincipal cached = personCache.get(token);
        if (cached != null && !hasExpired(cached)) {
            return cached.person();
        }
        PersonDef person = parseAndEnrich(token);
        if (person != null) {
            personCache.put(token, new CachedPrincipal(person, clock.getAsLong()));
        } else {
            // Nothing to reuse, and leaving a stale entry behind would outlive the token that produced it.
            personCache.remove(token);
        }
        return person;
    }

    /**
     * Whether an entry is too old to reuse.
     * <p>
     * The TTL is a backstop, not the primary mechanism: the {@code notifyXxxChanged} methods invalidate on
     * the events the library knows about. It matters for the changes it does not see - a Redis delegate
     * whose data another instance updated, or an application that never publishes the notifications.
     */
    private boolean hasExpired(CachedPrincipal cached) {
        return cacheTtlMillis > 0 && clock.getAsLong() - cached.enrichedAtMillis() >= cacheTtlMillis;
    }

    private PersonDef parseAndEnrich(String token) {
        log.debug("Parsing and enriching PersonDef from JWT token");
        PersonDef person = claimsParser.parsePersonFromToken(token);
        return person != null ? enrichPersonWithRoles(person, token) : null;
    }

    /**
     * Enrich PersonDef with role information from delegate storage.
     *
     * The JWT token contains positionRoleIds, but we need full RoleDef objects
     * with privileges. These are loaded from the delegate storage.
     */
    private PersonDef enrichPersonWithRoles(PersonDef person, String token) {
        if (person == null || person.organizationsMap == null) {
            return person;
        }

        // For each organization in the person's memberships
        for (Map.Entry<Long, OrganizationDef> entry : person.organizationsMap.entrySet()) {
            Long orgId = entry.getKey();
            OrganizationDef orgDef = entry.getValue();

            // Get position role IDs from JWT claims
            List<Long> positionRoleIds = claimsParser.getPositionRoleIds(token, orgId);

            log.debug("Enriching organization {} with {} position roles", orgId, positionRoleIds.size());

            // Load full RoleDef objects from delegate storage
            for (Long roleId : positionRoleIds) {
                RoleDef roleDef = delegateStorage.getPositionRole(roleId);
                if (roleDef != null) {
                    orgDef.addPositionRole(roleDef);
                    log.debug("Added position role {} to organization {}", roleId, orgId);
                } else {
                    log.warn("Position role {} not found in delegate storage", roleId);
                }
            }

            // Get organization from delegate storage to copy organization roles
            OrganizationDef fullOrgDef = delegateStorage.getOrganization(orgId);
            if (fullOrgDef != null) {
                // Copy organization name
                orgDef.organizationName = fullOrgDef.organizationName;
                // Take the hierarchy anchors from the delegate, not from the token. The claim carries only
                // pathId, so JwtClaimsParser has to derive parentPath from it - and derives the STRICT
                // parent, one level above the full-path-of-this-node that every other backend stores. A
                // principal at |1|10|15| ended up anchored at |1|10|, so HIERARCHY_DOWN granted every
                // sibling subtree under org 10; for a root-level membership the derived anchor is "|",
                // which matches every path in the system. companyParentPath has no claim at all and stayed
                // null, so the company hierarchy comparisons dereferenced null.
                //
                // The delegate holds whatever path convention the application maintains, so copying keeps
                // JWT principals deciding the same way InMemory and Redis do for the same organization,
                // without this class having to know which convention that is.
                orgDef.parentPath = fullOrgDef.parentPath;
                orgDef.companyParentPath = fullOrgDef.companyParentPath;
                // Copy organization roles
                orgDef.organizationRolesSet.addAll(fullOrgDef.organizationRolesSet);
                // Deep-copy the business roles instead of sharing the delegate's instances. The
                // loop below merges this request's position roles into these objects, and for a
                // business role the delegate already carries that merge writes straight into the
                // delegate's own BusinessRoleDef - so one principal's privileges would end up in
                // state that every other principal of this organization reads.
                fullOrgDef.businessRolesMap.forEach((name, businessRole) ->
                    orgDef.businessRolesMap.put(name, copyBusinessRole(businessRole))
                );
                log.debug("Copied {} business roles from organization {}", fullOrgDef.businessRolesMap.size(), orgId);
            } else {
                // Fail closed. The only hierarchy anchor available here is the one derived from the token,
                // and the comment above says why it cannot be trusted. Clearing it makes PrivilegeChecker
                // deny hierarchical privileges for this organization instead of granting on a path this
                // class knows to be wrong by one level.
                orgDef.parentPath = null;
                orgDef.companyParentPath = null;
                log.warn(
                    "Organization {} not found in delegate storage - hierarchical privileges will be denied for it",
                    orgId
                );
            }

            // Build business roles from position roles (user's specific privileges)
            // This is crucial - position roles contain the user's actual privileges
            for (RoleDef roleDef : orgDef.positionRolesSet) {
                PrivilegeSecurityHelper.buildBusinessRoleResourceMap(orgDef.businessRolesMap, roleDef);
            }
            log.debug("Built business roles from {} position roles for organization {}, total business roles: {}",
                    orgDef.positionRolesSet.size(), orgId, orgDef.businessRolesMap.size());
        }

        return person;
    }

    /**
     * Copy a business role so that merging this request's roles into it cannot reach the delegate's
     * stored organization.
     * <p>
     * The copy has to go one level deeper than the map: {@code addResourceDefinition} mutates the
     * {@link com.nomendi6.orgsec.model.ResourceDef} instances inside {@code resourcesMap}, so
     * copying only the map would still share those. {@code ResourceDef} is {@link java.io.Serializable}
     * and is cloned the same way {@code PrivilegeSecurityHelper} clones it when it creates a new
     * business role.
     */
    private static BusinessRoleDef copyBusinessRole(BusinessRoleDef source) {
        BusinessRoleDef copy = new BusinessRoleDef(source.businessRoleName);
        copy.filter = source.filter;
        copy.allowAll = source.allowAll;
        if (source.resourcesMap != null) {
            source.resourcesMap.forEach((resourceName, resourceDef) ->
                copy.resourcesMap.put(resourceName, SerializationUtils.clone(resourceDef))
            );
        }
        return copy;
    }

    /**
     * Clear the per-request person cache.
     * Should be called at the end of each request.
     */
    public void clearCache() {
        personCache.clear();
    }
}
