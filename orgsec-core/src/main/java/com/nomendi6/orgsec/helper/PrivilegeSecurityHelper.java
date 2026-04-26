package com.nomendi6.orgsec.helper;

import java.util.Map;
import org.apache.commons.lang3.SerializationUtils;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.model.RoleDef;

public class PrivilegeSecurityHelper {

    // Use only alphanumeric characters for security (no special characters that could be exploited)
    static String charSet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static int charSetLength = charSet.length();

    public static String longEncoder(Long number) {
        if (number == null || number < 0) {
            throw new IllegalArgumentException("Number must be non-null and non-negative");
        }

        StringBuilder encoded = new StringBuilder();
        int index = (int) (number % charSetLength);
        long newNumber = number / charSetLength;
        while (newNumber > 0) {
            encoded.insert(0, charSet.charAt(index));
            index = (int) (newNumber % charSetLength);
            newNumber = newNumber / charSetLength;
        }
        encoded.insert(0, charSet.charAt(index));

        // Validate the encoded result
        String result = encoded.toString();

        return result;
    }

    public static String longEncoderLpad(Long number, int length, char lpadChar) {
        String result = longEncoder(number);
        return String.format("%" + length + "s", result).replace(' ', lpadChar);
    }

    public static String longEncoderLpad(Long number, int length) {
        char lpadChar = charSet.charAt(0);
        return longEncoderLpad(number, length, lpadChar);
    }

    public static int getEncoderBase() {
        return charSetLength;
    }

    /**
     * Build resourceMap for specific businessRoles that are defined in roleDef.
     *
     * @param businessRolesMap The businessRolesMap that will contain businessRoles defined in roleDef
     * @param roleDef The roleDef contains roles and flags for related businessRoles
     */
    public static void buildBusinessRoleResourceMap(Map<String, BusinessRoleDef> businessRolesMap, RoleDef roleDef) {
        // Iterate through all business roles assigned to this role definition
        for (String businessRole : roleDef.getBusinessRoles()) {
            addResourceMapToBusinessRoles(businessRolesMap, businessRole, roleDef.resourcesMap);
        }
    }

    /**
     * Add resource map to a businessRoleName in businessRoles map.  If business role with businessRoleName does not exist in the map, a new business role is created.
     * For resources that already exist in the businessRoles privileges will be combined with the fromResourceMap.
     *
     * @param businessRolesMap The businessRoleMap is a map of businessRoles (owner, executor, supervisor, ...)
     * @param businessRoleName The businessRoleName is the name of the business role fromResourceMap will be added to
     * @param fromResourcesMap The fromResourceMap represent a map of resources that will be added to the business role.
     */
    public static void addResourceMapToBusinessRoles(
        Map<String, BusinessRoleDef> businessRolesMap,
        String businessRoleName,
        Map<String, ResourceDef> fromResourcesMap
    ) {
        final BusinessRoleDef businessRoleDef = businessRolesMap.get(businessRoleName);

        if (businessRoleDef == null) {
            BusinessRoleDef newBusinessRoleDef = new BusinessRoleDef(businessRoleName);
            // newBusinessRoleDef.resourcesMap.putAll(fromResourcesMap);
            fromResourcesMap.forEach((resourceName, resourceDef) -> {
                ResourceDef newResourceDef = SerializationUtils.clone(resourceDef);
                newBusinessRoleDef.resourcesMap.put(resourceName, newResourceDef);
            });

            businessRolesMap.put(businessRoleName, newBusinessRoleDef);
        } else {
            fromResourcesMap.forEach((s, resourceDef) -> businessRoleDef.addResourceDefinition(resourceDef));
        }
    }
}
