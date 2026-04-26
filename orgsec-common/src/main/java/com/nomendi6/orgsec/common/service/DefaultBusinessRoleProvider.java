package com.nomendi6.orgsec.common.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.constants.SecurityConstants;
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.model.BusinessRoleDefinition;

/**
 * Default business role provider that defines the standard roles for the orgsec module.
 *
 * This provider defines three default business roles:
 * - owner: Full access to all security field types
 * - customer: Access to company and person fields
 * - contractor: Access to person fields only
 *
 * This provider has the lowest priority (0) and can be overridden by application-specific providers.
 * It is conditionally loaded only if no other BusinessRoleProvider is present.
 *
 * @since 1.0.0
 */
@Component
@ConditionalOnMissingBean(name = "applicationBusinessRoleProvider")
public class DefaultBusinessRoleProvider implements BusinessRoleProvider {

    @Override
    public Map<String, BusinessRoleDefinition> getBusinessRoleDefinitions() {
        Map<String, BusinessRoleDefinition> definitions = new LinkedHashMap<>();

        // Owner role - supports all field types
        Set<SecurityFieldType> ownerFields = Set.of(
            SecurityFieldType.COMPANY,
            SecurityFieldType.COMPANY_PATH,
            SecurityFieldType.ORG,
            SecurityFieldType.ORG_PATH,
            SecurityFieldType.PERSON
        );
        definitions.put(
            SecurityConstants.BusinessRoles.OWNER,
            new BusinessRoleDefinition(SecurityConstants.BusinessRoles.OWNER, ownerFields)
        );

        // Customer role - supports company and person fields
        Set<SecurityFieldType> customerFields = Set.of(SecurityFieldType.COMPANY, SecurityFieldType.COMPANY_PATH, SecurityFieldType.PERSON);
        definitions.put(
            SecurityConstants.BusinessRoles.CUSTOMER,
            new BusinessRoleDefinition(SecurityConstants.BusinessRoles.CUSTOMER, customerFields)
        );

        // Contractor role - supports only person field
        Set<SecurityFieldType> contractorFields = Set.of(SecurityFieldType.PERSON);
        definitions.put(
            SecurityConstants.BusinessRoles.CONTRACTOR,
            new BusinessRoleDefinition(SecurityConstants.BusinessRoles.CONTRACTOR, contractorFields)
        );

        return definitions;
    }

    @Override
    public int getPriority() {
        return 0; // Lowest priority - can be overridden by application providers
    }

    @Override
    public String getProviderName() {
        return "DefaultBusinessRoleProvider";
    }
}
