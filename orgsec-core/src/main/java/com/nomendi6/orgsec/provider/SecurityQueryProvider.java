package com.nomendi6.orgsec.provider;

import jakarta.persistence.Tuple;
import java.util.List;

/**
 * Provider interface for executing security-related database queries.
 * This interface abstracts the query execution layer, allowing the orgsec module
 * to be independent of specific JPA/database implementations.
 *
 * Implementations of this interface should handle all database queries
 * needed by the security module for loading and synchronizing security data.
 */
public interface SecurityQueryProvider {
    // ========== ORGANIZATION QUERIES ==========

    /**
     * Load all active parties (organizations) from the database.
     * @return List of Tuple containing party data
     */
    List<Tuple> loadAllParties();

    /**
     * Load a specific party by ID.
     * @param partyId the party ID
     * @return List of Tuple containing party data (should contain 0 or 1 element)
     */
    List<Tuple> loadPartyById(Long partyId);

    /**
     * Load all party assigned roles.
     * @return List of Tuple containing party-role assignments
     */
    List<Tuple> loadAllPartyAssignedRoles();

    /**
     * Load party assigned roles for a specific party.
     * @param partyId the party ID
     * @return List of Tuple containing party-role assignments
     */
    List<Tuple> loadPartyAssignedRolesByPartyId(Long partyId);

    /**
     * Load parties related to a specific role.
     * @param partyRoleId the role ID
     * @return List of Tuple containing party IDs
     */
    List<Tuple> loadPartiesRelatedToRole(Long partyRoleId);

    // ========== PERSON QUERIES ==========

    /**
     * Load all active persons from the database.
     * @return List of Tuple containing person data
     */
    List<Tuple> loadAllPersons();

    /**
     * Load a specific person by ID.
     * @param personId the person ID
     * @return List of Tuple containing person data (should contain 0 or 1 element)
     */
    List<Tuple> loadPersonById(Long personId);

    /**
     * Load a specific person by Keycloak user ID.
     * @param userId the Keycloak user UUID
     * @return List of Tuple containing person data (should contain 0 or 1 element)
     */
    default List<Tuple> loadPersonByUserId(String userId) {
        // Default implementation returns empty list
        // Applications should override this with actual database query
        return List.of();
    }

    /**
     * Load all person parties (person-organization relationships).
     * @return List of Tuple containing person-party data
     */
    List<Tuple> loadAllPersonParties();

    /**
     * Load person parties for a specific person.
     * @param personId the person ID
     * @return List of Tuple containing person-party data
     */
    List<Tuple> loadPersonPartiesByPersonId(Long personId);

    /**
     * Load all person party role assignments.
     * @return List of Tuple containing person-party-role data
     */
    List<Tuple> loadAllPersonPartyRoles();

    /**
     * Load person party roles for a specific person.
     * @param personId the person ID
     * @return List of Tuple containing person-party-role data
     */
    List<Tuple> loadPersonPartyRolesByPersonId(Long personId);

    /**
     * Load all person position roles.
     * @return List of Tuple containing person-position-role data
     */
    List<Tuple> loadAllPersonPositionRoles();

    /**
     * Load person position roles for a specific person.
     * @param personId the person ID
     * @return List of Tuple containing person-position-role data
     */
    List<Tuple> loadPersonPositionRolesByPersonId(Long personId);

    /**
     * Load persons related to a specific role.
     * @param partyRoleId the role ID
     * @return List of Tuple containing person IDs
     */
    List<Tuple> loadPersonsRelatedToRole(Long partyRoleId);

    /**
     * Load persons related to a specific party.
     * @param partyId the party ID
     * @return List of Tuple containing person IDs
     */
    List<Tuple> loadPersonsRelatedToParty(Long partyId);

    /**
     * Load persons related to a specific position.
     * @param positionId the position ID
     * @return List of Tuple containing person IDs
     */
    List<Tuple> loadPersonsRelatedToPosition(Long positionId);

    // ========== ROLE QUERIES ==========

    /**
     * Load all active party roles from the database.
     * @return List of Tuple containing party role data (id, code, owner, contractor, customer)
     */
    List<Tuple> loadAllPartyRoles();

    /**
     * Load a specific party role by ID.
     * @param partyRoleId the role ID
     * @return List of Tuple containing party role data (should contain 0 or 1 element)
     */
    List<Tuple> loadPartyRoleById(Long partyRoleId);

    /**
     * Load all party role assigned privileges.
     * @return List of Tuple containing privilege data
     */
    List<Tuple> loadAllPartyRolePrivileges();

    /**
     * Load party role privileges for a specific role.
     * @param roleId the role ID
     * @return List of Tuple containing privilege data
     */
    List<Tuple> loadPartyRolePrivilegesByRoleId(Long roleId);

    /**
     * Load all active position roles from the database.
     * @return List of Tuple containing position role data (id, code, owner, contractor, customer)
     */
    List<Tuple> loadAllPositionRoles();

    /**
     * Load a specific position role by ID.
     * @param roleId the role ID
     * @return List of Tuple containing position role data (should contain 0 or 1 element)
     */
    List<Tuple> loadPositionRoleById(Long roleId);

    /**
     * Load all position role assigned privileges.
     * @return List of Tuple containing privilege data
     */
    List<Tuple> loadAllPositionRolePrivileges();

    /**
     * Load position role privileges for a specific role.
     * @param roleId the role ID
     * @return List of Tuple containing privilege data
     */
    List<Tuple> loadPositionRolePrivilegesByRoleId(Long roleId);

    // ========== STRING-BASED PRIVILEGE QUERIES (FOR ORGSEC LIBRARY) ==========

    /**
     * Load all party role privileges as tuples with string privilege identifiers.
     * This method converts SecurityPrivilege enum to strings for orgsec library compatibility.
     * @return List of Tuple containing roleId and privilege as string
     */
    List<Tuple> loadAllPartyRolePrivilegesAsStrings();

    /**
     * Load party role privileges for a specific role as tuples with string privilege identifiers.
     * @param roleId the role ID
     * @return List of Tuple containing roleId and privilege as string
     */
    List<Tuple> loadPartyRolePrivilegesByRoleIdAsStrings(Long roleId);

    /**
     * Load all position role privileges as tuples with string privilege identifiers.
     * This method converts SecurityPrivilege enum to strings for orgsec library compatibility.
     * @return List of Tuple containing roleId and privilege as string
     */
    List<Tuple> loadAllPositionRolePrivilegesAsStrings();

    /**
     * Load position role privileges for a specific role as tuples with string privilege identifiers.
     * @param roleId the role ID
     * @return List of Tuple containing roleId and privilege as string
     */
    List<Tuple> loadPositionRolePrivilegesByRoleIdAsStrings(Long roleId);
}
