package com.nomendi6.orgsec.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;

/**
 * Definition of a privilege in the security context.
 */
public class PrivilegeDef implements Serializable {

    @Size(max = 255, message = "Privilege name must not exceed 255 characters")
    public String name = null;

    @Size(max = 255, message = "Resource name must not exceed 255 characters")
    public String resourceName = null;

    @NotNull(message = "Operation is required")
    public PrivilegeOperation operation = PrivilegeOperation.NONE;

    public boolean all = false;

    public PrivilegeDirection company = PrivilegeDirection.NONE;
    public PrivilegeDirection org = PrivilegeDirection.NONE;
    public boolean person = false;

    public PrivilegeDef() {}

    public PrivilegeDef(String name, String resourceName) {
        this.name = name;
        this.resourceName = resourceName;
    }

    /**
     * Sets the operation privilege
     * @param operation The {@link PrivilegeOperation}
     * @return the reference to this object
     */
    public PrivilegeDef allowOperation(PrivilegeOperation operation) {
        this.operation = operation;
        return this;
    }

    /**
     * Sets allowAll flag on privilege.  This define that all organizations are allowed on the resource.
     * @param all The value for the flag
     * @return the reference to this object
     */
    public PrivilegeDef allowAll(boolean all) {
        this.all = all;
        return this;
    }

    /**
     * Sets the properties that define privilege on company, organization, en person
     * @param company The {@link PrivilegeDef} that define privilege on the company level
     * @param org The {@link PrivilegeDef} that define privilege on the organization level
     * @param person The boolean that defines if person should be checked
     * @return the reference to this object
     */
    public PrivilegeDef allowOrg(PrivilegeDirection company, PrivilegeDirection org, boolean person) {
        this.company = company;
        this.org = org;
        this.person = person;

        return this;
    }

    /**
     * Create a new privilege as a copy from another {@link PrivilegeDef} other
     * @param other the other privilege
     * @return the new privilege copied from other
     */
    static PrivilegeDef copyFrom(PrivilegeDef other) {
        PrivilegeDef newPrivilege = new PrivilegeDef(other.name, other.resourceName);

        newPrivilege.operation = other.operation;
        newPrivilege.all = other.all;

        newPrivilege.company = other.company;
        newPrivilege.org = other.org;
        newPrivilege.person = other.person;

        return newPrivilege;
    }

    /**
     * Add the other privilege to the current one
     *
     * @param other is the other privilege {@link PrivilegeDef}
     * @return the sum of two privileges
     */
    public PrivilegeDef add(PrivilegeDef other) {
        PrivilegeDef newPrivilege = new PrivilegeDef(this.name, this.resourceName);

        newPrivilege.operation = add(this.operation, other.operation);
        newPrivilege.all = this.all || other.all;

        if (!newPrivilege.all) {
            newPrivilege.company = add(this.company, other.company);
            if (newPrivilege.company == PrivilegeDirection.NONE) {
                newPrivilege.org = add(this.org, other.org);
                if (newPrivilege.org == PrivilegeDirection.ALL) {
                    newPrivilege.company = PrivilegeDirection.EXACT;
                    newPrivilege.org = PrivilegeDirection.NONE;
                } else {
                    if (newPrivilege.org == PrivilegeDirection.NONE) {
                        newPrivilege.person = this.person || other.person;
                    }
                }
            }
        }
        newPrivilege.resourceName = calculateResourceName(this.resourceName, other.resourceName);
        newPrivilege.name = calculatePrivilegeName(newPrivilege);
        return newPrivilege;
    }

    private String calculateResourceName(String thisResourceName, String otherResourceName) {
        if (thisResourceName == null) {
            return otherResourceName;
        }
        return thisResourceName;
    }

