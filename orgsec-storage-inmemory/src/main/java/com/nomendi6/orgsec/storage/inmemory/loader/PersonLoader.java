package com.nomendi6.orgsec.storage.inmemory.loader;

import jakarta.persistence.Tuple;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.nomendi6.orgsec.helper.PathSanitizer;
import com.nomendi6.orgsec.helper.PrivilegeSecurityHelper;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;

/**
 * Service responsible for processing person data from query results.
 * This loader focuses on data processing logic while query execution
 * is handled externally.
 */
@Service
public class PersonLoader {

    private static final Logger log = LoggerFactory.getLogger(PersonLoader.class);

    private final AllRolesStore allRolesStore;
    private final AllPersonsStore allPersonsStore;

    public PersonLoader(AllRolesStore allRolesStore, AllPersonsStore allPersonsStore) {
        this.allRolesStore = allRolesStore;
        this.allPersonsStore = allPersonsStore;
    }

    /**
     * Load all persons from query results with related roles and security definitions and store them in AllPersonsStore.
     *
     * @param persons List of Tuples containing person data
     * @param personParties List of Tuples containing person-party relationships
     * @param personPartyRoles List of Tuples containing person-party-role assignments
     * @param personPositionRoles List of Tuples containing person-position-role assignments
     */
    public void loadPersonsFromQueryResults(
        List<Tuple> persons,
        List<Tuple> personParties,
        List<Tuple> personPartyRoles,
        List<Tuple> personPositionRoles
    ) {
        Map<Long, PersonDef> workPersonsMap = new HashMap<>();

        processPersons(persons, workPersonsMap);
        processPersonParties(personParties, workPersonsMap);
        processPersonPartyRoles(personPartyRoles, workPersonsMap);
        processPersonPositionRoles(personPositionRoles, workPersonsMap);
        buildOrganizationAndPositionBusinessRoles(workPersonsMap);

        allPersonsStore.setPersonsMap(workPersonsMap);
        log.debug("Loaded {} persons", workPersonsMap.size());
    }

    /**
     * Synchronize a person defined by personId in persons store
     *
     * @param personId - given person id
     * @param person - Tuple containing person data (can be null/empty)
     * @param personParties - List of Tuples containing person-party relationships
     * @param personPartyRoles - List of Tuples containing person-party-role assignments
     * @param personPositionRoles - List of Tuples containing person-position-role assignments
     */
    public void syncPerson(
        Long personId,
        List<Tuple> person,
        List<Tuple> personParties,
        List<Tuple> personPartyRoles,
        List<Tuple> personPositionRoles
    ) {
        Optional<PersonDef> personDef = processPersonFromQueryResult(person, personParties, personPartyRoles, personPositionRoles);
        replacePerson(personId, personDef);
    }

    /**
     * Get person IDs that need synchronization based on role.
     * Note: Actual synchronization should be handled by the caller using syncPerson method.
     *
     * @param relatedPersons - List of Tuples containing person IDs related to a role
     * @return List of person IDs that should be synchronized
     */
    public List<Long> getPersonsForSyncByRole(List<Tuple> relatedPersons) {
        List<Long> personIds = new java.util.ArrayList<>();
        if (relatedPersons != null && !relatedPersons.isEmpty()) {
            for (Tuple person : relatedPersons) {
                personIds.add(person.get("personId", Long.class));
            }
        }
        return personIds;
    }

    /**
     * Get person IDs that need synchronization based on party.
     * Note: Actual synchronization should be handled by the caller using syncPerson method.
     *
     * @param relatedPersons - List of Tuples containing person IDs related to a party
     * @return List of person IDs that should be synchronized
     */
    public List<Long> getPersonsForSyncByParty(List<Tuple> relatedPersons) {
        List<Long> personIds = new java.util.ArrayList<>();
        if (relatedPersons != null && !relatedPersons.isEmpty()) {
            for (Tuple person : relatedPersons) {
                personIds.add(person.get("personId", Long.class));
            }
        }
        return personIds;
    }

    /**
     * Get person IDs that need synchronization based on position.
     * Note: Actual synchronization should be handled by the caller using syncPerson method.
     *
     * @param relatedPersons - List of Tuples containing person IDs related to a position
     * @return List of person IDs that should be synchronized
     */
    public List<Long> getPersonsForSyncByPosition(List<Tuple> relatedPersons) {
        List<Long> personIds = new java.util.ArrayList<>();
        if (relatedPersons != null && !relatedPersons.isEmpty()) {
            for (Tuple person : relatedPersons) {
                personIds.add(person.get("personId", Long.class));
            }
        }
        return personIds;
    }

