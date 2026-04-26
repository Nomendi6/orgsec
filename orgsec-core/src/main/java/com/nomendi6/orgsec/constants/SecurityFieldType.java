package com.nomendi6.orgsec.constants;

/**
 * Enumeration representing the types of security fields used in organizational security.
 * These fields are used to track ownership and access control at different organizational levels.
 *
 * @since 1.0.0
 */
public enum SecurityFieldType {
    /**
     * Company entity field.
     */
    COMPANY("COMPANY", "Company entity", true),

    /**
     * Organization unit field.
     */
    ORG("ORG", "Organization unit", true),

    /**
     * Person/Employee field.
     */
    PERSON("PERSON", "Person/Employee", true),

    /**
     * Company hierarchy path field.
     */
    COMPANY_PATH("COMPANY_PATH", "Company hierarchy path", false),

    /**
     * Organization hierarchy path field.
     */
    ORG_PATH("ORG_PATH", "Organization hierarchy path", false);

    private final String code;
    private final String description;
    private final boolean isEntity;

    SecurityFieldType(String code, String description, boolean isEntity) {
        this.code = code;
        this.description = description;
        this.isEntity = isEntity;
    }

    /**
     * Gets the code of the security field type.
     *
     * @return the security field type code
     */
    public String getCode() {
        return code;
    }

    /**
     * Gets the description of the security field type.
     *
     * @return the security field type description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Checks if this field type represents an entity (as opposed to a path).
     *
     * @return true if this is an entity field type
     */
    public boolean isEntity() {
        return isEntity;
    }

    /**
     * Checks if this field type represents a path.
     *
     * @return true if this is a path field type
     */
    public boolean isPath() {
        return !isEntity;
    }

    /**
     * Converts a string code to SecurityFieldType enum.
     *
     * @param code the code to convert
     * @return the matching SecurityFieldType or null if not found
     */
    public static SecurityFieldType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (SecurityFieldType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Gets the corresponding path field type for an entity field type.
     *
     * @return the corresponding path field type, or null if this is not an entity type
     */
    public SecurityFieldType getPathFieldType() {
        switch (this) {
            case COMPANY:
                return COMPANY_PATH;
            case ORG:
                return ORG_PATH;
            default:
                return null;
        }
    }

    /**
     * Gets the corresponding entity field type for a path field type.
     *
     * @return the corresponding entity field type, or null if this is not a path type
     */
    public SecurityFieldType getEntityFieldType() {
        switch (this) {
            case COMPANY_PATH:
                return COMPANY;
            case ORG_PATH:
                return ORG;
            default:
                return null;
        }
    }

    /**
     * Generates a field name for a specific business role.
     * For example: "customer" + COMPANY = "customerCompany"
     *
     * @param businessRole the business role (e.g., "customer", "executor")
     * @return the generated field name
     */
    public String generateFieldName(String businessRole) {
        if (businessRole == null || businessRole.isEmpty()) {
            throw new IllegalArgumentException("Business role must not be null or empty");
        }

        String roleLower = businessRole.toLowerCase();
        switch (this) {
            case COMPANY:
                return roleLower + "Company";
            case ORG:
                return roleLower + "Org";
            case PERSON:
                return roleLower + "Person";
            case COMPANY_PATH:
                return roleLower + "CompanyPath";
            case ORG_PATH:
                return roleLower + "OrgPath";
            default:
                return roleLower + code;
        }
    }

    /**
     * Checks if this field type is applicable for the given privilege scope.
     *
     * @param scope the privilege scope to check
     * @return true if this field type is applicable for the scope
     */
    public boolean isApplicableForScope(PrivilegeScope scope) {
        if (scope == null) {
            return false;
        }

        switch (scope) {
            case ALL:
                return true;
            case COMP:
            case COMPHD:
            case COMPHU:
                return this == COMPANY || this == COMPANY_PATH;
            case ORG:
            case ORGHD:
            case ORGHU:
                return this == ORG || this == ORG_PATH || this == COMPANY || this == COMPANY_PATH;
            case EMP:
                return this == PERSON;
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return code;
    }
}
