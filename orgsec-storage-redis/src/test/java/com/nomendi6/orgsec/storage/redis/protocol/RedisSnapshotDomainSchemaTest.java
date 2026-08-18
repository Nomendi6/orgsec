package com.nomendi6.orgsec.storage.redis.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.model.RoleDef;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the domain surface consumed by the canonical Redis snapshot codec.
 *
 * <p>A domain field or enum constant is a wire-schema change. This test deliberately fails before
 * such a change can be silently omitted from, or automatically added to, an authorization snapshot.
 */
class RedisSnapshotDomainSchemaTest {

    @Test
    void personSchemaIsExplicit() {
        assertFields(
            PersonDef.class,
            "defaultCompanyId:java.lang.Long",
            "defaultOrgunitId:java.lang.Long",
            "organizationsMap:java.util.Map<java.lang.Long, com.nomendi6.orgsec.model.OrganizationDef>",
            "personId:java.lang.Long",
            "personName:java.lang.String",
            "relatedUserId:java.lang.String",
            "relatedUserLogin:java.lang.String"
        );
    }

    @Test
    void organizationSchemaIsExplicit() {
        assertFields(
            OrganizationDef.class,
            "businessRolesMap:java.util.Map<java.lang.String, com.nomendi6.orgsec.model.BusinessRoleDef>",
            "companyId:java.lang.Long",
            "companyLineageIds:java.util.List<java.lang.Long>",
            "companyParentPath:java.lang.String",
            "orgLineageIds:java.util.List<java.lang.Long>",
            "organizationId:java.lang.Long",
            "organizationName:java.lang.String",
            "organizationRolesSet:java.util.Set<com.nomendi6.orgsec.model.RoleDef>",
            "parentId:java.lang.Long",
            "parentPath:java.lang.String",
            "pathId:java.lang.String",
            "positionId:java.lang.Long",
            "positionRolesSet:java.util.Set<com.nomendi6.orgsec.model.RoleDef>"
        );
    }

    @Test
    void roleSchemaIsExplicit() {
        assertFields(
            RoleDef.class,
            "businessRoles:java.util.Set<java.lang.String>",
            "name:java.lang.String",
            "resourcesMap:java.util.Map<java.lang.String, com.nomendi6.orgsec.model.ResourceDef>",
            "roleId:java.lang.Long",
            "securityPrivilegeSet:java.util.Set<java.lang.String>"
        );
    }

    @Test
    void nestedBusinessRoleAndResourceSchemasAreExplicit() {
        assertFields(
            BusinessRoleDef.class,
            "allowAll:boolean",
            "businessRoleName:java.lang.String",
            "filter:java.lang.String",
            "resourcesMap:java.util.Map<java.lang.String, com.nomendi6.orgsec.model.ResourceDef>"
        );
        assertFields(
            ResourceDef.class,
            "aggregatedExecutePrivilege:com.nomendi6.orgsec.model.PrivilegeDef",
            "aggregatedReadPrivilege:com.nomendi6.orgsec.model.PrivilegeDef",
            "aggregatedWritePrivilege:com.nomendi6.orgsec.model.PrivilegeDef",
            "privilegesList:java.util.List<com.nomendi6.orgsec.model.PrivilegeDef>",
            "resourceName:java.lang.String"
        );
    }

    @Test
    void privilegeSchemaAndEnumTokensAreExplicit() {
        assertFields(
            PrivilegeDef.class,
            "all:boolean",
            "company:com.nomendi6.orgsec.constants.PrivilegeDirection",
            "name:java.lang.String",
            "operation:com.nomendi6.orgsec.constants.PrivilegeOperation",
            "org:com.nomendi6.orgsec.constants.PrivilegeDirection",
            "person:boolean",
            "resourceName:java.lang.String"
        );

        assertThat(Arrays.stream(PrivilegeOperation.values()).map(Enum::name)).containsExactly(
            "NONE",
            "READ",
            "WRITE",
            "EXECUTE"
        );
        assertThat(Arrays.stream(PrivilegeDirection.values()).map(Enum::name)).containsExactly(
            "NONE",
            "EXACT",
            "HIERARCHY_DOWN",
            "HIERARCHY_UP",
            "ALL"
        );
    }

    private static void assertFields(Class<?> type, String... expected) {
        List<String> actual = Arrays.stream(type.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .filter(field -> !field.isSynthetic())
            .map(RedisSnapshotDomainSchemaTest::describe)
            .sorted()
            .toList();

        assertThat(actual).containsExactly(expected);
    }

    private static String describe(Field field) {
        return field.getName() + ":" + field.getGenericType().getTypeName();
    }
}
