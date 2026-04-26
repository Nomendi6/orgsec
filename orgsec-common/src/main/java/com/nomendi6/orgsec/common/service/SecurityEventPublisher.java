package com.nomendi6.orgsec.common.service;

import static com.nomendi6.orgsec.constants.SecurityConstants.EventTypes.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.nomendi6.orgsec.storage.SecurityDataStorage;

/**
 * Service responsible for publishing and managing security-related events.
 * Handles event generation for party roles, position roles, parties, and persons.
 *
 * Works with any SecurityDataStorage implementation through the notification interface.
 * Storage providers can choose to react to notifications (e.g., InMemory syncs data, Redis invalidates cache).
 */
@Component
public class SecurityEventPublisher {

    private final Logger log = LoggerFactory.getLogger(SecurityEventPublisher.class);

    private final SecurityDataStorage storage;

    public SecurityEventPublisher(SecurityDataStorage storage) {
        this.storage = storage;
        log.info("SecurityEventPublisher initialized with storage provider: {}", storage.getProviderType());
    }

    public void partyRoleAdded(Long partyRoleId) {
        log.debug("Party role added: {}", partyRoleId);
        storage.notifyPartyRoleChanged(partyRoleId);
        generateEvent(PARTY_ROLE_ADDED, partyRoleId);
    }

    public void partyRoleChanged(Long partyRoleId) {
        log.debug("Party role changed: {}", partyRoleId);
        storage.notifyPartyRoleChanged(partyRoleId);
        generateEvent(PARTY_ROLE_CHANGED, partyRoleId);
    }

    public void applyPartyRoleChanged(Long partyRoleId) {
        log.debug("Apply party role changes: {}", partyRoleId);
        storage.notifyPartyRoleChanged(partyRoleId);
    }

    public void partyRoleDeleted(Long partyRoleId) {
        log.debug("Party role deleted: {}", partyRoleId);
        storage.notifyPartyRoleChanged(partyRoleId);
        generateEvent(PARTY_ROLE_DELETED, partyRoleId);
    }

    public void positionRoleAdded(Long roleId) {
        log.debug("Position role added");
        storage.notifyPositionRoleChanged(roleId);
        generateEvent(POSITION_ROLE_ADDED, roleId);
    }

    public void positionRoleChanged(Long roleId) {
        log.debug("Position role changed");
        applyPositionRoleChanges(roleId);
        generateEvent(POSITION_ROLE_CHANGED, roleId);
    }

    public void applyPositionRoleChanges(Long roleId) {
        log.debug("Apply position role changes");
        storage.notifyPositionRoleChanged(roleId);
    }

    public void positionRoleDeleted(Long roleId) {
        applyPositionRoleChanges(roleId);
        generateEvent(POSITION_ROLE_DELETED, roleId);
    }

    public void partyAdded(Long partyId) {
        log.debug("Party added");
        storage.notifyOrganizationChanged(partyId);
        generateEvent(PARTY_ADDED, partyId);
    }

    public void partyChanged(Long partyId) {
        log.debug("Party changed");
        applyPartyChanges(partyId);
        generateEvent(PARTY_CHANGED, partyId);
    }

    public void applyPartyChanges(Long partyId) {
        storage.notifyOrganizationChanged(partyId);
    }

    public void partyDeleted(Long partyId) {
        applyPartyChanges(partyId);
        generateEvent(PARTY_DELETED, partyId);
    }

    public void personAdded(Long personId) {
        storage.notifyPersonChanged(personId);
        generateEvent(PERSON_ADDED, personId);
    }

    public void personChanged(Long personId) {
        storage.notifyPersonChanged(personId);
        generateEvent(PERSON_CHANGED, personId);
    }

    public void personDeleted(Long personId) {
        storage.notifyPersonChanged(personId);
        generateEvent(PERSON_DELETED, personId);
    }

    private void generateEvent(String event, Long relatedId) {
        // ToDo: enable after kafka is connected to the module
    }
}
