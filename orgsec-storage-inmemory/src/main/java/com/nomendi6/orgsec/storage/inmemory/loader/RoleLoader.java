package com.nomendi6.orgsec.storage.inmemory.loader;

import jakarta.persistence.Tuple;
import java.util.*;
import org.springframework.stereotype.Service;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.inmemory.store.AllPrivilegesStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;

/**
 * Service responsible for processing role data from query results.
 * This loader focuses on data processing logic while query execution
 * is handled externally.
 */
@Service
public class RoleLoader {

    private final AllPrivilegesStore allPrivilegesStore;
    private final AllRolesStore allRolesStore;

    public RoleLoader(AllPrivilegesStore allPrivilegesStore, AllRolesStore allRolesStore) {
        this.allPrivilegesStore = allPrivilegesStore;
        this.allRolesStore = allRolesStore;
    }

    /**
     * Load all roles from query results and store them in AllRolesStore.
     *
     * @param partyRoles List of Tuple containing party role data from database query
     * @param partyRolePrivileges List of Tuple containing roleId and privilege string
     * @param positionRoles List of Tuple containing position role data from database query
     * @param positionRolePrivileges List of Tuple containing roleId and privilege string
     */
    public void loadRolesFromQueryResults(
        List<Tuple> partyRoles,
        List<Tuple> partyRolePrivileges,
        List<Tuple> positionRoles,
        List<Tuple> positionRolePrivileges
    ) {
        processPartyRoles(partyRoles, partyRolePrivileges);
        processPositionRoles(positionRoles, positionRolePrivileges);
    }

    /**
     * Synchronize party role (organizational role) with a given id.
     *
     * @param partyRoleId - given role id
     * @param partyRoles - List of Tuple containing party role data (should contain 0 or 1 element)
     * @param privileges - List of Tuple containing roleId and privilege string
     */
    public void syncPartyRole(Long partyRoleId, List<Tuple> partyRoles, List<Tuple> privileges) {
        Optional<RoleDef> partyRole = processPartyRoleFromQueryResult(partyRoles, privileges);
        replacePartyRole(partyRoleId, partyRole);
    }

    /**
     * Synchronize position role with a given id.
     *
     * @param roleId - given role id
     * @param positionRoles - List of Tuple containing position role data (should contain 0 or 1 element)
     * @param privileges - List of Tuple containing roleId and privilege string
     */
    public void syncPositionRole(Long roleId, List<Tuple> positionRoles, List<Tuple> privileges) {
        Optional<RoleDef> positionRole = processPositionRoleFromQueryResult(positionRoles, privileges);
        replacePositionRole(roleId, positionRole);
    }

    /**
     * Process party roles from query results and store them in AllRolesStore.
     *
     * @param rolesFromDb List of Tuple containing party role data from database query
     * @param privilegesFromDb List of Tuple containing roleId and privilege string
     */
    private void processPartyRoles(List<Tuple> rolesFromDb, List<Tuple> privilegesFromDb) {
        Map<Long, RoleDef> workMap = new HashMap<>();
        RoleDef def;

        // add all PartyRoles from Tuple results
        for (Tuple roleTuple : rolesFromDb) {
            Long roleId = roleTuple.get("id", Long.class);
            String roleCode = roleTuple.get("code", String.class);
            Boolean isOwner = roleTuple.get("owner", Boolean.class);
            Boolean isContractor = roleTuple.get("contractor", Boolean.class);
            Boolean isCustomer = roleTuple.get("customer", Boolean.class);
            Boolean isPartner = roleTuple.get("partner", Boolean.class);

            def = new RoleDef(roleId, roleCode.toLowerCase());

            // Configure business role properties dynamically
            if (Boolean.TRUE.equals(isOwner)) {
                def.addBusinessRole("owner");
            }
            if (Boolean.TRUE.equals(isContractor)) {
                def.addBusinessRole("contractor");
            }
            if (Boolean.TRUE.equals(isCustomer)) {
                def.addBusinessRole("customer");
            }
            if (Boolean.TRUE.equals(isPartner)) {
                def.addBusinessRole("partner");
            }

            workMap.put(roleId, def);
        }

        // add all PartyRole privileges to roles in the map
        addPrivilegesToRoles(workMap, privilegesFromDb);

        // process PartyRole roles definition
        processRoleDefinitions(workMap);

        // assign the map to store
        allRolesStore.setOrganizationRolesMap(workMap);
    }

