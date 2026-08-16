package com.nomendi6.orgsec.storage.jwt;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nomendi6.orgsec.helper.PrivilegeSecurityHelper;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
     * The cached value is the <em>enriched</em> principal: {@code enrichPersonWithRoles} takes the
     * organization name and both hierarchy anchors from the delegate storage, resolves the claimed position
     * roles through it, and drops any membership the delegate does not confirm. None of that comes from the
     * token, so every one of those decisions goes stale when the delegate changes - and the anchors decide
     * hierarchy privileges directly, while a dropped membership can become a valid one again.
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
        // Party roles do not reach a JWT principal directly, but the delegate's view of the organization can
        // change with them, and that is what enrichment reads. See the personCache javadoc.
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
        // Clear before delegating: enrichment copies the delegate's name and both hierarchy anchors, and decides
        // whether the membership is confirmed at all, so a cached principal would keep the pre-change answer
        // indefinitely. See the personCache javadoc.
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
        JwtClaimsParser.ParsedPrincipal principal = claimsParser.parsePrincipalFromToken(token);
        return principal != null ? enrichPersonWithRoles(principal) : null;
    }

    /**
     * Turn a parsed claim into a principal the evaluator can decide on.
     *
     * <p>The claim asserts <em>which</em> organizations the person belongs to and which position
     * roles they hold there. It is not evidence that those organizations exist, that they belong to
     * the company it names, or where they sit in the hierarchy - a token is issued once and then
     * carried around, while the organization graph keeps changing. The delegate storage is the
     * authority for all of that, so every membership is confirmed against it before it is allowed to
     * grant anything.
     *
     * <p>A membership the delegate does not confirm is <strong>removed</strong>, not merely
     * weakened. Leaving the entry in place with cleared anchors still lets {@code EXACT} privileges
     * match on the organization id alone, which is the same unverified assertion in a quieter form.
     *
     * <p>Only two things are taken from the delegate: the organization's own identity (name,
     * hierarchy anchors) and the {@code RoleDef} bodies of the position roles the claim names. The
     * delegate's {@code organizationRolesSet} and {@code businessRolesMap} are deliberately
     * <em>not</em> copied - those are the roles the organization confers on its party members, which
     * this person may well not be. Copying them handed every token-authenticated principal the union
     * of everyone's privileges in that organization. Person-party grants are therefore not available
     * in JWT mode at all; that is a documented fail-closed limitation, not an oversight.
     */
    private PersonDef enrichPersonWithRoles(JwtClaimsParser.ParsedPrincipal principal) {
        PersonDef person = principal.person();
        if (person == null || person.organizationsMap == null) {
            return person;
        }

        Iterator<Map.Entry<Long, OrganizationDef>> memberships = person.organizationsMap.entrySet().iterator();
        while (memberships.hasNext()) {
            Map.Entry<Long, OrganizationDef> entry = memberships.next();
            Long orgId = entry.getKey();
            OrganizationDef orgDef = entry.getValue();

            OrganizationDef fullOrgDef = delegateStorage.getOrganization(orgId);
            if (fullOrgDef == null) {
                log.warn("Organization {} is not known to the delegate storage - dropping the claimed membership", orgId);
                memberships.remove();
                continue;
            }
            if (!Objects.equals(orgDef.companyId, fullOrgDef.companyId)) {
                // The claim names a different company than the delegate records for this
                // organization. Company-scoped privileges are evaluated against that value, so
                // trusting the token here would let a token pick its own tenant.
                log.warn(
                    "Claimed companyId {} for organization {} does not match the delegate ({}) - dropping the claimed membership",
                    orgDef.companyId, orgId, fullOrgDef.companyId
                );
                memberships.remove();
                continue;
            }

            orgDef.organizationName = fullOrgDef.organizationName;
            // Hierarchy anchors come from the delegate, which holds whatever path convention the
            // application maintains. Nothing in the claim can supply them: pathId names this node's
            // own segment and says nothing about its ancestry.
            orgDef.parentPath = fullOrgDef.parentPath;
            orgDef.companyParentPath = fullOrgDef.companyParentPath;

            List<Long> positionRoleIds = principal.positionRoleIdsByOrganization().getOrDefault(orgId, List.of());
            log.debug("Enriching organization {} with {} position roles", orgId, positionRoleIds.size());

            for (Long roleId : positionRoleIds) {
                RoleDef roleDef = delegateStorage.getPositionRole(roleId);
                if (roleDef != null) {
                    orgDef.addPositionRole(roleDef);
                } else {
                    log.warn("Position role {} not found in delegate storage", roleId);
                }
            }

            // Business roles are built from this person's position roles only.
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
