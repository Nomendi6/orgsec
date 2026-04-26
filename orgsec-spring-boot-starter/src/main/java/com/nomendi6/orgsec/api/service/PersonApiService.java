package com.nomendi6.orgsec.api.service;

import com.nomendi6.orgsec.api.dto.OrganizationMembershipApiDTO;
import com.nomendi6.orgsec.api.dto.PersonApiDTO;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import com.nomendi6.orgsec.storage.SecurityDataStorage;
import com.nomendi6.orgsec.storage.inmemory.loader.PersonLoader;
import com.nomendi6.orgsec.storage.inmemory.store.AllPersonsStore;
import jakarta.persistence.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for Person API operations.
 * Provides data for Keycloak mapper.
 *
 * <p>This bean is registered conditionally by {@link com.nomendi6.orgsec.autoconfigure.PersonApiServiceConfiguration}
 * only when PersonLoader is available (i.e., when the inmemory storage module is active).
 */
public class PersonApiService {

    private static final Logger log = LoggerFactory.getLogger(PersonApiService.class);

    private final SecurityDataStorage securityDataStorage;
    private final SecurityQueryProvider queryProvider;
    private final PersonLoader personLoader;
    private final AllPersonsStore personsStore;

    public PersonApiService(SecurityDataStorage securityDataStorage,
                            SecurityQueryProvider queryProvider,
                            PersonLoader personLoader,
                            AllPersonsStore personsStore) {
        this.securityDataStorage = securityDataStorage;
        this.queryProvider = queryProvider;
        this.personLoader = personLoader;
        this.personsStore = personsStore;
    }

    /**
     * Get person by Keycloak user ID.
     *
     * @param userId Keycloak user UUID
     * @return PersonApiDTO or null if not found
     */
    public PersonApiDTO getPersonByUserId(String userId) {
        log.debug("Fetching person for userId: {}", userId);

        // Find person in storage that matches the userId
        PersonDef personDef = findPersonByUserId(userId);

        if (personDef == null) {
            log.warn("Person not found for userId: {}", userId);
            return null;
        }

        return mapToApiDTO(personDef);
    }

    /**
     * Find PersonDef by Keycloak userId.
     * Uses SecurityQueryProvider to load person data from database.
     */
    private PersonDef findPersonByUserId(String userId) {
        // Load person data from database using query provider
        List<Tuple> personTuples = queryProvider.loadPersonByUserId(userId);

        if (personTuples.isEmpty()) {
            return null;
        }

        // Get person ID from the first tuple
        Tuple personTuple = personTuples.get(0);
        Long personId = personTuple.get("personId", Long.class);

        if (personId == null) {
            return null;
        }

        // Load full person data with memberships
        List<Tuple> persons = queryProvider.loadPersonById(personId);
        List<Tuple> personParties = queryProvider.loadPersonPartiesByPersonId(personId);
        List<Tuple> personPartyRoles = queryProvider.loadPersonPartyRolesByPersonId(personId);
        List<Tuple> personPositionRoles = queryProvider.loadPersonPositionRolesByPersonId(personId);

        // Use PersonLoader to build PersonDef
        personLoader.loadPersonsFromQueryResults(persons, personParties, personPartyRoles, personPositionRoles);

        // Get the loaded person from store
        return personsStore.getPerson(personId);
    }

    /**
     * Get person by person ID.
     *
     * @param personId the person ID
     * @return PersonApiDTO or null if not found
     */
    public PersonApiDTO getPersonById(Long personId) {
        log.debug("Fetching person for personId: {}", personId);

        PersonDef personDef = securityDataStorage.getPerson(personId);

        if (personDef == null) {
            log.warn("Person not found for personId: {}", personId);
            return null;
        }

        return mapToApiDTO(personDef);
    }

    /**
     * Map PersonDef to PersonApiDTO.
     */
    private PersonApiDTO mapToApiDTO(PersonDef personDef) {
        PersonApiDTO dto = new PersonApiDTO();
        dto.setId(personDef.personId);
        dto.setName(personDef.personName);
        dto.setRelatedUserId(personDef.relatedUserId);
        dto.setRelatedUserLogin(personDef.relatedUserLogin);
        dto.setDefaultCompanyId(personDef.defaultCompanyId);
        dto.setDefaultOrgunitId(personDef.defaultOrgunitId);

        // Map memberships
        List<OrganizationMembershipApiDTO> memberships = new ArrayList<>();

        if (personDef.organizationsMap != null) {
            for (Map.Entry<Long, OrganizationDef> entry : personDef.organizationsMap.entrySet()) {
                OrganizationDef orgDef = entry.getValue();
                OrganizationMembershipApiDTO membershipDTO = new OrganizationMembershipApiDTO();
                membershipDTO.setOrganizationId(orgDef.organizationId);
                membershipDTO.setCompanyId(orgDef.companyId);
                membershipDTO.setPathId(orgDef.pathId);

                // Extract position role IDs
                List<Long> positionRoleIds = orgDef.positionRolesSet.stream()
                        .map(role -> role.roleId)
                        .collect(Collectors.toList());
                membershipDTO.setPositionRoleIds(positionRoleIds);

                memberships.add(membershipDTO);
            }
        }

        dto.setMemberships(memberships);

        log.debug("Mapped PersonDef to API DTO: personId={}, memberships={}",
                dto.getId(), memberships.size());

        return dto;
    }
}
