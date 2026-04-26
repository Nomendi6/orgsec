package com.nomendi6.orgsec.model;

import java.util.Collections;
import java.util.Set;
import com.nomendi6.orgsec.constants.SecurityFieldType;

/**
 * Configurable definition of a business role and its associated security fields.
 * This allows orgsec to work with arbitrary business roles instead of hardcoded ones.
 */
public class BusinessRoleDefinition {

    private final String name;
    private final Set<SecurityFieldType> supportedFields;

    public BusinessRoleDefinition(String name, Set<SecurityFieldType> supportedFields) {
        this.name = name;
        this.supportedFields = Collections.unmodifiableSet(supportedFields);
    }

    public String getName() {
        return name;
    }

    public Set<SecurityFieldType> getSupportedFields() {
        return supportedFields;
    }

    public boolean supportsField(SecurityFieldType fieldType) {
        return supportedFields.contains(fieldType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BusinessRoleDefinition)) return false;

        BusinessRoleDefinition that = (BusinessRoleDefinition) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return "BusinessRoleDefinition{" + "name='" + name + '\'' + ", supportedFields=" + supportedFields + '}';
    }
}
