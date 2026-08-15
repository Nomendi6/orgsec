package com.nomendi6.orgsec.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.dto.OrganizationData;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.helper.PathSanitizer;
import com.nomendi6.orgsec.interfaces.SecurityEnabledDTO;
import com.nomendi6.orgsec.model.BusinessRoleContext;
import com.nomendi6.orgsec.model.BusinessRoleDefinition;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;

/**
 * Service responsible for checking privileges and permissions.
 * Contains logic for privilege validation and security context checking.
 */
@Component
public class PrivilegeChecker {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeChecker.class);

    private final BusinessRoleConfiguration businessRoleConfiguration;

    public PrivilegeChecker(BusinessRoleConfiguration businessRoleConfiguration) {
        this.businessRoleConfiguration = businessRoleConfiguration;
    }

    /**
     * Gets the appropriate privilege definition based on the operation type.
     */
    public PrivilegeDef getResourcePrivileges(ResourceDef resourceDef, PrivilegeOperation operation) {
        if (resourceDef == null) {
            return null;
        }

        switch (operation) {
            case WRITE:
                return resourceDef.getAggregatedWritePrivilege();
            case READ:
                return resourceDef.getAggregatedReadPrivilege();
            case EXECUTE:
                return resourceDef.getAggregatedExecutePrivilege();
            default:
                return null;
        }
    }

    /**
     * Checks if the user has the required operation privilege.
     */
    public boolean hasRequiredOperation(PrivilegeDef resourceAggregatedPrivs, PrivilegeOperation operation) {
        if (resourceAggregatedPrivs == null) {
            return false;
        }

        return (
            (operation == PrivilegeOperation.WRITE && resourceAggregatedPrivs.operation == PrivilegeOperation.WRITE) ||
            (operation == PrivilegeOperation.READ &&
                (resourceAggregatedPrivs.operation == PrivilegeOperation.WRITE ||
                    resourceAggregatedPrivs.operation == PrivilegeOperation.READ)) ||
            (operation == PrivilegeOperation.EXECUTE && resourceAggregatedPrivs.operation == PrivilegeOperation.EXECUTE)
        );
    }

    /**
     * Extracts security context for a specific business role from the entity DTO.
     * Uses configurable business role definitions.
     */
    public BusinessRoleContext extractSecurityContext(SecurityEnabledDTO entityDTO, String roleName) {
        BusinessRoleContext.Builder builder = new BusinessRoleContext.Builder();

        if (!businessRoleConfiguration.isValidBusinessRole(roleName)) {
            return builder.build();
        }

        BusinessRoleDefinition roleDefinition = businessRoleConfiguration.getBusinessRoleDefinition(roleName);

        // Configure based on supported fields for this role
        if (roleDefinition.supportsField(SecurityFieldType.COMPANY)) {
            Object companyField = entityDTO.getSecurityField(roleName, SecurityFieldType.COMPANY);
            Long companyId = extractPartyId(companyField);
            builder
                .companyId(companyId)
                .companyPath((String) entityDTO.getSecurityField(roleName, SecurityFieldType.COMPANY_PATH))
                .checkCompany(true);
        }

        if (roleDefinition.supportsField(SecurityFieldType.ORG)) {
            Object orgField = entityDTO.getSecurityField(roleName, SecurityFieldType.ORG);
            Long orgId = extractPartyId(orgField);
            builder.orgId(orgId).orgPath((String) entityDTO.getSecurityField(roleName, SecurityFieldType.ORG_PATH)).checkOrg(true);
        }

        if (roleDefinition.supportsField(SecurityFieldType.PERSON)) {
            Object personField = entityDTO.getSecurityField(roleName, SecurityFieldType.PERSON);
            Long personId = extractPersonId(personField);
            builder.personId(personId).checkPerson(true);
        }

        return builder.build();
    }

    /**
     * Checks privilege for a specific business role and organization.
     */
    public boolean checkPrivilegeForBusinessRole(
        PersonData currentPerson,
        OrganizationDef organizationDef,
        PrivilegeDef resourceAggregatedPrivs,
        BusinessRoleContext context
    ) {
        return checkOrganizationPrivilege(
            currentPerson,
            organizationDef,
            resourceAggregatedPrivs,
            context.getCompanyId(),
            context.getCompanyPath(),
            context.getOrgId(),
            context.getOrgPath(),
            context.getPersonId(),
            context.isCheckCompany(),
            context.isCheckOrg(),
            context.isCheckPerson()
        );
    }

    /**
     * Checks privilege for a specific business role.
     */
    public boolean checkBusinessRolePrivilege(
        PersonData currentPerson,
        OrganizationDef organizationDef,
        PrivilegeDef resourceAggregatedPrivs,
        String businessRoleName,
        SecurityEnabledDTO entityDTO
    ) {
        try {
            // Use the new string-based method directly
            BusinessRoleContext context = extractSecurityContext(entityDTO, businessRoleName);
            return checkPrivilegeForBusinessRole(currentPerson, organizationDef, resourceAggregatedPrivs, context);
        } catch (IllegalArgumentException e) {
            // Unknown business role, skip it
            return false;
        }
    }

    /**
     * Check if all privileges are satisfied. Refactored version with improved readability.
     */
    public boolean checkOrganizationPrivilege(
        PersonData currentPerson,
        OrganizationDef organizationDef,
        PrivilegeDef resourceAggregatedPrivs,
        Long businessRoleCompanyId,
        String businessRoleCompanyPath,
        Long businessRoleOrgId,
        String businessRoleOrgPath,
        Long businessRolePersonId,
        Boolean checkCompany,
        Boolean checkOrg,
        Boolean checkPerson
    ) {
        // Check company level privilege - only if company checks are enabled
        if (shouldCheckCompany(checkCompany, businessRoleCompanyId, businessRoleCompanyPath)) {
            if (checkCompanyPrivilege(organizationDef, resourceAggregatedPrivs, businessRoleCompanyId, businessRoleCompanyPath)) {
                return true;
            }
        }

        // Check organization level privilege - only if org checks are enabled and company didn't pass
        if (shouldCheckOrg(checkOrg, businessRoleOrgId, businessRoleOrgPath, resourceAggregatedPrivs, checkCompany)) {
            if (checkOrgPrivilege(organizationDef, resourceAggregatedPrivs, businessRoleOrgId, businessRoleOrgPath)) {
                return true;
            }
        }

        // Check person level privilege - only if person checks are enabled and previous checks didn't pass
        if (shouldCheckPerson(checkPerson, businessRolePersonId, resourceAggregatedPrivs, checkCompany, checkOrg)) {
            if (checkPersonPrivilege(currentPerson, resourceAggregatedPrivs, businessRolePersonId)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The record's path is required, not merely non-null. A path that no hierarchy comparison can use -
     * empty, or a bare separator - was passing this gate and then matching every record, because
     * {@code startsWith} is unconditionally true against both. Treating those the same as a missing path
     * keeps one rule: an axis whose path is unusable is not evaluated, and the business role denies.
     */
    private boolean shouldCheckCompany(Boolean checkCompany, Long businessRoleCompanyId, String businessRoleCompanyPath) {
        return (
            checkCompany != null &&
            checkCompany &&
            businessRoleCompanyId != null &&
            PathSanitizer.isUsableHierarchyAnchor(businessRoleCompanyPath)
        );
    }

    private boolean shouldCheckOrg(
        Boolean checkOrg,
        Long businessRoleOrgId,
        String businessRoleOrgPath,
        PrivilegeDef resourceAggregatedPrivs,
        Boolean checkCompany
    ) {
        return (
            checkOrg != null &&
            checkOrg &&
            businessRoleOrgId != null &&
            PathSanitizer.isUsableHierarchyAnchor(businessRoleOrgPath) &&
            ((resourceAggregatedPrivs.company == PrivilegeDirection.NONE) || (checkCompany != null && !checkCompany))
        );
    }

    private boolean shouldCheckPerson(
        Boolean checkPerson,
        Long businessRolePersonId,
        PrivilegeDef resourceAggregatedPrivs,
        Boolean checkCompany,
        Boolean checkOrg
    ) {
        return (
            checkPerson != null &&
            checkPerson &&
            businessRolePersonId != null &&
            ((resourceAggregatedPrivs.company == PrivilegeDirection.NONE) || (checkCompany != null && !checkCompany)) &&
            ((resourceAggregatedPrivs.org == PrivilegeDirection.NONE) || (checkOrg != null && !checkOrg))
        );
    }

    private boolean checkCompanyPrivilege(
        OrganizationDef organizationDef,
        PrivilegeDef resourceAggregatedPrivs,
        Long businessRoleCompanyId,
        String businessRoleCompanyPath
    ) {
        if (resourceAggregatedPrivs.company == PrivilegeDirection.EXACT) {
            return organizationDef.companyId != null && organizationDef.companyId.equals(businessRoleCompanyId);
        }
        if (!hasPrincipalAnchor(resourceAggregatedPrivs.company, organizationDef.companyParentPath, "companyParentPath", organizationDef)) {
            return false;
        }
        if (resourceAggregatedPrivs.company == PrivilegeDirection.HIERARCHY_DOWN) {
            return businessRoleCompanyPath.startsWith(organizationDef.companyParentPath);
        }
        if (resourceAggregatedPrivs.company == PrivilegeDirection.HIERARCHY_UP) {
            // HIERARCHY_UP means "the entity sits on the principal's ancestor chain", i.e. the
            // entity path is a PREFIX of the principal path. Mirrors checkOrgPrivilege below.
            return organizationDef.companyParentPath.startsWith(businessRoleCompanyPath);
        }
        return false;
    }

    private boolean checkOrgPrivilege(
        OrganizationDef organizationDef,
        PrivilegeDef resourceAggregatedPrivs,
        Long businessRoleOrgId,
        String businessRoleOrgPath
    ) {
        if (resourceAggregatedPrivs.org == PrivilegeDirection.EXACT) {
            return organizationDef.organizationId != null && organizationDef.organizationId.equals(businessRoleOrgId);
        }
        if (!hasPrincipalAnchor(resourceAggregatedPrivs.org, organizationDef.parentPath, "parentPath", organizationDef)) {
            return false;
        }
        if (resourceAggregatedPrivs.org == PrivilegeDirection.HIERARCHY_DOWN) {
            return businessRoleOrgPath.startsWith(organizationDef.parentPath);
        }
        if (resourceAggregatedPrivs.org == PrivilegeDirection.HIERARCHY_UP) {
            return organizationDef.parentPath.startsWith(businessRoleOrgPath);
        }
        return false;
    }

    /**
     * Whether a hierarchical comparison has the principal's own anchor path to compare against.
     * <p>
     * Both hierarchy directions dereference this value, so without the guard a missing anchor raises a
     * {@link NullPointerException} rather than refusing the privilege - and
     * {@code checkBusinessRolePrivilege} catches only {@link IllegalArgumentException}, so the exception
     * escapes to the caller and the request fails instead of the record being denied.
     * <p>
     * The anchor is missing whenever the backend could not supply one: a JWT principal whose organization
     * is absent from the delegate storage, or an application that never populated
     * {@code companyParentPath}. Denying is the answer the list filter already gives for the same
     * condition, so this keeps the two authorization paths in agreement.
     * <p>
     * A present but unusable anchor is refused for the opposite reason: an empty path or a bare separator
     * makes {@code startsWith} unconditionally true, so the comparison would grant on every record rather
     * than throw. See {@link PathSanitizer#isUsableHierarchyAnchor}.
     * <p>
     * Logged at debug, not warn: this runs once per record, so a warn would flood a page of results. The
     * backend that failed to supply the anchor logs it once, which is where the misconfiguration belongs.
     */
    private boolean hasPrincipalAnchor(
        PrivilegeDirection direction,
        String anchorPath,
        String anchorName,
        OrganizationDef organizationDef
    ) {
        boolean hierarchical = direction == PrivilegeDirection.HIERARCHY_DOWN || direction == PrivilegeDirection.HIERARCHY_UP;
        if (!hierarchical || PathSanitizer.isUsableHierarchyAnchor(anchorPath)) {
            return true;
        }
        log.debug(
            "Denying {} privilege: {} is not a usable hierarchy anchor ({}) for organization {}",
            direction,
            anchorName,
            anchorPath,
            organizationDef.organizationId
        );
        return false;
    }

    private boolean checkPersonPrivilege(PersonData currentPerson, PrivilegeDef resourceAggregatedPrivs, Long businessRolePersonId) {
        if (resourceAggregatedPrivs.person) {
            return currentPerson.getId().equals(businessRolePersonId);
        }
        return false;
    }

    private Long getPartyIdOrNull(OrganizationData organizationData) {
        return organizationData != null ? organizationData.getId() : null;
    }

    private Long getPersonIdOrNull(PersonData personData) {
        return personData != null ? personData.getId() : null;
    }

    /**
     * Extract party ID from various types of party objects.
     * Handles both OrganizationData (from orgsec) and PartyDTO (from domain).
     */
    private Long extractPartyId(Object partyObject) {
        if (partyObject == null) {
            return null;
        }
        if (partyObject instanceof OrganizationData) {
            return ((OrganizationData) partyObject).getId();
        }
        // Handle PartyDTO from domain - use reflection to avoid direct dependency
        try {
            var getIdMethod = partyObject.getClass().getMethod("getId");
            return (Long) getIdMethod.invoke(partyObject);
        } catch (Exception e) {
            log.warn("Could not extract party ID from object of type: {}", partyObject.getClass().getName());
            return null;
        }
    }

    /**
     * Extract person ID from various types of person objects.
     * Handles both PersonData (from orgsec) and PersonDTO (from domain).
     */
    private Long extractPersonId(Object personObject) {
        if (personObject == null) {
            return null;
        }
        if (personObject instanceof PersonData) {
            return ((PersonData) personObject).getId();
        }
        // Handle PersonDTO from domain - use reflection to avoid direct dependency
        try {
            var getIdMethod = personObject.getClass().getMethod("getId");
            return (Long) getIdMethod.invoke(personObject);
        } catch (Exception e) {
            log.warn("Could not extract person ID from object of type: {}", personObject.getClass().getName());
            return null;
        }
    }
}