    /**
     * Process workPersonsMap and build business role privileges for each person.
     *
     * @param workPersonsMap The workPersonsMap with persons and related privileges
     */
    private void buildOrganizationAndPositionBusinessRoles(Map<Long, PersonDef> workPersonsMap) {
        workPersonsMap.forEach((aLong, personDef) -> {
            buildOrganizationBusinessRoles(personDef);
            buildPositionBusinessRoles(personDef);
        });
    }

    /**
     * Build businessRoles from organization roles
     *
     * @param personDef The personDef that contains organizations with roles.
     */
    private void buildOrganizationBusinessRoles(PersonDef personDef) {
        for (Map.Entry<Long, OrganizationDef> entry : personDef.organizationsMap.entrySet()) {
            OrganizationDef organizationDef = entry.getValue();

            organizationDef.organizationRolesSet.forEach(roleDefinition ->
                PrivilegeSecurityHelper.buildBusinessRoleResourceMap(organizationDef.businessRolesMap, roleDefinition)
            );
        }
    }

    /**
     * Build businessRoles from position roles.
     *
     * @param personDef The personDef that contains organizations with position roles.
     */
    private void buildPositionBusinessRoles(PersonDef personDef) {
        for (Map.Entry<Long, OrganizationDef> entry : personDef.organizationsMap.entrySet()) {
            OrganizationDef organizationDef = entry.getValue();

            organizationDef.positionRolesSet.forEach(roleDefinition ->
                PrivilegeSecurityHelper.buildBusinessRoleResourceMap(organizationDef.businessRolesMap, roleDefinition)
            );
        }
    }

    /**
     * Process person position roles from query results and add them to workPersonsMap
     *
     * @param personPositionRoles List of Tuples containing person-position-role data
     * @param workPersonsMap is the map of persons being built
     */
    private void processPersonPositionRoles(List<Tuple> personPositionRoles, Map<Long, PersonDef> workPersonsMap) {
        for (Tuple role : personPositionRoles) {
            Long empId = role.get("empId", Long.class);
            Long orgunitId = role.get("orgunitId", Long.class);
            Long roleId = role.get("roleId", Long.class);

            RoleDef roleDef = allRolesStore.getPositionRole(roleId);
            workPersonsMap.get(empId).organizationsMap.get(orgunitId).addPositionRole(roleDef);
        }
    }

    /**
     * Process person party roles from query results and add them to workPersonsMap
     *
     * @param personPartyRoles List of Tuples containing person-party-role data
     * @param workPersonsMap is the map of persons being built
     */
    private void processPersonPartyRoles(List<Tuple> personPartyRoles, Map<Long, PersonDef> workPersonsMap) {
        for (Tuple role : personPartyRoles) {
            Long empId = role.get("empId", Long.class);
            Long orgunitId = role.get("orgunitId", Long.class);
            Long roleId = role.get("roleId", Long.class);

            RoleDef roleDef = allRolesStore.getOrganizationRole(roleId);
            workPersonsMap.get(empId).organizationsMap.get(orgunitId).addOrganizationRole(roleDef);
        }
    }

    /**
     * Process person parties from query results and add them to workPersonsMap
     *
     * @param personParties List of Tuples containing person-party data
     * @param workPersonsMap The workPersonsMap that data will be added to
     */
    private void processPersonParties(List<Tuple> personParties, Map<Long, PersonDef> workPersonsMap) {
        for (Tuple personParty : personParties) {
            Long personId = personParty.get("empId", Long.class);
            OrganizationDef organizationDef = new OrganizationDef(
                personParty.get("orgunitName", String.class),
                personParty.get("orgunitId", Long.class),
                personParty.get("positionId", Long.class),
                PathSanitizer.sanitizePath(personParty.get("pathId", String.class)),
                PathSanitizer.sanitizePath(personParty.get("parentPath", String.class)),
                personParty.get("companyId", Long.class),
                PathSanitizer.sanitizePath(personParty.get("companyParentPath", String.class))
            );
            if (personId != null) {
                workPersonsMap.get(personId).organizationsMap.put(organizationDef.organizationId, organizationDef);
            }
        }
    }

