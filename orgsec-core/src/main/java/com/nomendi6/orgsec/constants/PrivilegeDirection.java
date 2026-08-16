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
     * Not a valid {@code company} or {@code org} scope.
     * <p>
     * Unrestricted access is expressed by the separate {@link com.nomendi6.orgsec.model.PrivilegeDef#all}
     * flag, which the privilege loader sets for the {@code ALL} scope code - it never assigns this
     * value to an axis. No evaluator implements it either: {@code PrivilegeChecker} and
     * {@code RsqlFilterBuilder} both deny a privilege whose axis is {@code ALL}.
     * <p>
     * The value is retained because it is part of the published API and because
     * {@link PrivilegeScope#toDirection()} maps the {@code ALL} scope onto it. Do not set it on a
     * {@code PrivilegeDef}: the result is a privilege that grants nothing.
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
     * <p>This is a property of the enum value, not a prediction of what an evaluator will do.
     * {@link #ALL} answers {@code true} here and is nevertheless denied by both
     * {@code PrivilegeChecker} and {@code RsqlFilterBuilder} - see {@link #ALL}. Code deciding
     * whether a hierarchy comparison should run must test for {@link #HIERARCHY_DOWN} or
     * {@link #HIERARCHY_UP} explicitly rather than calling this.
     *
     * <p>Note also that {@link PrivilegeScope#isHierarchical()} answers a narrower question - the
     * scope enum has no {@code ALL}-with-hierarchy member - so the two methods do not agree for every
     * input. That divergence is left as it is; changing either would move an authorization boundary.
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
     * @deprecated Unused by the library and misleading: it reports {@code true} for {@code ALL},
     *     which no evaluator treats as a grant. Compare against {@link #HIERARCHY_DOWN} directly.
     *     Scheduled for removal in 2.1.
     */
    @Deprecated(since = "1.0.4", forRemoval = true)
    public boolean includesDown() {
        return this == HIERARCHY_DOWN || this == ALL;
    }

    /**
     * Checks if this direction includes upward hierarchy.
     *
     * @return true if this is HIERARCHY_UP or ALL
     * @deprecated Unused by the library and misleading: it reports {@code true} for {@code ALL},
     *     which no evaluator treats as a grant. Compare against {@link #HIERARCHY_UP} directly.
     *     Scheduled for removal in 2.1.
     */
    @Deprecated(since = "1.0.4", forRemoval = true)
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
     * <p>Nothing in the library calls this; it describes the enum's abstract algebra and is not the
     * authorization decision. In particular it answers {@code true} unconditionally for {@link #ALL},
     * which no evaluator grants on. An application using it to gate access would grant where OrgSec
     * itself denies.
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
