package com.nomendi6.orgsec.constants;

/**
 * Enumeration representing the direction or scope of privilege application
 * within the organizational hierarchy.
 *
 * @since 1.0.0
 */
public enum PrivilegeDirection {
    /**
     * No access at this level.
     */
    NONE("NONE", "No access"),

    /**
     * Access only at the exact organizational level.
     */
    EXACT("EXACT", "Exact level only"),

    /**
     * Access to the current level and all levels below in the hierarchy.
     */
    HIERARCHY_DOWN("HIERARCHY_DOWN", "Current level and below"),

    /**
     * Access to the current level and all levels above in the hierarchy.
     */
    HIERARCHY_UP("HIERARCHY_UP", "Current level and above"),

    /**
     * Unrestricted access across all organizational levels.
     */
    ALL("ALL", "All levels");

    private final String code;
    private final String description;

    PrivilegeDirection(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Gets the code of the privilege direction.
     *
     * @return the privilege direction code
     */
    public String getCode() {
        return code;
    }

    /**
     * Gets the description of the privilege direction.
     *
     * @return the privilege direction description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Converts a string code to PrivilegeDirection enum.
     *
     * @param code the code to convert
     * @return the matching PrivilegeDirection or null if not found
     */
    public static PrivilegeDirection fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PrivilegeDirection direction : values()) {
            if (direction.code.equalsIgnoreCase(code)) {
                return direction;
            }
        }
        return null;
    }

    /**
     * Checks if this direction allows access.
     *
     * @return true if access is allowed (not NONE)
     */
    public boolean allowsAccess() {
        return this != NONE;
    }

    /**
     * Checks if this direction includes hierarchical access.
     *
     * @return true if this is HIERARCHY_DOWN, HIERARCHY_UP, or ALL
     */
    public boolean isHierarchical() {
        return this == HIERARCHY_DOWN || this == HIERARCHY_UP || this == ALL;
    }

    /**
     * Checks if this direction includes downward hierarchy.
     *
     * @return true if this is HIERARCHY_DOWN or ALL
     */
    public boolean includesDown() {
        return this == HIERARCHY_DOWN || this == ALL;
    }

    /**
     * Checks if this direction includes upward hierarchy.
     *
     * @return true if this is HIERARCHY_UP or ALL
     */
    public boolean includesUp() {
        return this == HIERARCHY_UP || this == ALL;
    }

    /**
     * Checks if this direction is more permissive than another.
     *
     * @param other the direction to compare with
     * @return true if this direction grants more access than the other
     */
    public boolean isMorePermissiveThan(PrivilegeDirection other) {
        if (other == null) {
            return true;
        }
        if (this == ALL) {
            return other != ALL;
        }
        if (this == HIERARCHY_DOWN || this == HIERARCHY_UP) {
            return other == EXACT || other == NONE;
        }
        if (this == EXACT) {
            return other == NONE;
        }
        return false;
    }

    /**
     * Checks if this direction includes the target organization.
     * Legacy method for backward compatibility.
     *
     * @param isTarget true if checking the target organization itself
     * @param isDescendant true if checking a descendant
     * @param isAncestor true if checking an ancestor
     * @return true if the privilege applies
     */
    public boolean applies(boolean isTarget, boolean isDescendant, boolean isAncestor) {
        switch (this) {
            case EXACT:
                return isTarget;
            case HIERARCHY_DOWN:
                return isTarget || isDescendant;
            case HIERARCHY_UP:
                return isTarget || isAncestor;
            case ALL:
                return true;
            case NONE:
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return code;
    }
}
