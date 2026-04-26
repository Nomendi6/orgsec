package com.nomendi6.orgsec.constants;

/**
 * Enumeration representing the scope levels for privilege application.
 * Defines at which organizational level privileges are applied.
 *
 * @since 1.0.0
 */
public enum PrivilegeScope {
    /**
     * ALL - Unrestricted access across all organizational units.
     */
    ALL("ALL", "All units without restriction"),

    /**
     * COMP - Company level access (exact).
     */
    COMP("COMP", "Company level exact"),

    /**
     * COMPHD - Company and hierarchy down.
     */
    COMPHD("COMPHD", "Company and hierarchy down"),

    /**
     * COMPHU - Company and hierarchy up.
     */
    COMPHU("COMPHU", "Company and hierarchy up"),

    /**
     * ORG - Organization unit level (exact).
     */
    ORG("ORG", "Organization unit exact"),

    /**
     * ORGHD - Organization unit and hierarchy down.
     */
    ORGHD("ORGHD", "Organization unit and hierarchy down"),

    /**
     * ORGHU - Organization unit and hierarchy up.
     */
    ORGHU("ORGHU", "Organization unit and hierarchy up"),

    /**
     * EMP - Employee/Person level only.
     */
    EMP("EMP", "Employee/Person level");

    private final String code;
    private final String description;

    PrivilegeScope(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Gets the code of the privilege scope.
     *
     * @return the privilege scope code
     */
    public String getCode() {
        return code;
    }

    /**
     * Gets the description of the privilege scope.
     *
     * @return the privilege scope description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Converts a string code to PrivilegeScope enum.
     *
     * @param code the code to convert
     * @return the matching PrivilegeScope or null if not found
     */
    public static PrivilegeScope fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PrivilegeScope scope : values()) {
            if (scope.code.equalsIgnoreCase(code)) {
                return scope;
            }
        }
        return null;
    }

    /**
     * Checks if this scope is at company level.
     *
     * @return true if this is COMP, COMPHD, or COMPHU
     */
    public boolean isCompanyLevel() {
        return this == COMP || this == COMPHD || this == COMPHU;
    }

    /**
     * Checks if this scope is at organization unit level.
     *
     * @return true if this is ORG, ORGHD, or ORGHU
     */
    public boolean isOrganizationLevel() {
        return this == ORG || this == ORGHD || this == ORGHU;
    }

    /**
     * Checks if this scope is at employee level.
     *
     * @return true if this is EMP
     */
    public boolean isEmployeeLevel() {
        return this == EMP;
    }

    /**
     * Checks if this scope includes hierarchical access.
     *
     * @return true if scope includes hierarchy (HD or HU suffix)
     */
    public boolean isHierarchical() {
        return this == COMPHD || this == COMPHU || this == ORGHD || this == ORGHU;
    }

    /**
     * Checks if this scope includes downward hierarchy.
     *
     * @return true if scope includes downward hierarchy (HD suffix)
     */
    public boolean includesDown() {
        return this == COMPHD || this == ORGHD || this == ALL;
    }

    /**
     * Checks if this scope includes upward hierarchy.
     *
     * @return true if scope includes upward hierarchy (HU suffix)
     */
    public boolean includesUp() {
        return this == COMPHU || this == ORGHU || this == ALL;
    }

    /**
     * Gets the corresponding PrivilegeDirection for this scope.
     *
     * @return the corresponding PrivilegeDirection
     */
    public PrivilegeDirection getDirection() {
        switch (this) {
            case ALL:
                return PrivilegeDirection.ALL;
            case COMPHD:
            case ORGHD:
                return PrivilegeDirection.HIERARCHY_DOWN;
            case COMPHU:
            case ORGHU:
                return PrivilegeDirection.HIERARCHY_UP;
            case COMP:
            case ORG:
            case EMP:
                return PrivilegeDirection.EXACT;
            default:
                return PrivilegeDirection.NONE;
        }
    }

    /**
     * Generates a privilege code combining entity, scope, and operation.
     * Format: {ENTITY}_{SCOPE}_{OPERATION}
     *
     * @param entity the entity name (e.g., "PERSON", "PARTY")
     * @param operation the operation (e.g., "R" for READ, "W" for WRITE)
     * @return the formatted privilege code
     */
    public String generatePrivilegeCode(String entity, String operation) {
        if (entity == null || operation == null) {
            throw new IllegalArgumentException("Entity and operation must not be null");
        }
        return String.format("%s_%s_%s", entity.toUpperCase(), this.code, operation.toUpperCase());
    }

    @Override
    public String toString() {
        return code;
    }
}
