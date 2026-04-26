package com.nomendi6.orgsec.storage;

import java.util.HashMap;
import java.util.Map;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;

/**
 * Represents a snapshot of the security data storage state.
 * Used for backup/restore operations, particularly useful in integration tests
 * to save and restore the storage state between test scenarios.
 */
public class StorageSnapshot {

    private final Map<Long, PersonDef> persons;
    private final Map<Long, OrganizationDef> organizations;
    private final Map<Long, RoleDef> partyRoles;
    private final Map<Long, RoleDef> positionRoles;
    private final Map<String, PrivilegeDef> privileges;
    private final long timestamp;

    public StorageSnapshot(
        Map<Long, PersonDef> persons,
        Map<Long, OrganizationDef> organizations,
        Map<Long, RoleDef> partyRoles,
        Map<Long, RoleDef> positionRoles,
        Map<String, PrivilegeDef> privileges
    ) {
        this.persons = persons != null ? new HashMap<>(persons) : new HashMap<>();
        this.organizations = organizations != null ? new HashMap<>(organizations) : new HashMap<>();
        this.partyRoles = partyRoles != null ? new HashMap<>(partyRoles) : new HashMap<>();
        this.positionRoles = positionRoles != null ? new HashMap<>(positionRoles) : new HashMap<>();
        this.privileges = privileges != null ? new HashMap<>(privileges) : new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public Map<Long, PersonDef> getPersons() {
        return persons;
    }

    public Map<Long, OrganizationDef> getOrganizations() {
        return organizations;
    }

    public Map<Long, RoleDef> getPartyRoles() {
        return partyRoles;
    }

    public Map<Long, RoleDef> getPositionRoles() {
        return positionRoles;
    }

    public Map<String, PrivilegeDef> getPrivileges() {
        return privileges;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format(
            "StorageSnapshot{timestamp=%d, persons=%d, organizations=%d, partyRoles=%d, positionRoles=%d, privileges=%d}",
            timestamp,
            persons.size(),
            organizations.size(),
            partyRoles.size(),
            positionRoles.size(),
            privileges.size()
        );
    }
}