    /**
     * Process persons from query results and add them to the workPersonsMap.
     *
     * @param persons List of Tuples containing person data
     * @param workPersonsMap The workPersonsMap that data will be added to
     */
    private void processPersons(List<Tuple> persons, Map<Long, PersonDef> workPersonsMap) {
        for (Tuple person : persons) {
            final PersonDef personDef = new PersonDef(person.get("id", Long.class), person.get("name", String.class))
                .setDefaultCompanyId(person.get("dfltCompanyId", Long.class))
                .setDefaultOrgunitId(person.get("dfltOrgunitId", Long.class))
                .setRelatedUserId(person.get("relatedUserId", String.class))
                .setRelatedUserLogin(person.get("login", String.class));

            workPersonsMap.put(person.get("id", Long.class), personDef);
        }
    }

    /**
     * Replace person specified by personId in persons store.
     * If personDef is empty and previous value exists in the store that it is deleted.
     *
     * @param personId - given person id
     * @param personDef - related person definition
     */
    private void replacePerson(Long personId, Optional<PersonDef> personDef) {
        personDef.ifPresentOrElse(def -> allPersonsStore.putPerson(personId, def), () -> allPersonsStore.removePerson(personId));
    }

    /**
     * Process person from query results
     * @param persons List of Tuples containing person data (should contain 0 or 1 element)
     * @param personParties List of Tuples containing person-party relationships
     * @param personPartyRoles List of Tuples containing person-party-role assignments
     * @param personPositionRoles List of Tuples containing person-position-role assignments
     * @return a personDef for a specified person
     */
    private Optional<PersonDef> processPersonFromQueryResult(
        List<Tuple> persons,
        List<Tuple> personParties,
        List<Tuple> personPartyRoles,
        List<Tuple> personPositionRoles
    ) {
        if (persons != null && !persons.isEmpty()) {
            Tuple person = persons.get(0);

            final PersonDef personDef = new PersonDef(person.get("id", Long.class), person.get("name", String.class))
                .setDefaultCompanyId(person.get("dfltCompanyId", Long.class))
                .setDefaultOrgunitId(person.get("dfltOrgunitId", Long.class))
                .setRelatedUserId(person.get("relatedUserId", String.class))
                .setRelatedUserLogin(person.get("login", String.class));

            // Process person parties
            processPersonPartiesForSinglePerson(personParties, personDef);
            // Process person party roles
            processPersonPartyRolesForSinglePerson(personPartyRoles, personDef);
            // Process person position roles
            processPersonPositionRolesForSinglePerson(personPositionRoles, personDef);
            // Build organization and position businessRoles
            buildOrganizationBusinessRoles(personDef);
            buildPositionBusinessRoles(personDef);

            return Optional.of(personDef);
        }
        return Optional.empty();
    }

    /**
     * Process person position roles for a single person
     *
     * @param personPositionRoles List of Tuples containing person-position-role data
     * @param personDef - person def where position roles will be added
     */
    private void processPersonPositionRolesForSinglePerson(List<Tuple> personPositionRoles, PersonDef personDef) {
        for (Tuple role : personPositionRoles) {
            Long orgunitId = role.get("orgunitId", Long.class);
            Long roleId = role.get("roleId", Long.class);

            RoleDef roleDef = allRolesStore.getPositionRole(roleId);
            personDef.organizationsMap.get(orgunitId).addPositionRole(roleDef);
        }
    }

    /**
     * Process person party roles for a single person
     *
     * @param personPartyRoles List of Tuples containing person-party-role data
     * @param personDef - person def where party roles will be added
     */
    private void processPersonPartyRolesForSinglePerson(List<Tuple> personPartyRoles, PersonDef personDef) {
        for (Tuple role : personPartyRoles) {
            Long orgunitId = role.get("orgunitId", Long.class);
            Long roleId = role.get("roleId", Long.class);

            RoleDef roleDef = allRolesStore.getOrganizationRole(roleId);
            personDef.organizationsMap.get(orgunitId).addOrganizationRole(roleDef);
        }
    }

    /**
     * Process person parties for a single person
     *
     * @param personParties List of Tuples containing person-party data
     * @param personDef - person def where parties will be added
     */
    private void processPersonPartiesForSinglePerson(List<Tuple> personParties, PersonDef personDef) {
        for (Tuple personParty : personParties) {
            OrganizationDef organizationDef = new OrganizationDef(
                personParty.get("orgunitName", String.class),
                personParty.get("orgunitId", Long.class),
                personParty.get("positionId", Long.class),
                PathSanitizer.sanitizePath(personParty.get("pathId", String.class)),
                PathSanitizer.sanitizePath(personParty.get("parentPath", String.class)),
                personParty.get("companyId", Long.class),
                PathSanitizer.sanitizePath(personParty.get("companyParentPath", String.class))
            );
            personDef.organizationsMap.put(organizationDef.organizationId, organizationDef);
        }
    }
}