    /**
     * Process position roles from query results and store them in AllRolesStore.
     *
     * @param rolesFromDb List of Tuple containing position role data from database query
     * @param privilegesFromDb List of Tuple containing roleId and privilege string
     */
    private void processPositionRoles(List<Tuple> rolesFromDb, List<Tuple> privilegesFromDb) {
        Map<Long, RoleDef> workMap = new HashMap<>();
        RoleDef def;

        // Process position roles from Tuple results
        for (Tuple roleTuple : rolesFromDb) {
            Long roleId = roleTuple.get("id", Long.class);
            String roleCode = roleTuple.get("code", String.class);
            Boolean isOwner = roleTuple.get("owner", Boolean.class);
            Boolean isContractor = roleTuple.get("contractor", Boolean.class);
            Boolean isCustomer = roleTuple.get("customer", Boolean.class);
            Boolean isPartner = roleTuple.get("partner", Boolean.class);

            def = new RoleDef(roleId, roleCode.toLowerCase());

            // Configure business role properties dynamically
            if (Boolean.TRUE.equals(isOwner)) {
                def.addBusinessRole("owner");
            }
            if (Boolean.TRUE.equals(isContractor)) {
                def.addBusinessRole("contractor");
            }
            if (Boolean.TRUE.equals(isCustomer)) {
                def.addBusinessRole("customer");
            }
            if (Boolean.TRUE.equals(isPartner)) {
                def.addBusinessRole("partner");
            }

            workMap.put(roleId, def);
        }

        // add all privileges to the roles map
        addPrivilegesToPositionRoles(workMap, privilegesFromDb);

        // process role definitions
        processRoleDefinitions(workMap);

        // assign the map to store
        allRolesStore.setPositionRolesMap(workMap);
    }

    /**
     * Add privileges to party roles
     */
    private void addPrivilegesToRoles(Map<Long, RoleDef> workMap, List<Tuple> privilegesFromDb) {
        Long currentId = Long.MIN_VALUE;
        Set<String> privilegeSet = new HashSet<>();
        RoleDef def;

        for (Tuple privilege : privilegesFromDb) {
            Long roleId = privilege.get("roleId", Long.class);
            String privilegeStr = privilege.get("privilege", String.class);

            if (roleId != null) {
                if (!roleId.equals(currentId)) {
                    // new role
                    // save the current privilegeSet if relevant
                    if (currentId > Long.MIN_VALUE) {
                        def = workMap.get(currentId);
                        if (def != null) {
                            def.addSecurityPrivilegeSet(privilegeSet);
                        } else {
                            throw new IllegalArgumentException("RoleId " + currentId + " not found");
                        }
                    }
                    // new privilegeSet
                    currentId = roleId;
                    privilegeSet.clear();
                }
                if (privilegeStr != null) {
                    privilegeSet.add(privilegeStr);
                }
            }
        }
        // save the last privilegeSet if relevant
        if (currentId > Long.MIN_VALUE) {
            def = workMap.get(currentId);
            if (def != null) {
                def.addSecurityPrivilegeSet(privilegeSet);
            } else {
                throw new IllegalArgumentException("RoleId " + currentId + " not found");
            }
        }
    }

    /**
     * Add privileges to position roles
     */
    private void addPrivilegesToPositionRoles(Map<Long, RoleDef> workMap, List<Tuple> privilegesFromDb) {
        Long currentId = Long.MIN_VALUE;
        Set<String> privilegeSet = new HashSet<>();
        RoleDef def;

        for (Tuple privilege : privilegesFromDb) {
            Long roleId = privilege.get("roleId", Long.class);
            String privilegeStr = privilege.get("privilege", String.class);

            if (roleId != null) {
                if (!roleId.equals(currentId)) {
                    // new role
                    // save the current privilegeSet if relevant
                    if (currentId > Long.MIN_VALUE) {
                        def = workMap.get(currentId);
                        if (def != null) {
                            def.addSecurityPrivilegeSet(privilegeSet);
                        } else {
                            throw new IllegalArgumentException("RoleId " + currentId + " not found");
                        }
                    }
                    // new privilegeSet
                    currentId = roleId;
                    privilegeSet.clear();
                }
                if (privilegeStr != null) {
                    privilegeSet.add(privilegeStr);
                }
            }
        }

        // save the last privilegeSet if relevant
        if (currentId > Long.MIN_VALUE) {
            def = workMap.get(currentId);
            if (def != null) {
                def.addSecurityPrivilegeSet(privilegeSet);
            } else {
                throw new IllegalArgumentException("RoleId " + currentId + " not found");
            }
        }
    }

    /**
     * Process role definitions by adding privilege definitions
     */
    private void processRoleDefinitions(Map<Long, RoleDef> workMap) {
        for (Map.Entry<Long, RoleDef> entry : workMap.entrySet()) {
            RoleDef role = entry.getValue();
            for (String privilege : role.securityPrivilegeSet) {
                PrivilegeDef privilegeDef = allPrivilegesStore.getPrivilege(privilege);
                role.addPrivilegeDef(privilegeDef);
            }
        }
    }

