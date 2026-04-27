package com.nomendi6.orgsec.common.service;

import static java.util.Arrays.asList;
import static com.nomendi6.orgsec.helper.RsqlHelper.addParenthases;
import static com.nomendi6.orgsec.helper.RsqlHelper.orRsql;

import java.util.List;
import java.util.Map;
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

        RsqlFilterContext context = new RsqlFilterContext(resourceName, parentField, operation);
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

        PrivilegeDef resourceAggregatedPrivs = getPrivilegeDefForOperation(resourceDef, context.operation);
        if (resourceAggregatedPrivs == null) {
            return null;
        }

        if (resourceAggregatedPrivs.all) {
            return ALL_GRANT_SENTINEL;
        }

        return buildRsqlForOrganizationalPrivilege(
            currentPerson,
            organizationDef,
            resourceAggregatedPrivs,
            businessRoleName,
            context.parentField
        );
    }

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
                // Validate and escape path before using in RSQL
                String safeCompanyPathUp = PathSanitizer.escapeForRsql(organizationDef.companyParentPath);
                return selector(alias, businessRoleName, SecurityFieldType.COMPANY_PATH) + "=*'*" + safeCompanyPathUp + "'";
            default:
                return "";
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
                // Validate and escape path before using in RSQL
                String safeOrgPathUp = PathSanitizer.escapeForRsql(organizationDef.parentPath);
                return selector(alias, businessRoleName, SecurityFieldType.ORG_PATH) + "=*'*" + safeOrgPathUp + "'";
            default:
                return "";
        }
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

        RsqlFilterContext(String resourceName, String parentField, PrivilegeOperation operation) {
            this.resourceName = resourceName;
            this.parentField = parentField;
            this.operation = operation;
        }
    }
}
