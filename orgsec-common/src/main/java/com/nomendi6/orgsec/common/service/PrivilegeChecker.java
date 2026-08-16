package com.nomendi6.orgsec.common.service;

import java.math.BigInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.dto.OrganizationData;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.exceptions.OrgsecSecurityException;
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
        } catch (ClassCastException e) {
            // The entity returned something other than a String for a *_PATH field. That is an
            // application-side mapping error, but surfacing it as HTTP 500 while a list endpoint over
            // the same rows answers normally is worse than denying: the two authorization paths must
            // agree, and the list filter has no record to fail on.
            log.warn(
                "Business role '{}' on {} returned a non-String value for a path field - denying",
                businessRoleName, entityDTO.getClass().getName(), e
            );
            return false;
        } catch (OrgsecSecurityException e) {
            log.warn("Business role '{}' on {} supplied an invalid path - denying: {}",
                businessRoleName, entityDTO.getClass().getName(), e.getMessage());
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
        if (shouldCheckCompany(checkCompany, businessRoleCompanyId, businessRoleCompanyPath, resourceAggregatedPrivs)) {
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
     * Which operand an axis actually needs depends on the direction it is evaluated in.
     *
     * <p>Requiring both an id and a usable path for every direction denied cases the list filter
     * grants, and the two paths must agree per record. {@code EXACT} compares ids and never looks at
     * a path, so a record with no path is still decidable; the hierarchy directions compare paths and
     * never look at the record's id.
     *
     * <p>{@code NONE} and {@code ALL} are not evaluated at all. {@code ALL} is an abstract top of the
     * direction lattice used by the union algebra, not a grant an evaluator can act on - {@code all}
     * is a separate flag on {@link PrivilegeDef} - so treating it as a scope here would grant on an
     * axis nobody configured.
     */
    private boolean shouldCheckCompany(
        Boolean checkCompany,
        Long businessRoleCompanyId,
        String businessRoleCompanyPath,
        PrivilegeDef resourceAggregatedPrivs
    ) {
        if (checkCompany == null || !checkCompany) {
            return false;
        }
        switch (resourceAggregatedPrivs.company) {
            case EXACT:
                return businessRoleCompanyId != null;
            case HIERARCHY_DOWN:
            case HIERARCHY_UP:
                return PathSanitizer.isUsableHierarchyAnchor(businessRoleCompanyPath);
            default:
                return false;
        }
    }

    private boolean shouldCheckOrg(
        Boolean checkOrg,
        Long businessRoleOrgId,
        String businessRoleOrgPath,
        PrivilegeDef resourceAggregatedPrivs,
        Boolean checkCompany
    ) {
        if (checkOrg == null || !checkOrg) {
            return false;
        }
        boolean companyDidNotApply =
            (resourceAggregatedPrivs.company == PrivilegeDirection.NONE) || (checkCompany != null && !checkCompany);
        if (!companyDidNotApply) {
            return false;
        }
        switch (resourceAggregatedPrivs.org) {
            case EXACT:
                return businessRoleOrgId != null;
            case HIERARCHY_DOWN:
            case HIERARCHY_UP:
                return PathSanitizer.isUsableHierarchyAnchor(businessRoleOrgPath);
            default:
                return false;
        }
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
        return matchesHierarchy(
            resourceAggregatedPrivs.company,
            organizationDef.companyParentPath,
            businessRoleCompanyPath,
            "companyParentPath",
            organizationDef
        );
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
        return matchesHierarchy(
            resourceAggregatedPrivs.org,
            organizationDef.parentPath,
            businessRoleOrgPath,
            "parentPath",
            organizationDef
        );
    }

    /**
     * Compares the principal's anchor with the record's path for a hierarchy direction.
     *
     * <p>Both operands are validated, not merely null-checked, and a failure denies. Three distinct
     * failures are covered by the one rule:
     *
     * <ul>
     *   <li>A missing anchor - a JWT principal whose organization the delegate does not know, or an
     *       application that never populated {@code companyParentPath} - would otherwise raise a
     *       {@link NullPointerException}. {@code checkBusinessRolePrivilege} catches only
     *       {@link IllegalArgumentException}, so that escaped to the caller and failed the request
     *       instead of denying the record.</li>
     *   <li>An empty path or a bare {@code "|"} makes {@code startsWith} unconditionally true, so the
     *       comparison would match every record rather than throw.</li>
     *   <li>A path that does not end in the separator, such as {@code |A|B}, makes {@code startsWith}
     *       match {@code |A|BX|C|} - a sibling branch whose name merely begins with the same
     *       characters. Requiring the canonical form is what makes prefix comparison equivalent to
     *       "is a descendant of".</li>
     * </ul>
     *
     * <p>Logged at debug, not warn: this runs once per record, so a warn would flood a page of
     * results. The backend that failed to supply the anchor logs it once, which is where the
     * misconfiguration belongs.
     */
    private boolean matchesHierarchy(
        PrivilegeDirection direction,
        String principalAnchor,
        String recordPath,
        String anchorName,
        OrganizationDef organizationDef
    ) {
        if (direction != PrivilegeDirection.HIERARCHY_DOWN && direction != PrivilegeDirection.HIERARCHY_UP) {
            return false;
        }
        String principal;
        String record;
        try {
            principal = PathSanitizer.validateHierarchyAnchor(principalAnchor);
            record = PathSanitizer.validateHierarchyAnchor(recordPath);
        } catch (OrgsecSecurityException e) {
            log.debug(
                "Denying {} privilege for organization {}: {}={} / record path={} - {}",
                direction, organizationDef.organizationId, anchorName, principalAnchor, recordPath, e.getMessage()
            );
            return false;
        }

        if (direction == PrivilegeDirection.HIERARCHY_DOWN) {
            // The record sits inside the principal's subtree.
            return record.startsWith(principal);
        }
        // HIERARCHY_UP: the record sits on the principal's ancestor chain, i.e. the record path is a
        // prefix of the principal path.
        return principal.startsWith(record);
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
            return toIdentifier(getIdMethod.invoke(partyObject), "party", partyObject);
        } catch (ReflectiveOperationException | RuntimeException e) {
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
            return toIdentifier(getIdMethod.invoke(personObject), "person", personObject);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("Could not extract person ID from object of type: {}", personObject.getClass().getName());
            return null;
        }
    }

    /**
     * Narrows whatever {@code getId()} returned to a {@code Long}.
     *
     * <p>The value used to be cast straight to {@code Long}, so an entity exposing an
     * {@code Integer} id - which JPA and MapStruct both produce for an {@code int} column - raised a
     * {@link ClassCastException} that was swallowed into a {@code null}, and the record was denied.
     * An id that is genuinely a whole number is now accepted whatever integral box it arrives in.
     *
     * <p>Non-integral types are still refused rather than rounded: a decimal id is a mapping error,
     * and quietly truncating it would compare against a different row. A {@code BigInteger} outside
     * {@code long} range is refused for the same reason - it cannot round-trip.
     *
     * @return the id, or {@code null} when the value is absent or not a whole number in range
     */
    private Long toIdentifier(Object id, String kind, Object source) {
        if (id == null) {
            return null;
        }
        if (id instanceof Long value) {
            return value;
        }
        if (id instanceof Integer || id instanceof Short || id instanceof Byte) {
            return ((Number) id).longValue();
        }
        if (id instanceof BigInteger value) {
            try {
                return value.longValueExact();
            } catch (ArithmeticException e) {
                log.warn("{} id {} from {} does not fit in a long - denying", kind, value, source.getClass().getName());
                return null;
            }
        }
        log.warn(
            "Could not extract {} ID from {}: getId() returned {}, which is not an integral identifier",
            kind, source.getClass().getName(), id.getClass().getName()
        );
        return null;
    }
}
