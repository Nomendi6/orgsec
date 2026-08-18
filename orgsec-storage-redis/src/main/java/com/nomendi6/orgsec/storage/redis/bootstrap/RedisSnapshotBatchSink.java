package com.nomendi6.orgsec.storage.redis.bootstrap;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;

import java.util.Map;

/** Internal synchronous target for validated snapshot batches. */
public interface RedisSnapshotBatchSink {

    void writePersons(Map<Long, PersonDef> persons);

    void writeOrganizations(Map<Long, OrganizationDef> organizations);

    void writePartyRoles(Map<Long, RoleDef> partyRoles);

    void writePositionRoles(Map<Long, RoleDef> positionRoles);

    void writeRoles(Map<Long, RoleDef> roles);

    void writePrivileges(Map<String, PrivilegeDef> privileges);
}
