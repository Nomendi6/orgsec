package com.nomendi6.orgsec.interfaces;

import com.nomendi6.orgsec.constants.SecurityFieldType;

/**
 * Interface for entities that support organizational security through dynamic field resolution.
 * This interface enables loose coupling between the security module and application entities,
 * allowing flexible business role configurations without hard-coding field names.
 *
 * <p>Implementing classes should provide access to security-related fields based on
 * business roles (e.g., customer, executor, owner) and field types (e.g., company, org, person).
 *
 * <p>Example implementation:
 * <pre>{@code
 * @Entity
 * public class Person implements SecurityEnabledEntity {
 *     private Party customerCompany;
 *     private Person customerPerson;
 *     private String customerCompanyPath;
 *
 *     @Override
 *     public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
 *         if ("customer".equalsIgnoreCase(businessRole)) {
 *             switch (fieldType) {
 *                 case COMPANY: return customerCompany;
 *                 case PERSON: return customerPerson;
 *                 case COMPANY_PATH: return customerCompanyPath;
 *             }
 *         }
 *         return null;
 *     }
 * }
 * }</pre>
 *
 * @since 1.0.0
 */
public interface SecurityEnabledEntity {
    /**
     * Gets the value of a security field for a specific business role and field type.
     *
     * @param businessRole the business role (e.g., "customer", "executor", "owner")
     * @param fieldType the type of field to retrieve
     * @return the field value, or null if the field doesn't exist for this role
     */
    Object getSecurityField(String businessRole, SecurityFieldType fieldType);

    /**
     * Sets the value of a security field for a specific business role and field type.
     *
     * @param businessRole the business role (e.g., "customer", "executor", "owner")
     * @param fieldType the type of field to set
     * @param value the value to set
     */
    void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value);

    /**
     * Gets the path value for a specific business role and field type.
     * This is a convenience method specifically for path fields.
     *
     * @param businessRole the business role
     * @param fieldType the path field type (should be COMPANY_PATH or ORG_PATH)
     * @return the path string, or null if not available
     */
    default String getSecurityPath(String businessRole, SecurityFieldType fieldType) {
        if (fieldType == null || !fieldType.isPath()) {
            return null;
        }
        Object value = getSecurityField(businessRole, fieldType);
        return value instanceof String ? (String) value : null;
    }

    /**
     * Sets the path value for a specific business role and field type.
     * This is a convenience method specifically for path fields.
     *
     * @param businessRole the business role
     * @param fieldType the path field type (should be COMPANY_PATH or ORG_PATH)
     * @param path the path string to set
     */
    default void setSecurityPath(String businessRole, SecurityFieldType fieldType, String path) {
        if (fieldType != null && fieldType.isPath()) {
            setSecurityField(businessRole, fieldType, path);
        }
    }

    /**
     * Checks if this entity has a security field for the given business role and field type.
     *
     * @param businessRole the business role
     * @param fieldType the field type
     * @return true if the field exists and has a non-null value
     */
    default boolean hasSecurityField(String businessRole, SecurityFieldType fieldType) {
        return getSecurityField(businessRole, fieldType) != null;
    }

    /**
     * Gets the ID of the security field entity for a specific business role and field type.
     * This method assumes that entity fields have a getId() method.
     *
     * @param businessRole the business role
     * @param fieldType the field type (should be an entity type, not a path)
     * @return the entity ID, or null if not available
     */
    default Long getSecurityFieldId(String businessRole, SecurityFieldType fieldType) {
        if (fieldType == null || !fieldType.isEntity()) {
            return null;
        }
        Object entity = getSecurityField(businessRole, fieldType);
        if (entity == null) {
            return null;
        }

        // Use reflection to get ID - this is a common pattern for JPA entities
        try {
            java.lang.reflect.Method getIdMethod = entity.getClass().getMethod("getId");
            Object id = getIdMethod.invoke(entity);
            if (id instanceof Long) {
                return (Long) id;
            } else if (id instanceof Number) {
                return ((Number) id).longValue();
            }
        } catch (Exception e) {
            // Silently fail - entity might not have getId method
        }

        return null;
    }

    /**
     * Clears all security fields for a specific business role.
     *
     * @param businessRole the business role
     */
    default void clearSecurityFields(String businessRole) {
        for (SecurityFieldType fieldType : SecurityFieldType.values()) {
            setSecurityField(businessRole, fieldType, null);
        }
    }

    /**
     * Copies security fields from another SecurityEnabledEntity for a specific business role.
     *
     * @param source the source entity to copy from
     * @param businessRole the business role to copy
     */
    default void copySecurityFields(SecurityEnabledEntity source, String businessRole) {
        if (source == null || businessRole == null) {
            return;
        }
        for (SecurityFieldType fieldType : SecurityFieldType.values()) {
            Object value = source.getSecurityField(businessRole, fieldType);
            if (value != null) {
                setSecurityField(businessRole, fieldType, value);
            }
        }
    }

    /**
     * Gets all business roles that have at least one security field set.
     * This is a utility method that implementations can override for better performance.
     *
     * @return array of business roles with security fields
     */
    default String[] getBusinessRolesWithSecurityFields() {
        // Default implementation - can be overridden for better performance
        java.util.Set<String> roles = new java.util.HashSet<>();
        String[] commonRoles = { "customer", "executor", "owner", "supplier", "partner" };

        for (String role : commonRoles) {
            for (SecurityFieldType fieldType : SecurityFieldType.values()) {
                if (hasSecurityField(role, fieldType)) {
                    roles.add(role);
                    break;
                }
            }
        }

        return roles.toArray(new String[0]);
    }
}
