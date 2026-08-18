package com.nomendi6.orgsec.model;

import com.nomendi6.orgsec.constants.PrivilegeOperation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RoleDefConstructionTest {

    @Test
    void threeArgumentConstructorCreatesACompleteMutableRole() {
        Set<String> sourcePrivileges = new LinkedHashSet<>(Set.of("orders:read"));
        RoleDef role = new RoleDef(41L, "Order reader", sourcePrivileges);

        sourcePrivileges.add("orders:write");
        role.addBusinessRole("sales");
        role.addPrivilegeDef(privilege("orders", "orders:read"));

        assertThat(role.securityPrivilegeSet).containsExactly("orders:read");
        assertThat(role.businessRoles).containsExactly("sales");
        assertThat(role.resourcesMap)
            .containsOnlyKeys("orders")
            .doesNotContainValue(null);
    }

    private static PrivilegeDef privilege(String resource, String name) {
        PrivilegeDef privilege = new PrivilegeDef();
        privilege.resourceName = resource;
        privilege.name = name;
        privilege.operation = PrivilegeOperation.READ;
        return privilege;
    }
}
