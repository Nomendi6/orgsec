package com.nomendi6.orgsec.storage.inmemory.loader;

import jakarta.persistence.Tuple;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.nomendi6.orgsec.helper.PrivilegeSecurityHelper;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.inmemory.store.AllOrganizationsStore;
import com.nomendi6.orgsec.storage.inmemory.store.AllRolesStore;

/**
 * Service responsible for processing organization data from query results.
 * This loader focuses on data processing logic while query execution
 * is handled externally.
 */
@Service
public class OrganizationLoader {

    private static final Logger log = LoggerFactory.getLogger(OrganizationLoader.class);

    private final AllRolesStore allRolesStore;
    private final AllOrganizationsStore allOrganizationsStore;

    public OrganizationLoader(AllRolesStore allRolesStore, AllOrganizationsStore allOrganizationsStore) {
        this.allRolesStore = allRolesStore;
        this.allOrganizationsStore = allOrganizationsStore;
    }

    /**
     * Load all organization units from query results and store them in AllOrganizationsStore.
     *
     * @param parties List of Tuples containing party data from database query
     * @param assignedRoles List of Tuples containing party-role assignments from database query
     */
    public void loadOrganizationsFromQueryResults(List<Tuple> parties, List<Tuple> assignedRoles) {
        Map<Long, OrganizationDef> workOrganizationMap = new HashMap<>();

        processParties(parties, workOrganizationMap);
        processPartyAssignedRoles(assignedRoles, workOrganizationMap);
        buildOrganizationBusinessRoles(workOrganizationMap);

        allOrganizationsStore.setOrganizationMap(workOrganizationMap);
        log.debug("Loaded {} organizations", workOrganizationMap.size());
    }

    /**
     * Synchronize party specified by partyId in organization store.
     *
     * @param partyId - specified party id
     * @param party - Tuple containing party data from database query (can be null/empty)
     * @param assignedRoles - List of Tuples containing role assignments for this party
     */
    public void syncParty(Long partyId, List<Tuple> party, List<Tuple> assignedRoles) {
        Optional<OrganizationDef> organization = processPartyFromQueryResult(party, assignedRoles);
        replaceParty(partyId, organization);
    }

    /**
     * Process related parties for synchronization.
     * Note: Actual synchronization should be handled by the caller using syncParty method.
     *
     * @param relatedParties - List of Tuples containing party IDs that need synchronization
     * @return List of party IDs that should be synchronized
     */
    public List<Long> getPartiesForSync(List<Tuple> relatedParties) {
        List<Long> partyIds = new java.util.ArrayList<>();
        if (relatedParties != null && !relatedParties.isEmpty()) {
            for (Tuple party : relatedParties) {
                partyIds.add(party.get("partyId", Long.class));
            }
        }
        return partyIds;
    }

    /**
     * Build businessRoles from organization roles
     *
     * @param workOrganizationMap is the map of organizations being built
     */
    private void buildOrganizationBusinessRoles(Map<Long, OrganizationDef> workOrganizationMap) {
        workOrganizationMap.forEach((aLong, organizationDef) ->
            organizationDef.organizationRolesSet.forEach(roleDef ->
                PrivilegeSecurityHelper.buildBusinessRoleResourceMap(organizationDef.businessRolesMap, roleDef)
            )
        );
    }

    /**
     * Process parties from query results and populate the workOrganizationMap
     *
     * @param parties List of Tuples containing party data from database query
     * @param workOrganizationMap is the map of organizations being built
     */
    private void processParties(List<Tuple> parties, Map<Long, OrganizationDef> workOrganizationMap) {
        for (Tuple party : parties) {
            final OrganizationDef organization = new OrganizationDef(
                party.get("name", String.class),
                party.get("id", Long.class),
                null,
                party.get("pathId", String.class),
                party.get("parentPath", String.class),
                party.get("companyId", Long.class),
                party.get("companyParentPath", String.class)
            );
            workOrganizationMap.put(organization.organizationId, organization);
        }
    }

    /**
     * Process party assigned roles from query results and add them to workOrganizationMap
     *
     * @param assignedRoles List of Tuples containing party-role assignments from database query
     * @param workOrganizationMap is the map of organizations being built
     */
    private void processPartyAssignedRoles(List<Tuple> assignedRoles, Map<Long, OrganizationDef> workOrganizationMap) {
        for (Tuple assignedRole : assignedRoles) {
            OrganizationDef organization = workOrganizationMap.get(assignedRole.get("partyId", Long.class));
            if (organization != null) {
                RoleDef role = allRolesStore.getOrganizationRole(assignedRole.get("roleId", Long.class));
                if (role != null) {
                    organization.addOrganizationRole(role);
                }
            }
        }
    }

    /**
     * Replace party in organization store if party is defined.
     * If party is empty and previous value exists in the store then it is deleted.
     *
     * @param partyId - given party id
     * @param organization - related organization structure
     */
    private void replaceParty(Long partyId, Optional<OrganizationDef> organization) {
        organization.ifPresentOrElse(
            org -> allOrganizationsStore.putOrganization(partyId, org),
            () -> allOrganizationsStore.removeOrganization(partyId)
        );
    }

    /**
     * Process a specific party from query results.
     *
     * @param parties - List of Tuples containing party data (should contain 0 or 1 element)
     * @param assignedRoles - List of Tuples containing role assignments for this party
     * @return Optional containing the processed party, or empty if not found
     */
    private Optional<OrganizationDef> processPartyFromQueryResult(List<Tuple> parties, List<Tuple> assignedRoles) {
        if (parties != null && !parties.isEmpty()) {
            Tuple party = parties.get(0);
            final OrganizationDef organization = new OrganizationDef(
                party.get("name", String.class),
                party.get("id", Long.class),
                null,
                party.get("pathId", String.class),
                party.get("parentPath", String.class),
                party.get("companyId", Long.class),
                party.get("companyParentPath", String.class)
            );

            // Process assigned roles for this party
            processPartyAssignedRolesForSingleParty(assignedRoles, organization);

            // build organization businessRoles
            organization.organizationRolesSet.forEach(roleDef ->
                PrivilegeSecurityHelper.buildBusinessRoleResourceMap(organization.businessRolesMap, roleDef)
            );

            return Optional.of(organization);
        }

        return Optional.empty();
    }

    /**
     * Process roles assigned to a party and add them to the organization.
     *
     * @param assignedRoles - List of Tuples containing role assignments
     * @param organization - organization structure where roles will be added
     */
    private void processPartyAssignedRolesForSingleParty(List<Tuple> assignedRoles, OrganizationDef organization) {
        for (Tuple assignedRole : assignedRoles) {
            if (organization != null) {
                RoleDef role = allRolesStore.getOrganizationRole(assignedRole.get("roleId", Long.class));
                if (role != null) {
                    organization.addOrganizationRole(role);
                }
            }
        }
    }
}
