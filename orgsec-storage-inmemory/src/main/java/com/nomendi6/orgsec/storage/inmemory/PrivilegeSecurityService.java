package com.nomendi6.orgsec.storage.inmemory;

import static java.util.Arrays.*;

import java.util.*;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.dto.UserData;
import com.nomendi6.orgsec.exceptions.OrgsecConfigurationException;
import com.nomendi6.orgsec.exceptions.OrgsecDataAccessException;
import com.nomendi6.orgsec.exceptions.OrgsecSecurityException;
import com.nomendi6.orgsec.interfaces.SecurityEnabledDTO;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import com.nomendi6.orgsec.provider.PersonDataProvider;
import com.nomendi6.orgsec.provider.SecurityContextProvider;
import com.nomendi6.orgsec.provider.UserDataProvider;
import com.nomendi6.orgsec.common.service.PrivilegeChecker;
import com.nomendi6.orgsec.common.service.RsqlFilterBuilder;
import com.nomendi6.orgsec.common.service.SecurityEventPublisher;
import com.nomendi6.orgsec.common.store.SecurityDataStore;

/**
 * Main PrivilegeSecurityService that delegates all operations to specialized services.
 * This class maintains backward compatibility while using the refactored architecture.
 */
@Service
public class PrivilegeSecurityService {

    private static final Logger log = LoggerFactory.getLogger(PrivilegeSecurityService.class);

    // Data providers for abstracted data access
    private final PersonDataProvider personDataProvider;
    private final UserDataProvider userDataProvider;
    private final SecurityContextProvider securityContextProvider;

    private final SecurityDataStore securityDataStore;

    // Refactored services that handle all the logic
    private final PrivilegeChecker privilegeChecker;
    private final RsqlFilterBuilder rsqlFilterBuilder;
    private final SecurityEventPublisher securityEventPublisher;

    public PrivilegeSecurityService(
        SecurityDataStore securityDataStore,
        PrivilegeChecker privilegeChecker,
        RsqlFilterBuilder rsqlFilterBuilder,
        SecurityEventPublisher securityEventPublisher,
        PersonDataProvider personDataProvider,
        UserDataProvider userDataProvider,
        SecurityContextProvider securityContextProvider
    ) {
        this.securityDataStore = securityDataStore;
        this.privilegeChecker = privilegeChecker;
        this.rsqlFilterBuilder = rsqlFilterBuilder;
        this.securityEventPublisher = securityEventPublisher;
        this.personDataProvider = personDataProvider;
        this.userDataProvider = userDataProvider;
        this.securityContextProvider = securityContextProvider;
    }

    // Setter methods are no longer needed as all providers are now injected via constructor
    // This provides better immutability and ensures all dependencies are available at construction time

    // ========== USER AND PERSON MANAGEMENT ==========

    /**
     * Returns the currently logged user as UserData.
     * This method uses UserDataProvider for abstraction.
     */
    public UserData getCurrentUser() {
        if (securityContextProvider != null) {
            Optional<String> login = securityContextProvider.getCurrentUserLogin();
            if (login.isPresent() && userDataProvider != null) {
                return userDataProvider.findByLogin(login.get()).orElse(null);
            }
        }
        return null;
    }

    /**
     * Returns the current user data using the provider.
     */
    public UserData getCurrentUserData() {
        if (userDataProvider == null) {
            log.error("UserDataProvider not configured");
            return null;
        }
        Optional<String> login = userDataProvider.getCurrentUserLogin();
        if (login.isEmpty() && securityContextProvider != null) {
            login = securityContextProvider.getCurrentUserLogin();
        }
        return login.flatMap(userDataProvider::findByLogin).orElse(null);
    }

    /**
     * Returns the current user login (username).
     */
    public String getCurrentUserLogin() {
        if (securityContextProvider != null) {
            return securityContextProvider.getCurrentUserLogin().orElseThrow(() -> new OrgsecSecurityException("No user is logged in"));
        }
        throw new OrgsecConfigurationException("SecurityContextProvider not configured");
    }

    /**
     * Returns the UserData object for given user login (username).
     * This method uses UserDataProvider for abstraction.
     */
    public UserData getUserByLogin(String login) {
        if (userDataProvider == null) {
            throw new OrgsecConfigurationException("UserDataProvider not configured");
        }
        return userDataProvider.findByLogin(login).orElseThrow(() -> new OrgsecDataAccessException("No user found with login: " + login));
    }

    /**
     * Returns the user data for given user login (username)
     */
    public UserData getUserDataByLogin(String login) {
        if (userDataProvider == null) {
            log.error("UserDataProvider not configured");
            throw new OrgsecConfigurationException("UserDataProvider not configured");
        }
        return userDataProvider.findByLogin(login).orElseThrow(() -> new OrgsecDataAccessException("No user found with login: " + login));
    }

