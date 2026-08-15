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

    // Bounded cache for enriched PersonDef keyed by full token value.
    private final Map<String, PersonDef> personCache = Collections.synchronizedMap(
        new LinkedHashMap<String, PersonDef>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PersonDef> eldest) {
                return size() > MAX_TOKEN_CACHE_SIZE;
            }
        }
    );

    public JwtSecurityDataStorage(
            JwtClaimsParser claimsParser,
            JwtTokenContextHolder tokenContextHolder,
            SecurityDataStorage delegateStorage) {
        this.claimsParser = claimsParser;
        this.tokenContextHolder = tokenContextHolder;
        this.delegateStorage = delegateStorage;
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
        log.debug("JWT storage notified: party role {} changed - delegating to delegate storage", roleId);
        delegateStorage.notifyPartyRoleChanged(roleId);
    }

    @Override
    public void notifyPositionRoleChanged(Long roleId) {
        log.debug("JWT storage notified: position role {} changed - delegating to delegate storage", roleId);
        delegateStorage.notifyPositionRoleChanged(roleId);
    }

    @Override
    public void notifyOrganizationChanged(Long orgId) {
        log.debug("JWT storage notified: organization {} changed - delegating to delegate storage", orgId);
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
        return personCache.computeIfAbsent(token, currentToken -> {
            log.debug("Parsing and enriching PersonDef from JWT token");
            PersonDef person = claimsParser.parsePersonFromToken(currentToken);
            if (person != null) {
                return enrichPersonWithRoles(person, currentToken);
            }
            return null;
        });
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
