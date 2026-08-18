package com.nomendi6.orgsec.common.service;

import static java.util.Arrays.asList;
import static com.nomendi6.orgsec.helper.RsqlHelper.addParenthases;
import static com.nomendi6.orgsec.helper.RsqlHelper.orRsql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.exceptions.OrgsecSecurityException;
import com.nomendi6.orgsec.helper.LineageBuilder;
import com.nomendi6.orgsec.helper.PathSanitizer;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.common.store.SecurityDataStore;

/**
 * Service responsible for building RSQL filters for privilege-based queries.
 * Handles filter generation for different privilege operations and business roles.
 */
@Component
public class RsqlFilterBuilder {

    private static final Logger log = LoggerFactory.getLogger(RsqlFilterBuilder.class);
    private static final String ALL_GRANT_SENTINEL = "__ORGSEC_ALL_GRANT__";

    /** Separator of the materialized organization path, as enforced by {@link PathSanitizer}. */
    private static final char PATH_SEPARATOR = '|';

    private final SecurityDataStore securityDataStore;
    private final BusinessRoleConfiguration businessRoleConfiguration;

    public RsqlFilterBuilder(SecurityDataStore securityDataStore, BusinessRoleConfiguration businessRoleConfiguration) {
        this.securityDataStore = securityDataStore;
        this.businessRoleConfiguration = businessRoleConfiguration;
    }

    /**
     * Returns rsql filter to add to sql query that will apply privilege security
     * If the user does not have any appropriate privilege the {@link AccessDeniedException} will be thrown.
     *
     * @param resourceName The resource name the entityDTO belongs to.
     * @param parentField is the name of the parent field for entity that depends on parent privileges
     * @param allowedBusinessRoles list of allowed business roles to check
     * @param operation the privilege operation to check for
     * @param currentPerson the current person to build filter for
     * @return The {@link String} with a rsql filter
     * @throws AccessDeniedException if no privilege exists
     */
    public String buildRsqlFilterForPrivileges(
        String resourceName,
        String parentField,
        List<String> allowedBusinessRoles,
        PrivilegeOperation operation,
        PersonData currentPerson
    ) throws AccessDeniedException {
        if (currentPerson == null) {
            throw new AccessDeniedException("Insufficient privileges for READ operation on resource: " + resourceName);
        }

        PersonDef personDef = securityDataStore.getPerson(currentPerson.getId());
        if (personDef == null) {
            throw new AccessDeniedException("Insufficient privileges for READ operation on resource: " + resourceName);
        }

        RsqlFilterContext context = new RsqlFilterContext(resourceName, parentField, operation, allowedBusinessRoles);
        return buildFilterForPersonDef(personDef, currentPerson, context);
    }

    /**
     * Build RSQL filter for read privileges
     */
    public String buildRsqlFilterForReadPrivileges(String resourceName, String parentField, PersonData currentPerson)
        throws AccessDeniedException {
        String filter = buildRsqlFilterForPrivileges(resourceName, parentField, null, PrivilegeOperation.READ, currentPerson);
        String result = filter.length() > 0 ? addParenthases(filter) : "";
        log.debug("Built RSQL filter for resource '{}': '{}'", resourceName, result);
        return result;
    }

    /**
     * Build RSQL filter for write privileges
     */
    public String buildRsqlFilterForWritePrivileges(String resourceName, String parentField, PersonData currentPerson)
        throws AccessDeniedException {
        String filter = buildRsqlFilterForPrivileges(resourceName, parentField, null, PrivilegeOperation.WRITE, currentPerson);
        return filter.length() > 0 ? addParenthases(filter) : "";
    }

    /**
     * Build RSQL filter for basic privileges (owner role only)
     */
    public String buildRsqlFilterForBasicPrivileges(String resourceName, String parentField, PersonData currentPerson)
        throws AccessDeniedException {
        return buildRsqlFilterForPrivileges(resourceName, parentField, asList("owner"), PrivilegeOperation.READ, currentPerson);
    }