    /**
     * Replace partyRole in store if partyRole is defined.
     * If partyRole is empty and previous value exists in the store then it is deleted.
     *
     * @param partyRoleId - given party role id
     * @param partyRole - party role object with privileges
     */
    private void replacePartyRole(Long partyRoleId, Optional<RoleDef> partyRole) {
        partyRole.ifPresentOrElse(
            role -> allRolesStore.putOrganizationRole(partyRoleId, role),
            () -> allRolesStore.removeOrganizationRole(partyRoleId)
        );
    }

    /**
     * Replace position role in store
     */
    private void replacePositionRole(Long roleId, Optional<RoleDef> positionRole) {
        positionRole.ifPresentOrElse(role -> allRolesStore.putPositionRole(roleId, role), () -> allRolesStore.removePositionRole(roleId));
    }

    /**
     * Process specific party role from query results
     *
     * @param rolesFromDb - List of Tuple containing party role data (should contain 0 or 1 element)
     * @param privilegesFromDb - List of Tuple containing roleId and privilege string
     * @return Optional.empty if role is not found or the RoleDef for the given role id
     */
    private Optional<RoleDef> processPartyRoleFromQueryResult(List<Tuple> rolesFromDb, List<Tuple> privilegesFromDb) {
        if (rolesFromDb != null && !rolesFromDb.isEmpty()) {
            Tuple roleTuple = rolesFromDb.get(0);
            Long roleId = roleTuple.get("id", Long.class);
            String roleCode = roleTuple.get("code", String.class);
            Boolean isOwner = roleTuple.get("owner", Boolean.class);
            Boolean isContractor = roleTuple.get("contractor", Boolean.class);
            Boolean isCustomer = roleTuple.get("customer", Boolean.class);
            Boolean isPartner = roleTuple.get("partner", Boolean.class);

            RoleDef def = new RoleDef(roleId, roleCode.toLowerCase());

            // Configure business role properties dynamically
            if (Boolean.TRUE.equals(isOwner)) {
                def.addBusinessRole("owner");
            }
            if (Boolean.TRUE.equals(isContractor)) {
                def.addBusinessRole("contractor");
            }
            if (Boolean.TRUE.equals(isCustomer)) {
                def.addBusinessRole("customer");
            }
            if (Boolean.TRUE.equals(isPartner)) {
                def.addBusinessRole("partner");
            }

            Set<String> privilegeSet = new HashSet<>();

            for (Tuple privilege : privilegesFromDb) {
                String privilegeStr = privilege.get("privilege", String.class);
                if (privilegeStr != null) {
                    privilegeSet.add(privilegeStr);
                }
            }
            def.addSecurityPrivilegeSet(privilegeSet);

            // process party role definitions
            for (String privilege : privilegeSet) {
                PrivilegeDef privilegeDef = allPrivilegesStore.getPrivilege(privilege);
                def.addPrivilegeDef(privilegeDef);
            }

            return Optional.of(def);
        }

        return Optional.empty();
    }

    /**
     * Process specific position role from query results
     *
     * @param rolesFromDb - List of Tuple containing position role data (should contain 0 or 1 element)
     * @param privilegesFromDb - List of Tuple containing roleId and privilege string
     * @return Optional.empty if role is not found or the RoleDef for the given role id
     */
    private Optional<RoleDef> processPositionRoleFromQueryResult(List<Tuple> rolesFromDb, List<Tuple> privilegesFromDb) {
        if (rolesFromDb != null && !rolesFromDb.isEmpty()) {
            Tuple roleTuple = rolesFromDb.get(0);
            Long roleId = roleTuple.get("id", Long.class);
            String roleCode = roleTuple.get("code", String.class);
            Boolean isOwner = roleTuple.get("owner", Boolean.class);
            Boolean isContractor = roleTuple.get("contractor", Boolean.class);
            Boolean isCustomer = roleTuple.get("customer", Boolean.class);
            Boolean isPartner = roleTuple.get("partner", Boolean.class);

            RoleDef def = new RoleDef(roleId, roleCode.toLowerCase());

            // Configure business role properties dynamically
            if (Boolean.TRUE.equals(isOwner)) {
                def.addBusinessRole("owner");
            }
            if (Boolean.TRUE.equals(isContractor)) {
                def.addBusinessRole("contractor");
            }
            if (Boolean.TRUE.equals(isCustomer)) {
                def.addBusinessRole("customer");
            }
            if (Boolean.TRUE.equals(isPartner)) {
                def.addBusinessRole("partner");
            }

            Set<String> privilegeSet = new HashSet<>();

            for (Tuple privilege : privilegesFromDb) {
                String privilegeStr = privilege.get("privilege", String.class);
                if (privilegeStr != null) {
                    privilegeSet.add(privilegeStr);
                }
            }
            def.addSecurityPrivilegeSet(privilegeSet);

            // process party role definitions
            for (String privilege : privilegeSet) {
                PrivilegeDef privilegeDef = allPrivilegesStore.getPrivilege(privilege);
                def.addPrivilegeDef(privilegeDef);
            }

            return Optional.of(def);
        }

        return Optional.empty();
    }
}