    /**
     * Calculate the name of the privilege based on privilege attributes.
     *
     * @param privilege the {@link PrivilegeDef} which name should be calculated.
     * @return the string with a new privilege name.
     */
    public String calculatePrivilegeName(PrivilegeDef privilege) {
        String name = privilege.resourceName;

        if (name == null) {
            return "NONE";
        }
        if (privilege.all) {
            name += "_ALL";
        } else {
            if (privilege.company == PrivilegeDirection.EXACT) {
                name += "_COMP";
            } else if (privilege.company == PrivilegeDirection.HIERARCHY_DOWN) {
                name += "_COMPHD";
            } else if (privilege.company == PrivilegeDirection.HIERARCHY_UP) {
                name += "_COMPHU";
            } else if (privilege.company == PrivilegeDirection.NONE) {
                if (privilege.org == PrivilegeDirection.EXACT) {
                    name += "_ORG";
                } else if (privilege.org == PrivilegeDirection.HIERARCHY_DOWN) {
                    name += "_ORGHD";
                } else if (privilege.org == PrivilegeDirection.HIERARCHY_UP) {
                    name += "_ORGHU";
                } else if (privilege.org == PrivilegeDirection.NONE) {
                    name += "_EMP";
                }
            }
        }

        if (privilege.operation == PrivilegeOperation.WRITE) {
            name += "_W";
        } else if (privilege.operation == PrivilegeOperation.READ) {
            name += "_R";
        }
        if (privilege.operation == PrivilegeOperation.EXECUTE) {
            name += "_";
        }

        return name;
    }

    /**
     * Add two {@link PrivilegeDirection} and get the result {@link PrivilegeDirection}
     * @param a The first {@link PrivilegeDirection} operand
     * @param b The second {@link PrivilegeDirection} operand
     * @return the result of addition
     */

    public PrivilegeDirection add(PrivilegeDirection a, PrivilegeDirection b) {
        if (a == b) {
            return a;
        }

        if (a == PrivilegeDirection.NONE) {
            return b;
        }

        if (b == PrivilegeDirection.NONE) {
            return a;
        }

        if (
            ((a == PrivilegeDirection.HIERARCHY_DOWN) && (b == PrivilegeDirection.HIERARCHY_UP)) ||
            ((a == PrivilegeDirection.HIERARCHY_UP) && (b == PrivilegeDirection.HIERARCHY_DOWN))
        ) {
            return PrivilegeDirection.ALL;
        }

        return PrivilegeDirection.EXACT;
    }

    /**
     * Add two {@link PrivilegeOperation} and get the result {@link PrivilegeOperation}.
     * WRITE privilege includes the READ privilege.
     *
     * @param a The first {@link PrivilegeOperation} operand
     * @param b The second {@link PrivilegeOperation} operand
     * @return the result of addition
     */
    public PrivilegeOperation add(PrivilegeOperation a, PrivilegeOperation b) {
        if (a == b) {
            return a;
        }

        if (a == PrivilegeOperation.NONE) {
            return b;
        }

        if (b == PrivilegeOperation.NONE) {
            return a;
        }

        if ((a == PrivilegeOperation.WRITE) || (b == PrivilegeOperation.WRITE)) {
            return PrivilegeOperation.WRITE;
        }
        return PrivilegeOperation.READ;
    }

    /**
     * Return the flag is all organizations are allowed
     * @return The flag
     */
    public boolean isAllowedAll() {
        return this.all;
    }

    /**
     * Return the flag if any organizations are allowed
     * @return the flag
     */
    public boolean isAllowedOrg() {
        return (this.company != PrivilegeDirection.NONE) || (this.org != PrivilegeDirection.NONE) || (!this.person);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PrivilegeDef)) return false;

        PrivilegeDef that = (PrivilegeDef) o;

        if (all != that.all) return false;
        if (person != that.person) return false;
        if (name != null ? !name.equals(that.name) : that.name != null) return false;
        if (resourceName != null ? !resourceName.equals(that.resourceName) : that.resourceName != null) return false;
        if (operation != that.operation) return false;
        if (company != that.company) return false;
        return org == that.org;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (resourceName != null ? resourceName.hashCode() : 0);
        result = 31 * result + (operation != null ? operation.hashCode() : 0);
        result = 31 * result + (all ? 1 : 0);
        result = 31 * result + (company != null ? company.hashCode() : 0);
        result = 31 * result + (org != null ? org.hashCode() : 0);
        result = 31 * result + (person ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return (
            "PrivilegeDef{" +
            "name='" +
            name +
            '\'' +
            ", resourceName='" +
            resourceName +
            '\'' +
            ", operation=" +
            operation +
            ", all=" +
            all +
            ", company=" +
            company +
            ", org=" +
            org +
            ", person=" +
            person +
            '}'
        );
    }
}
