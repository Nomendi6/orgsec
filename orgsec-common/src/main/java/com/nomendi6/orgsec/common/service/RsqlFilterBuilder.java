package com.nomendi6.orgsec.common.service;

import static java.util.Arrays.asList;
import static com.nomendi6.orgsec.helper.RsqlHelper.addParenthases;
import static com.nomendi6.orgsec.helper.RsqlHelper.orRsql;

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
        String filter = "";
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
                            context
                        );

                        if (roleFilter != null) {
                            hasPrivilege = true;
                            if (ALL_GRANT_SENTINEL.equals(roleFilter)) {
                                // Empty filter means 'all' privilege - no filtering needed
                                return "";
                            }
                            if (!roleFilter.isEmpty()) {
                                filter = orRsql(filter, roleFilter);
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

        return filter;
    }

    private String buildFilterForBusinessRole(
        String businessRoleName,
        BusinessRoleDef businessRoleDef,
        PersonData currentPerson,
        OrganizationDef organizationDef,
        RsqlFilterContext context
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
            // Fail closed. Every path inside the library populates the list; an absent one means the
            // caller built the ResourceDef by hand and set only the aggregate, which is no longer a
            // supported way to express a privilege.
            return null;
        }

        String filter = "";
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
            filter = orRsql(filter, clause);
        }

        return filter.isEmpty() ? null : filter;
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
                return selector(alias, businessRoleName, SecurityFieldType.COMPANY) + "==" + organizationDef.companyId;
            case HIERARCHY_DOWN:
                if (organizationDef.companyParentPath == null) {
                    log.warn("Cannot build company hierarchy-down RSQL filter: companyParentPath is null for organization {}",
                        organizationDef.organizationId);
                    return null;
                }
                // Validate and escape path before using in RSQL
                String safeCompanyPath = PathSanitizer.escapeForRsql(organizationDef.companyParentPath);
                return selector(alias, businessRoleName, SecurityFieldType.COMPANY_PATH) + "=*'" + safeCompanyPath + "*'";
            case HIERARCHY_UP:
                if (organizationDef.companyParentPath == null) {
                    log.warn("Cannot build company hierarchy-up RSQL filter: companyParentPath is null for organization {}",
                        organizationDef.organizationId);
                    return null;
                }
                // Ancestors are enumerated as an '=in=' list of path prefixes, not a suffix LIKE.
                String companyUpClause = buildAncestorInClause(
                    selector(alias, businessRoleName, SecurityFieldType.COMPANY_PATH),
                    organizationDef.companyParentPath
                );
                if (companyUpClause == null) {
                    log.warn("Cannot build company hierarchy-up RSQL filter: no ancestor prefixes in path {} for organization {}",
                        organizationDef.companyParentPath, organizationDef.organizationId);
                    return null;
                }
                return companyUpClause;
            default:
                // Unhandled direction (e.g. ALL, which is not a valid company/org scope - 'all' is a
                // separate flag). Returning "" would mark the privilege as present with no filter,
                // i.e. grant unfiltered access. Fail closed instead.
                log.warn("Unsupported company privilege direction {} - denying", direction);
                return null;
        }
    }

    private String buildOrgFilter(String alias, String businessRoleName, OrganizationDef organizationDef, PrivilegeDirection direction) {
        switch (direction) {
            case EXACT:
                return selector(alias, businessRoleName, SecurityFieldType.ORG) + "==" + organizationDef.organizationId;
            case HIERARCHY_DOWN:
                if (organizationDef.parentPath == null) {
                    log.warn("Cannot build organization hierarchy-down RSQL filter: parentPath is null for organization {}",
                        organizationDef.organizationId);
                    return null;
                }
                // Validate and escape path before using in RSQL
                String safeOrgPath = PathSanitizer.escapeForRsql(organizationDef.parentPath);
                return selector(alias, businessRoleName, SecurityFieldType.ORG_PATH) + "=*'" + safeOrgPath + "*'";
            case HIERARCHY_UP:
                if (organizationDef.parentPath == null) {
                    log.warn("Cannot build organization hierarchy-up RSQL filter: parentPath is null for organization {}",
                        organizationDef.organizationId);
                    return null;
                }
                // Ancestors are enumerated as an '=in=' list of path prefixes, not a suffix LIKE.
                String orgUpClause = buildAncestorInClause(
                    selector(alias, businessRoleName, SecurityFieldType.ORG_PATH),
                    organizationDef.parentPath
                );
                if (orgUpClause == null) {
                    log.warn("Cannot build organization hierarchy-up RSQL filter: no ancestor prefixes in path {} for organization {}",
                        organizationDef.parentPath, organizationDef.organizationId);
                    return null;
                }
                return orgUpClause;
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
     * so HIERARCHY_DOWN keeps using {@code =*'path*'}.
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
