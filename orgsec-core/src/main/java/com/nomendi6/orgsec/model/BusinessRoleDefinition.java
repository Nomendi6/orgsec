package com.nomendi6.orgsec.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.nomendi6.orgsec.constants.SecurityFieldType;

/**
 * Configurable definition of a business role and its associated security fields.
 * This allows orgsec to work with arbitrary business roles instead of hardcoded ones.
 */
public class BusinessRoleDefinition {

    private final String name;
    private final Set<SecurityFieldType> supportedFields;
    private final Map<SecurityFieldType, String> rsqlFields;

    public BusinessRoleDefinition(String name, Set<SecurityFieldType> supportedFields) {
        this(name, supportedFields, Collections.emptyMap());
    }

    public BusinessRoleDefinition(String name, Set<SecurityFieldType> supportedFields, Map<SecurityFieldType, String> rsqlFields) {
        this.name = name;
        this.supportedFields = Collections.unmodifiableSet(supportedFields != null ? Set.copyOf(supportedFields) : Collections.emptySet());

        EnumMap<SecurityFieldType, String> fields = new EnumMap<>(SecurityFieldType.class);
        if (rsqlFields != null) {
            fields.putAll(rsqlFields);
        }
        this.rsqlFields = Collections.unmodifiableMap(fields);
    }

    public String getName() {
        return name;
    }

    public Set<SecurityFieldType> getSupportedFields() {
        return supportedFields;
    }

    public Map<SecurityFieldType, String> getRsqlFields() {
        return rsqlFields;
    }

    public Optional<String> getRsqlField(SecurityFieldType fieldType) {
        return Optional.ofNullable(rsqlFields.get(fieldType));
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
        return "BusinessRoleDefinition{" +
            "name='" + name + '\'' +
            ", supportedFields=" + supportedFields +
            ", rsqlFields=" + rsqlFields +
            '}';
    }
}
