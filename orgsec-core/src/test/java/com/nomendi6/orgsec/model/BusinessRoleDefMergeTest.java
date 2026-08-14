package com.nomendi6.orgsec.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.helper.PrivilegeSecurityHelper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Merging several roles into one business role must never write into the roles it merges from.
 *
 * <p>The role store hands out {@link RoleDef} instances without copying them, so a
 * {@link ResourceDef} reached from a role is shared process-wide. {@code addResourceDefinition}
 * used to store the source privileges list by reference, and the merge branch then appended into
 * that same list - permanently adding one role's privileges to another role's, for every principal
 * holding it.
 *
 * <p>The order of the three merges is enforced explicitly. It matters, and in production it comes
 * from {@code HashSet}/{@code HashMap} iteration, so it is not reproducible on its own: the first
 * role processed goes down the deep-clone branch of
 * {@link PrivilegeSecurityHelper#addResourceMapToBusinessRoles}, which makes every resource it
 * carries permanently safe. Only a resource introduced by a later role can be aliased, and the
 * pollution needs a third role carrying that same resource.
 */
class BusinessRoleDefMergeTest {

    private static final String BUSINESS_ROLE = "owner";
    private static final String RESOURCE_X = "DOCUMENT";
    private static final String RESOURCE_Y = "PAYMENT_ORDER";

    private static RoleDef roleWith(String resourceName, String privilegeName, PrivilegeDirection org) {
        RoleDef role = new RoleDef();
        role.addPrivilegeDef(
            new PrivilegeDef(privilegeName, resourceName)
                .allowOperation(PrivilegeOperation.EXECUTE)
                .allowOrg(PrivilegeDirection.NONE, org, false)
        );
        return role;
    }

    /**
     * Role A introduces resource X, so the business role is created and X is deep-cloned. Role B
     * then introduces resource Y - the branch that used to alias. Role C carries Y as well, so the
     * merge branch appends into whatever list B's ResourceDef installed.
     */
    @Test
    void shouldNotWriteIntoTheSourceRoleWhenALaterRoleCarriesTheSameResource() {
        RoleDef roleA = roleWith(RESOURCE_X, "DOCUMENT_ORGHD_E", PrivilegeDirection.HIERARCHY_DOWN);
        RoleDef roleB = roleWith(RESOURCE_Y, "PAYMENT_ORDER_ORGHD_E", PrivilegeDirection.HIERARCHY_DOWN);
        RoleDef roleC = roleWith(RESOURCE_Y, "PAYMENT_ORDER_ORGHU_E", PrivilegeDirection.HIERARCHY_UP);

        ResourceDef bResource = roleB.resourcesMap.get(RESOURCE_Y);
        assertThat(bResource.getPrivilegesList()).hasSize(1);

        Map<String, BusinessRoleDef> businessRoles = new HashMap<>();
        PrivilegeSecurityHelper.addResourceMapToBusinessRoles(businessRoles, BUSINESS_ROLE, roleA.resourcesMap);
        PrivilegeSecurityHelper.addResourceMapToBusinessRoles(businessRoles, BUSINESS_ROLE, roleB.resourcesMap);
        PrivilegeSecurityHelper.addResourceMapToBusinessRoles(businessRoles, BUSINESS_ROLE, roleC.resourcesMap);

        assertThat(bResource.getPrivilegesList())
            .as("role B's own privileges list must not have grown - role C's privilege leaked into it")
            .hasSize(1);
        assertThat(bResource.getPrivilegesList().get(0).name).isEqualTo("PAYMENT_ORDER_ORGHD_E");
    }

    /** The business role's list must be a distinct instance, not the source role's list. */
    @Test
    void shouldGiveTheBusinessRoleItsOwnPrivilegesListInstance() {
        RoleDef roleA = roleWith(RESOURCE_X, "DOCUMENT_ORGHD_E", PrivilegeDirection.HIERARCHY_DOWN);
        RoleDef roleB = roleWith(RESOURCE_Y, "PAYMENT_ORDER_ORGHD_E", PrivilegeDirection.HIERARCHY_DOWN);

        Map<String, BusinessRoleDef> businessRoles = new HashMap<>();
        PrivilegeSecurityHelper.addResourceMapToBusinessRoles(businessRoles, BUSINESS_ROLE, roleA.resourcesMap);
        PrivilegeSecurityHelper.addResourceMapToBusinessRoles(businessRoles, BUSINESS_ROLE, roleB.resourcesMap);

        ResourceDef merged = businessRoles.get(BUSINESS_ROLE).resourcesMap.get(RESOURCE_Y);
        assertThat(merged.getPrivilegesList())
            .as("the business role must own its list, not share the source role's")
            .isNotSameAs(roleB.resourcesMap.get(RESOURCE_Y).getPrivilegesList());
    }

    /** The defensive copy must not break the merge itself: both privileges have to arrive. */
    @Test
    void shouldStillCollectPrivilegesFromEveryMergedRole() {
        RoleDef roleA = roleWith(RESOURCE_X, "DOCUMENT_ORGHD_E", PrivilegeDirection.HIERARCHY_DOWN);
        RoleDef roleB = roleWith(RESOURCE_Y, "PAYMENT_ORDER_ORGHD_E", PrivilegeDirection.HIERARCHY_DOWN);
        RoleDef roleC = roleWith(RESOURCE_Y, "PAYMENT_ORDER_ORGHU_E", PrivilegeDirection.HIERARCHY_UP);

        Map<String, BusinessRoleDef> businessRoles = new HashMap<>();
        PrivilegeSecurityHelper.addResourceMapToBusinessRoles(businessRoles, BUSINESS_ROLE, roleA.resourcesMap);
        PrivilegeSecurityHelper.addResourceMapToBusinessRoles(businessRoles, BUSINESS_ROLE, roleB.resourcesMap);
        PrivilegeSecurityHelper.addResourceMapToBusinessRoles(businessRoles, BUSINESS_ROLE, roleC.resourcesMap);

        ResourceDef merged = businessRoles.get(BUSINESS_ROLE).resourcesMap.get(RESOURCE_Y);
        assertThat(merged.getPrivilegesList())
            .extracting(privilege -> privilege.name)
            .containsExactlyInAnyOrder("PAYMENT_ORDER_ORGHD_E", "PAYMENT_ORDER_ORGHU_E");
    }
}