    /**
     * Returns the currently logged person as PersonData.
     * Returns null if no user is logged in or no person found for the current user.
     */
    public PersonData getCurrentPerson() {
        try {
            String login = getCurrentUserLogin();
            if (login == null || login.isEmpty()) {
                log.debug("No user login available");
                return null;
            }

            if (personDataProvider != null) {
                // Use provider to get person data
                Optional<PersonData> personData = personDataProvider.findByRelatedUserLogin(login);
                if (personData.isPresent()) {
                    return personData.get();
                } else {
                    log.debug("No person found for user login: {}", login);
                }
            } else {
                log.error("PersonDataProvider is not configured - this is a configuration error");
                throw new OrgsecConfigurationException("PersonDataProvider not configured");
            }
        } catch (OrgsecSecurityException e) {
            log.debug("Security exception getting current person: {}", e.getMessage());
            return null;
        }

        // If no provider or person not found, return null
        return null;
    }

    /**
     * Check if the current user has a necessary privilege to execute the operation on the resource.
     */
    public boolean checkCurrentUserPrivilegeOnResource(SecurityEnabledDTO entityDTO, String resourceName, PrivilegeOperation operation)
        throws OrgsecSecurityException {
        PersonData currentPerson = getCurrentPerson();
        if (currentPerson == null) {
            log.debug("No current person available for privilege check on resource: {}", resourceName);
            return false;
        }

        if (currentPerson.getId() == null) {
            log.warn("Current person has null ID, cannot check privileges for resource: {}", resourceName);
            return false;
        }

        PersonDef personDef = securityDataStore.getPerson(currentPerson.getId());
        if (personDef == null) {
            log.warn("PersonDef not found for person: {}, cannot check privileges for resource: {}", currentPerson.getId(), resourceName);
            return false;
        }

        if (personDef.organizationsMap == null) {
            log.warn(
                "OrganizationsMap is null for person: {}, cannot check privileges for resource: {}",
                currentPerson.getId(),
                resourceName
            );
            return false;
        }

        // look for all person assigned organization unit
        for (Map.Entry<Long, OrganizationDef> e : personDef.organizationsMap.entrySet()) {
            OrganizationDef organizationDef = e.getValue();

            if (organizationDef.businessRolesMap != null && !organizationDef.businessRolesMap.isEmpty()) {
                for (Map.Entry<String, BusinessRoleDef> entry : organizationDef.businessRolesMap.entrySet()) {
                    String businessRoleName = entry.getKey();
                    BusinessRoleDef businessRoleDef = entry.getValue();
                    if (businessRoleDef.resourcesMap != null && !businessRoleDef.resourcesMap.isEmpty()) {
                        final ResourceDef resourceDef = businessRoleDef.resourcesMap.get(resourceName);

                        if (resourceDef == null) {
                            continue;
                        }

                        PrivilegeDef resourceAggregatedPrivs = privilegeChecker.getResourcePrivileges(resourceDef, operation);
                        if (resourceAggregatedPrivs == null || !privilegeChecker.hasRequiredOperation(resourceAggregatedPrivs, operation)) {
                            continue;
                        }

                        if (resourceAggregatedPrivs.all) {
                            return true;
                        }

                        // Check specific business role privileges - use direct delegation to PrivilegeChecker
                        if (
                            privilegeChecker.checkBusinessRolePrivilege(
                                currentPerson,
                                organizationDef,
                                resourceAggregatedPrivs,
                                businessRoleName,
                                entityDTO
                            )
                        ) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Check write privileges for entity
     */
    public void checkWritePrivileges(SecurityEnabledDTO entityDTO, String resourceName) throws AccessDeniedException {
        boolean hasPrivilege = checkCurrentUserPrivilegeOnResource(entityDTO, resourceName, PrivilegeOperation.WRITE);
        if (!hasPrivilege) {
            throw new AccessDeniedException("Insufficient privileges for WRITE operation on resource: " + resourceName);
        }
    }

    public boolean hasWritePrivileges(SecurityEnabledDTO entityDTO, String resourceName) throws OrgsecSecurityException {
        return checkCurrentUserPrivilegeOnResource(entityDTO, resourceName, PrivilegeOperation.WRITE);
    }

    /**
     * Check read privileges for entity
     */
    public void checkReadOnePrivileges(SecurityEnabledDTO entityDTO, String resourceName) throws AccessDeniedException {
        boolean hasPrivilege = checkCurrentUserPrivilegeOnResource(entityDTO, resourceName, PrivilegeOperation.READ);
        if (!hasPrivilege) {
            throw new AccessDeniedException("Insufficient privileges for READ operation on resource: " + resourceName);
        }
    }

    public boolean hasReadOnePrivileges(SecurityEnabledDTO entityDTO, String resourceName) throws OrgsecSecurityException {
        return checkCurrentUserPrivilegeOnResource(entityDTO, resourceName, PrivilegeOperation.READ);
    }

    // ========== RSQL FILTER BUILDING ==========

    /**
     * Returns rsql filter to add to sql query that will apply privilege security
     */
    public String getRsqlFilterForPrivileges(
        String resourceName,
        String parentField,
        List<String> allowedBusinessRoles,
        PrivilegeOperation operation
    ) throws OrgsecSecurityException {
        PersonData currentPerson = getCurrentPerson();
        if (currentPerson == null) {
            log.debug("No current person available for RSQL filter building on resource: {}", resourceName);
            throw new OrgsecSecurityException("No authenticated user available for privilege filtering");
        }
        if (currentPerson.getId() == null) {
            log.warn("Current person has null ID, cannot build RSQL filter for resource: {}", resourceName);
            throw new OrgsecSecurityException("Current user has invalid person data");
        }
        return rsqlFilterBuilder.buildRsqlFilterForPrivileges(resourceName, parentField, allowedBusinessRoles, operation, currentPerson);
    }

    public String getRsqlFilterForPrivileges(String resourceName, String parentField) throws OrgsecSecurityException {
        PersonData currentPerson = getCurrentPerson();
        if (currentPerson == null) {
            log.debug("No current person available for RSQL filter building on resource: {}", resourceName);
            throw new OrgsecSecurityException("No authenticated user available for privilege filtering");
        }
        if (currentPerson.getId() == null) {
            log.warn("Current person has null ID, cannot build RSQL filter for resource: {}", resourceName);
            throw new OrgsecSecurityException("Current user has invalid person data");
        }
        return rsqlFilterBuilder.buildRsqlFilterForReadPrivileges(resourceName, parentField, currentPerson);
    }

    public String getRsqlFilterForWritePrivileges(String resourceName, String parentField) throws OrgsecSecurityException {
        PersonData currentPerson = getCurrentPerson();
        if (currentPerson == null) {
            log.debug("No current person available for RSQL filter building on resource: {}", resourceName);
            throw new OrgsecSecurityException("No authenticated user available for privilege filtering");
        }
        if (currentPerson.getId() == null) {
            log.warn("Current person has null ID, cannot build RSQL filter for resource: {}", resourceName);
            throw new OrgsecSecurityException("Current user has invalid person data");
        }
        return rsqlFilterBuilder.buildRsqlFilterForWritePrivileges(resourceName, parentField, currentPerson);
    }

    public String getRsqlFilterForBasicPrivileges(String resourceName, String parentField) throws OrgsecSecurityException {
        PersonData currentPerson = getCurrentPerson();
        if (currentPerson == null) {
            log.debug("No current person available for RSQL filter building on resource: {}", resourceName);
            throw new OrgsecSecurityException("No authenticated user available for privilege filtering");
        }
        if (currentPerson.getId() == null) {
            log.warn("Current person has null ID, cannot build RSQL filter for resource: {}", resourceName);
            throw new OrgsecSecurityException("Current user has invalid person data");
        }
        return rsqlFilterBuilder.buildRsqlFilterForBasicPrivileges(resourceName, parentField, currentPerson);
    }

    // ========== SECURITY EVENT PUBLISHING ==========

    /**
     * Event that is triggered after a new role is added.
     */
    public void partyRoleAdded(Long partyRoleId) {
        securityEventPublisher.partyRoleAdded(partyRoleId);
    }

    /**
     * Event that is trigger after existing role is changed or role assigned privileges are changed
     */
    public void partyRoleChanged(Long partyRoleId) {
        securityEventPublisher.partyRoleChanged(partyRoleId);
    }

    /**
     * Apply party role changes.
     */
    public void applyPartyRoleChanged(Long partyRoleId) {
        securityEventPublisher.applyPartyRoleChanged(partyRoleId);
    }

    /**
     * Event that is trigger after existing role is deleted
     */
    public void partyRoleDeleted(Long partyRoleId) {
        securityEventPublisher.partyRoleDeleted(partyRoleId);
    }

    /**
     * Event that is triggered after a new position is added
     */
    public void positionRoleAdded(Long roleId) {
        securityEventPublisher.positionRoleAdded(roleId);
    }

    /**
     * Event that is trigger after existing role is changed or role assigned privileges are changed
     */
    public void positionRoleChanged(Long roleId) {
        securityEventPublisher.positionRoleChanged(roleId);
    }

    /**
     * Apply internally position role changes
     */
    public void applyPositionRoleChanges(Long roleId) {
        securityEventPublisher.applyPositionRoleChanges(roleId);
    }

    /**
     * Event that is triggered after a position role is deleted
     */
    public void positionRoleDeleted(Long roleId) {
        securityEventPublisher.positionRoleDeleted(roleId);
    }

    /**
     * Event that is triggered after a new party is added
     */
    public void partyAdded(Long partyId) {
        securityEventPublisher.partyAdded(partyId);
    }

    /**
     * Event that is triggered after existing party or party assigned privileges are changed
     */
    public void partyChanged(Long partyId) {
        securityEventPublisher.partyChanged(partyId);
    }

    /**
     * Apply party changes
     */
    public void applyPartyChanges(Long partyId) {
        securityEventPublisher.applyPartyChanges(partyId);
    }

    /**
     * Event that is triggered after existing party is deleted
     */
    public void partyDeleted(Long partyId) {
        securityEventPublisher.partyDeleted(partyId);
    }

    public void personAdded(Long personId) {
        securityEventPublisher.personAdded(personId);
    }

    public void personChanged(Long personId) {
        securityEventPublisher.personChanged(personId);
    }

    public void personDeleted(Long personId) {
        securityEventPublisher.personDeleted(personId);
    }
}
