package com.nomendi6.orgsec.storage.jwt;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nomendi6.orgsec.helper.PrivilegeSecurityHelper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    // Per-request cache for enriched PersonDef (keyed by token hash)
    private final Map<Integer, PersonDef> personCache = new ConcurrentHashMap<>();

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
        int tokenHash = token.hashCode();

        return personCache.computeIfAbsent(tokenHash, hash -> {
            log.debug("Parsing and enriching PersonDef from JWT token");
            PersonDef person = claimsParser.parsePersonFromToken(token);
            if (person != null) {
                return enrichPersonWithRoles(person, token);
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
                // Copy organization roles
                orgDef.organizationRolesSet.addAll(fullOrgDef.organizationRolesSet);
                // Copy business roles map from organization roles
                orgDef.businessRolesMap.putAll(fullOrgDef.businessRolesMap);
                log.debug("Copied {} business roles from organization {}", fullOrgDef.businessRolesMap.size(), orgId);
            } else {
                log.warn("Organization {} not found in delegate storage", orgId);
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
     * Clear the per-request person cache.
     * Should be called at the end of each request.
     */
    public void clearCache() {
        personCache.clear();
    }
}
