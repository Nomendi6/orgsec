package com.nomendi6.orgsec.common.service;

import static com.nomendi6.orgsec.constants.SecurityConstants.EventTypes.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.nomendi6.orgsec.storage.SecurityDataStorage;

/**
 * Service responsible for publishing and managing security-related events.
 * Handles event generation for party roles, position roles, parties, and persons.
 *
 * <p>Works with any SecurityDataStorage implementation through the notification interface.
 * Storage providers can choose to react to notifications (e.g., InMemory syncs data, Redis
 * publishes a new snapshot).
 *
 * <p>Producer methods ({@code partyRoleChanged}, {@code personChanged}, ...) apply the local
 * notify and the Kafka publish attempt once, after the surrounding transaction commits. A
 * rollback applies nothing. With no active transaction the notify runs immediately. {@code apply*}
 * methods stay the consumer/internal path: they notify storage at once and never republish.
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
        afterCommitOrNow(() -> {
            storage.notifyPartyRoleChanged(partyRoleId);
            generateEvent(PARTY_ROLE_ADDED, partyRoleId);
        });
    }

    public void partyRoleChanged(Long partyRoleId) {
        log.debug("Party role changed: {}", partyRoleId);
        afterCommitOrNow(() -> {
            storage.notifyPartyRoleChanged(partyRoleId);
            generateEvent(PARTY_ROLE_CHANGED, partyRoleId);
        });
    }

    public void applyPartyRoleChanged(Long partyRoleId) {
        log.debug("Apply party role changes: {}", partyRoleId);
        storage.notifyPartyRoleChanged(partyRoleId);
    }

    public void partyRoleDeleted(Long partyRoleId) {
        log.debug("Party role deleted: {}", partyRoleId);
        afterCommitOrNow(() -> {
            storage.notifyPartyRoleChanged(partyRoleId);
            generateEvent(PARTY_ROLE_DELETED, partyRoleId);
        });
    }

    public void positionRoleAdded(Long roleId) {
        log.debug("Position role added");
        afterCommitOrNow(() -> {
            storage.notifyPositionRoleChanged(roleId);
            generateEvent(POSITION_ROLE_ADDED, roleId);
        });
    }

    public void positionRoleChanged(Long roleId) {
        log.debug("Position role changed");
        afterCommitOrNow(() -> {
            storage.notifyPositionRoleChanged(roleId);
            generateEvent(POSITION_ROLE_CHANGED, roleId);
        });
    }

    public void applyPositionRoleChanges(Long roleId) {
        log.debug("Apply position role changes");
        storage.notifyPositionRoleChanged(roleId);
    }

    public void positionRoleDeleted(Long roleId) {
        log.debug("Position role deleted");
        afterCommitOrNow(() -> {
            storage.notifyPositionRoleChanged(roleId);
            generateEvent(POSITION_ROLE_DELETED, roleId);
        });
    }

    public void partyAdded(Long partyId) {
        log.debug("Party added");
        afterCommitOrNow(() -> {
            storage.notifyOrganizationChanged(partyId);
            generateEvent(PARTY_ADDED, partyId);
        });
    }

    public void partyChanged(Long partyId) {
        log.debug("Party changed");
        afterCommitOrNow(() -> {
            storage.notifyOrganizationChanged(partyId);
            generateEvent(PARTY_CHANGED, partyId);
        });
    }

    public void applyPartyChanges(Long partyId) {
        storage.notifyOrganizationChanged(partyId);
    }

    public void partyDeleted(Long partyId) {
        log.debug("Party deleted");
        afterCommitOrNow(() -> {
            storage.notifyOrganizationChanged(partyId);
            generateEvent(PARTY_DELETED, partyId);
        });
    }

    public void personAdded(Long personId) {
        afterCommitOrNow(() -> {
            storage.notifyPersonChanged(personId);
            generateEvent(PERSON_ADDED, personId);
        });
    }

    public void personChanged(Long personId) {
        afterCommitOrNow(() -> {
            storage.notifyPersonChanged(personId);
            generateEvent(PERSON_CHANGED, personId);
        });
    }

    public void personDeleted(Long personId) {
        afterCommitOrNow(() -> {
            storage.notifyPersonChanged(personId);
            generateEvent(PERSON_DELETED, personId);
        });
    }

    /**
     * Runs {@code action} after the current transaction commits, or immediately when there is no
     * active transaction. A rollback never runs the action.
     *
     * <p>A failure here cannot un-commit the database. It is logged and rethrown as
     * {@link SecurityNotifyAfterCommitException} so the caller can see that the source change
     * landed and the authorization view did not.
     */
    private void afterCommitOrNow(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
            || !TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    log.error("Security notify after commit failed; the source transaction already committed", failure);
                    throw new SecurityNotifyAfterCommitException(failure);
                }
            }
        });
    }

    private void generateEvent(String event, Long relatedId) {
        // ToDo: enable after kafka is connected to the module
    }
}
