package com.nomendi6.orgsec.constants;

/**
 * Enumeration representing the type of operation that can be performed
 * on a resource within the security system.
 *
 * @since 1.0.0
 */
public enum PrivilegeOperation {
    /**
     * No operation allowed.
     */
    NONE("NONE", "No operation"),

    /**
     * Read operation - view/retrieve data.
     */
    READ("READ", "Read access"),

    /**
     * Write operation - create/update/delete data.
     * Note: WRITE privilege implicitly includes READ privilege.
     */
    WRITE("WRITE", "Write access"),

    /**
     * Execute operation - perform actions/execute functions.
     */
    EXECUTE("EXECUTE", "Execute access");

    private final String code;
    private final String description;

    PrivilegeOperation(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Gets the code of the privilege operation.
     *
     * @return the privilege operation code
     */
    public String getCode() {
        return code;
    }

    /**
     * Gets the description of the privilege operation.
     *
     * @return the privilege operation description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Converts a string code to PrivilegeOperation enum.
     *
     * @param code the code to convert
     * @return the matching PrivilegeOperation or null if not found
     */
    public static PrivilegeOperation fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PrivilegeOperation operation : values()) {
            if (operation.code.equalsIgnoreCase(code)) {
                return operation;
            }
        }
        return null;
    }

    /**
     * Checks if this operation allows any access.
     *
     * @return true if operation is not NONE
     */
    public boolean allowsAccess() {
        return this != NONE;
    }

    /**
     * Checks if this operation allows read access.
     *
     * @return true if operation is READ, WRITE, or EXECUTE
     */
    public boolean allowsRead() {
        return this == READ || this == WRITE || this == EXECUTE;
    }

    /**
     * Checks if this operation allows write access.
     *
     * @return true if operation is WRITE
     */
    public boolean allowsWrite() {
        return this == WRITE;
    }

    /**
     * Checks if this operation allows execute access.
     *
     * @return true if operation is EXECUTE
     */
    public boolean allowsExecute() {
        return this == EXECUTE;
    }

    /**
     * Checks if this operation is more permissive than another.
     * WRITE is considered more permissive than READ.
     * EXECUTE is considered a separate permission level.
     *
     * @param other the operation to compare with
     * @return true if this operation grants more access than the other
     */
    public boolean isMorePermissiveThan(PrivilegeOperation other) {
        if (other == null || other == NONE) {
            return this != NONE;
        }
        if (this == WRITE) {
            return other == READ || other == NONE;
        }
        if (this == READ) {
            return other == NONE;
        }
        if (this == EXECUTE) {
            return other == NONE;
        }
        return false;
    }

    /**
     * Combines two operations and returns the most permissive one.
     * WRITE privilege includes READ privilege.
     *
     * @param other the operation to combine with
     * @return the combined operation
     */
    public PrivilegeOperation combine(PrivilegeOperation other) {
        if (other == null) {
            return this;
        }
        if (this == NONE) {
            return other;
        }
        if (other == NONE) {
            return this;
        }
        if (this == WRITE || other == WRITE) {
            return WRITE;
        }
        if (this == EXECUTE || other == EXECUTE) {
            return EXECUTE;
        }
        return READ;
    }

    @Override
    public String toString() {
        return code;
    }
}