    private String buildFilterForPersonDef(PersonDef personDef, PersonData currentPerson, RsqlFilterContext context)
        throws AccessDeniedException {
        // Atomic clauses are collected rather than OR-ed as they are produced, so that identical
        // clauses coming from different organizations or business roles are emitted once and a
        // subtree clause can absorb the narrower subtrees it already covers.
        Set<String> clauses = new LinkedHashSet<>();
        boolean hasPrivilege = false;

        for (Map.Entry<Long, OrganizationDef> entry : personDef.organizationsMap.entrySet()) {
            OrganizationDef organizationDef = entry.getValue();

            if (organizationDef.businessRolesMap != null && !organizationDef.businessRolesMap.isEmpty()) {
                for (Map.Entry<String, BusinessRoleDef> roleEntry : organizationDef.businessRolesMap.entrySet()) {
                    String businessRoleName = roleEntry.getKey();
                    BusinessRoleDef businessRoleDef = roleEntry.getValue();

                    // Honour the caller's restriction. Without this the parameter was accepted and
                    // ignored, so a query scoped to one business role still evaluated every role the
                    // principal held - and a broader privilege on an unrelated role produced an
                    // unfiltered result.
                    if (!context.allows(businessRoleName)) {
                        continue;
                    }

                    if (businessRoleDef.resourcesMap != null && !businessRoleDef.resourcesMap.isEmpty()) {
                        String roleFilter = buildFilterForBusinessRole(
                            businessRoleName,
                            businessRoleDef,
                            currentPerson,
                            organizationDef,
                            context,
                            clauses
                        );

                        if (roleFilter != null) {
                            hasPrivilege = true;
                            if (ALL_GRANT_SENTINEL.equals(roleFilter)) {
                                // Empty filter means 'all' privilege - no filtering needed
                                return "";
                            }
                        }
                    }
                }
            }
        }

        if (!hasPrivilege) {
            throw new AccessDeniedException(
                "Insufficient privileges for " + context.operation.name() + " operation on resource: " + context.resourceName
            );
        }

        String filter = "";
        for (String clause : removeSubsumedSubtrees(clauses)) {
            filter = orRsql(filter, clause);
        }
        return filter;
    }

    /**
     * Drops every subtree clause already covered by a broader subtree clause on the same selector.
     * <p>
     * A principal assigned at {@code |A|} and again at {@code |A|B|} produces
     * {@code orgPath=^*'|A|*'} and {@code orgPath=^*'|A|B|*'}; the first matches everything the
     * second does, so keeping both only widens the query plan. Paths are rooted and every segment
     * ends with the separator, so "covers" is plain prefix containment and {@code |A|} cannot
     * absorb {@code |AX|}.
     * <p>
     * Only the subtree form is folded. EXACT, ancestor ({@code =in=}) and person clauses are left
     * untouched: their sets are not nested in a way a string prefix can decide.
     */
    private static Collection<String> removeSubsumedSubtrees(Collection<String> clauses) {
        List<SubtreeClause> subtrees = new ArrayList<>();
        for (String clause : clauses) {
            SubtreeClause parsed = SubtreeClause.parse(clause);
            if (parsed != null) {
                subtrees.add(parsed);
            }
        }
        if (subtrees.size() < 2) {
            return clauses;
        }

        List<String> kept = new ArrayList<>(clauses.size());
        for (String clause : clauses) {
            SubtreeClause parsed = SubtreeClause.parse(clause);
            if (parsed == null || !parsed.isCoveredByAnyOf(subtrees)) {
                kept.add(clause);
            }
        }
        return kept;
    }

    /** A {@code selector=^*'path*'} clause, split so subtree containment can be decided on the path. */
    private static final class SubtreeClause {

        private static final String OPERATOR = "=^*'";

        final String selector;
        final String path;

        private SubtreeClause(String selector, String path) {
            this.selector = selector;
            this.path = path;
        }

        static SubtreeClause parse(String clause) {
            if (clause == null) {
                return null;
            }
            int operatorAt = clause.indexOf(OPERATOR);
            if (operatorAt < 0 || !clause.endsWith("*'")) {
                return null;
            }
            String path = clause.substring(operatorAt + OPERATOR.length(), clause.length() - 2);
            if (path.isEmpty() || path.indexOf('\'') >= 0) {
                return null;
            }
            return new SubtreeClause(clause.substring(0, operatorAt), path);
        }

        boolean isCoveredByAnyOf(Collection<SubtreeClause> others) {
            for (SubtreeClause other : others) {
                if (other != this && other.covers(this)) {
                    return true;
                }
            }
            return false;
        }

        /** True when this clause matches everything {@code narrower} matches, and more. */
        private boolean covers(SubtreeClause narrower) {
            return selector.equals(narrower.selector)
                && narrower.path.length() > path.length()
                && narrower.path.startsWith(path);
        }
    }

    /**
     * Evaluates one business role and appends its atomic clauses to {@code sink}.
     *
     * @return {@code null} when the role grants nothing, {@link #ALL_GRANT_SENTINEL} when it grants
     *     everything, or the empty string when it granted something and the clauses were appended.
     */
    private String buildFilterForBusinessRole(
        String businessRoleName,
        BusinessRoleDef businessRoleDef,
        PersonData currentPerson,
        OrganizationDef organizationDef,
        RsqlFilterContext context,
        Collection<String> sink
    ) {
        ResourceDef resourceDef = businessRoleDef.resourcesMap.get(context.resourceName);
        if (resourceDef == null) {
            return null;
        }

        // The decision is taken from the privileges list alone. The aggregate is a single PrivilegeDef
        // and cannot express "HIERARCHY_DOWN OR HIERARCHY_UP" (subtree or ancestors), so a role
        // holding both would be summarized into one direction and lose rows. Consulting it first
        // would also reintroduce that loss: it could grant on `all` before the list is read, or - as
        // the per-record path did - deny on an operation mismatch, so the two authorization paths
        // could answer differently for the same data.
        List<PrivilegeDef> privileges = resourceDef.getPrivilegesList();
        if (privileges == null || privileges.isEmpty()) {
            // Compatibility fallback for the 1.x line. Every path inside the library populates the
            // list, so an absent one means the caller built the ResourceDef by hand and set only the
            // aggregate - which was a supported way to express a privilege before 1.0.4. Falling back
            // keeps that code working on a minor release; the aggregate is lossy, so a role holding
            // two hierarchy directions still cannot be expressed this way. Removed in 2.x, where the
            // empty-list case denies.
            PrivilegeDef aggregate = getPrivilegeDefForOperation(resourceDef, context.operation);
            if (aggregate == null || !matchesOperation(aggregate, context.operation)) {
                return null;
            }
            log.warn(
                "Resource '{}' has an empty privilegesList; falling back to the aggregated privilege. " +
                "Populate ResourceDef.privilegesList - this fallback is removed in 2.x.",
                context.resourceName
            );
            if (aggregate.all) {
                return ALL_GRANT_SENTINEL;
            }
            String fallback = buildRsqlForOrganizationalPrivilege(
                currentPerson,
                organizationDef,
                aggregate,
                businessRoleName,
                context.parentField
            );
            if (fallback == null || fallback.isEmpty()) {
                return null;
            }
            sink.add(fallback);
            return "";
        }

        boolean granted = false;
        for (PrivilegeDef privilege : privileges) {
            if (!matchesOperation(privilege, context.operation)) {
                continue;
            }
            if (privilege.all) {
                return ALL_GRANT_SENTINEL;
            }

            String clause = buildRsqlForOrganizationalPrivilege(
                currentPerson,
                organizationDef,
                privilege,
                businessRoleName,
                context.parentField
            );
            if (clause == null || clause.isEmpty()) {
                continue;
            }
            sink.add(clause);
            granted = true;
        }

        return granted ? "" : null;
    }

    /**
     * Mirrors {@link PrivilegeChecker#hasRequiredOperation(PrivilegeDef, PrivilegeOperation)}: a
     * WRITE privilege also satisfies a READ request.
     */
    private boolean matchesOperation(PrivilegeDef privilege, PrivilegeOperation requested) {
        if (privilege == null) {
            return false;
        }
        switch (requested) {
            case WRITE:
                return privilege.operation == PrivilegeOperation.WRITE;
            case READ:
                return privilege.operation == PrivilegeOperation.WRITE || privilege.operation == PrivilegeOperation.READ;
            case EXECUTE:
                return privilege.operation == PrivilegeOperation.EXECUTE;
            default:
                return false;
        }
    }

    /**
     * Aggregated privilege for an operation. Used only by the 1.0.x compatibility fallback above -
     * the authorization decision itself is taken from the privileges list.
     */
    private PrivilegeDef getPrivilegeDefForOperation(ResourceDef resourceDef, PrivilegeOperation operation) {
        switch (operation) {
            case READ:
                return resourceDef.getAggregatedReadPrivilege();
            case WRITE:
                return resourceDef.getAggregatedWritePrivilege();
            case EXECUTE:
                return resourceDef.getAggregatedExecutePrivilege();
            default:
                return null;
        }
    }

    /**
     * Build RSQL filter for organizational privilege
     */
    private String buildRsqlForOrganizationalPrivilege(
        PersonData currentPerson,
        OrganizationDef organizationDef,
        PrivilegeDef resourceAggregatedPrivs,
        String businessRoleName,
        String parentField
    ) {
        String alias = (parentField != null && !parentField.isEmpty()) ? parentField + "." : "";

        // Company level checks - only if business role supports COMPANY field
        if (resourceAggregatedPrivs.company != PrivilegeDirection.NONE &&
            businessRoleConfiguration.roleSupportsField(businessRoleName, SecurityFieldType.COMPANY)) {
            return buildCompanyFilter(alias, businessRoleName, organizationDef, resourceAggregatedPrivs.company);
        }

        // Organization level checks - only if business role supports ORG field
        if (resourceAggregatedPrivs.org != PrivilegeDirection.NONE &&
            businessRoleConfiguration.roleSupportsField(businessRoleName, SecurityFieldType.ORG)) {
            return buildOrgFilter(alias, businessRoleName, organizationDef, resourceAggregatedPrivs.org);
        }

        // Person level checks - only if business role supports PERSON field
        if (resourceAggregatedPrivs.person &&
            businessRoleConfiguration.roleSupportsField(businessRoleName, SecurityFieldType.PERSON)) {
            return selector(alias, businessRoleName, SecurityFieldType.PERSON) + "==" + currentPerson.getId();
        }

        return null;
    }

    private String buildCompanyFilter(
        String alias,
        String businessRoleName,
        OrganizationDef organizationDef,
        PrivilegeDirection direction
    ) {
        switch (direction) {
            case EXACT:
                if (organizationDef.companyId == null) {
                    // "selector==null" is not a deny - depending on the RSQL dialect it is either a
                    // parse error or a clause that matches every row whose column is null.
                    log.warn("Cannot build company EXACT RSQL filter: the principal has no companyId for organization {}",
                        organizationDef.organizationId);
                    return null;
                }
                return selector(alias, businessRoleName, SecurityFieldType.COMPANY) + "==" + organizationDef.companyId;
            case HIERARCHY_DOWN: {
                String anchor = anchorOrNull(organizationDef.companyParentPath, "companyParentPath", organizationDef, direction);
                if (anchor == null) {
                    return null;
                }
                // Validate and escape path before using in RSQL
                String safeCompanyPath = PathSanitizer.escapeForRsql(anchor);
                return selector(alias, businessRoleName, SecurityFieldType.COMPANY_PATH) + "=^*'" + safeCompanyPath + "*'";
            }
            case HIERARCHY_UP: {
                if (businessRoleConfiguration.hierarchyUpUsesIds()) {
                    return buildLineageInClause(
                        selector(alias, businessRoleName, SecurityFieldType.COMPANY),
                        organizationDef.companyLineageIds,
                        organizationDef.companyId,
                        "companyLineageIds",
                        organizationDef
                    );
                }
                String anchor = anchorOrNull(organizationDef.companyParentPath, "companyParentPath", organizationDef, direction);
                if (anchor == null) {
                    return null;
                }
                // Ancestors are enumerated as an '=in=' list of path prefixes, not a suffix LIKE.
                String companyUpClause = buildAncestorInClause(
                    selector(alias, businessRoleName, SecurityFieldType.COMPANY_PATH),
                    anchor
                );
                if (companyUpClause == null) {
                    log.warn("Cannot build company hierarchy-up RSQL filter: no ancestor prefixes in path {} for organization {}",
                        organizationDef.companyParentPath, organizationDef.organizationId);
                    return null;
                }
                return companyUpClause;
            }
            default:
                // Unhandled direction (e.g. ALL, which is not a valid company/org scope - 'all' is a
                // separate flag). Returning "" would mark the privilege as present with no filter,
                // i.e. grant unfiltered access. Fail closed instead.
                log.warn("Unsupported company privilege direction {} - denying", direction);
                return null;
        }
    }

    /**
     * The principal's anchor for a hierarchical clause, or {@code null} when it cannot be used.
     *
     * <p>Validation is stricter than "is it non-empty": a path that does not end in the separator,
     * such as {@code |A|B}, produces the prefix pattern {@code |A|B*}, which also matches
     * {@code |A|BX|C|} - a different branch entirely. {@link PathSanitizer#validateHierarchyAnchor}
     * rejects that shape, and everything else the segment grammar forbids.
     *
     * <p>Failure is reported as a {@code null} clause, never as an empty string. An empty string is
     * how this builder spells "no filter needed" for an {@code all} grant, so returning it from a
     * validation failure would turn a deny into unfiltered access to the whole table.
     */
    private String anchorOrNull(
        String anchorPath,
        String anchorName,
        OrganizationDef organizationDef,
        PrivilegeDirection direction
    ) {
        try {
            return PathSanitizer.validateHierarchyAnchor(anchorPath);
        } catch (OrgsecSecurityException e) {
            log.warn(
                "Cannot build {} RSQL filter: {} is not a usable hierarchy anchor ({}) for organization {} - {}",
                direction, anchorName, anchorPath, organizationDef.organizationId, e.getMessage()
            );
            return null;
        }
    }

    private String buildOrgFilter(String alias, String businessRoleName, OrganizationDef organizationDef, PrivilegeDirection direction) {
        switch (direction) {
            case EXACT:
                if (organizationDef.organizationId == null) {
                    log.warn("Cannot build organization EXACT RSQL filter: the principal has no organizationId");
                    return null;
                }
                return selector(alias, businessRoleName, SecurityFieldType.ORG) + "==" + organizationDef.organizationId;
            case HIERARCHY_DOWN: {
                String anchor = anchorOrNull(organizationDef.parentPath, "parentPath", organizationDef, direction);
                if (anchor == null) {
                    return null;
                }
                // Validate and escape path before using in RSQL
                String safeOrgPath = PathSanitizer.escapeForRsql(anchor);
                return selector(alias, businessRoleName, SecurityFieldType.ORG_PATH) + "=^*'" + safeOrgPath + "*'";
            }
            case HIERARCHY_UP: {
                if (businessRoleConfiguration.hierarchyUpUsesIds()) {
                    return buildLineageInClause(
                        selector(alias, businessRoleName, SecurityFieldType.ORG),
                        organizationDef.orgLineageIds,
                        organizationDef.organizationId,
                        "orgLineageIds",
                        organizationDef
                    );
                }
                String anchor = anchorOrNull(organizationDef.parentPath, "parentPath", organizationDef, direction);
                if (anchor == null) {
                    return null;
                }
                // Ancestors are enumerated as an '=in=' list of path prefixes, not a suffix LIKE.
                String orgUpClause = buildAncestorInClause(
                    selector(alias, businessRoleName, SecurityFieldType.ORG_PATH),
                    anchor
                );
                if (orgUpClause == null) {
                    log.warn("Cannot build organization hierarchy-up RSQL filter: no ancestor prefixes in path {} for organization {}",
                        organizationDef.parentPath, organizationDef.organizationId);
                    return null;
                }
                return orgUpClause;
            }
            default:
                // See buildCompanyFilter: an unhandled direction must deny, not grant unfiltered.
                log.warn("Unsupported organization privilege direction {} - denying", direction);
                return null;
        }
    }

    /**
     * Builds the RSQL clause for HIERARCHY_UP (ancestors): the entity path must be a PREFIX of the
     * principal path, i.e. the entity is an ancestor of the principal or the principal itself.
     * <p>
     * This cannot be expressed with the RSQL LIKE operator {@code =*}. LIKE always makes the
     * <em>column</em> the target of the match ({@code col LIKE 'pattern'}), whereas ancestors need
     * the column on the pattern side ({@code 'principalPath' LIKE col || '%'}). The previous
     * {@code =*'*path'} form compiled to {@code col LIKE '%path'} - "ends with path" - and on rooted
     * paths with unique segment ids the only path ending in {@code |A|B|C|} is {@code |A|B|C|}
     * itself. The filter therefore matched the principal's own organization and no ancestor at all,
     * silently hiding rows that {@link PrivilegeChecker#checkOrgPrivilege} does grant on a
     * single-record read.
     * <p>
     * Ancestors are instead enumerated as every prefix of the path, each ending with the separator
     * so that {@code |A|} cannot match {@code |AX|}: {@code |A|B|C|} yields
     * {@code =in=('|A|','|A|B|','|A|B|C|')}, the principal itself included. RSQL {@code =in=} is an
     * exact, case-sensitive comparison, consistent with the EXACT direction.
     * <p>
     * The opposite direction needs no such treatment: a subtree IS expressible as a prefix pattern,
     * so HIERARCHY_DOWN uses {@code =^*'path*'} - the case-sensitive LIKE. The case-insensitive
     * {@code =*} compiles to {@code lower(col) LIKE ...}, which no index on the path column can
     * serve; {@code =^*} drops the {@code lower()} and makes the branch sargable. Paths are emitted
     * by the same encoder as the ids they contain, so the comparison is case-consistent with
     * {@code ==} and {@code =in=}.
     *
     * @param selectorExpr the already-built selector (alias plus field), e.g. {@code "doc.orgPath"}
     * @param parentPath the materialized organization path ({@code |seg|seg|})
     * @return {@code "selector=in=('p1','p2',...)"}, or {@code null} when the path holds no prefix
     */
    private String buildAncestorInClause(String selectorExpr, String parentPath) {
        // Validates the path format and escapes RSQL special characters. A valid path holds only
        // separators, alphanumerics and underscores, so escaping is a no-op on it and the separator
        // positions in safePath match the original - substring(0, i + 1) is a valid prefix path.
        String safePath = PathSanitizer.escapeForRsql(parentPath);

        StringBuilder elements = new StringBuilder();
        for (int i = 1; i < safePath.length(); i++) {
            if (safePath.charAt(i) == PATH_SEPARATOR) {
                if (elements.length() > 0) {
                    elements.append(',');
                }
                elements.append('\'').append(safePath, 0, i + 1).append('\'');
            }
        }
        if (elements.length() == 0) {
            return null;
        }
        return selectorExpr + "=in=(" + elements + ")";
    }

    private String buildLineageInClause(
        String selectorExpr,
        List<Long> lineage,
        Long selfId,
        String lineageName,
        OrganizationDef organizationDef
    ) {
        if (!LineageBuilder.isUsable(lineage, selfId)) {
            log.warn(
                "Cannot build hierarchy-up RSQL filter: {} is not a usable lineage for organization {}",
                lineageName,
                organizationDef.organizationId
            );
            return null;
        }
        StringBuilder elements = new StringBuilder();
        for (Long id : lineage) {
            if (elements.length() > 0) {
                elements.append(',');
            }
            elements.append(id);
        }
        return selectorExpr + "=in=(" + elements + ")";
    }

    private String selector(String alias, String businessRoleName, SecurityFieldType fieldType) {
        return alias + businessRoleConfiguration.getRsqlFieldSelector(businessRoleName, fieldType);
    }

    /**
     * Internal class to hold filter building context
     */
    private static class RsqlFilterContext {

        final String resourceName;
        final String parentField;
        final PrivilegeOperation operation;

        /**
         * Business roles the caller restricted the query to, or {@code null} for "no restriction".
         * An empty list denies: a caller that passes an empty restriction has asked for nothing.
         */
        final Set<String> allowedBusinessRoles;

        RsqlFilterContext(
            String resourceName,
            String parentField,
            PrivilegeOperation operation,
            List<String> allowedBusinessRoles
        ) {
            this.resourceName = resourceName;
            this.parentField = parentField;
            this.operation = operation;
            this.allowedBusinessRoles =
                allowedBusinessRoles == null
                    ? null
                    : allowedBusinessRoles.stream().filter(Objects::nonNull).map(role -> role.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        }

        /** Business roles are matched case-insensitively, as everywhere else in the library. */
        boolean allows(String businessRoleName) {
            if (allowedBusinessRoles == null) {
                return true;
            }
            return businessRoleName != null && allowedBusinessRoles.contains(businessRoleName.toLowerCase(Locale.ROOT));
        }
    }
}
