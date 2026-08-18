package com.nomendi6.orgsec.storage.redis.bootstrap;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;

import java.util.Map;

/**
 * Bounded, thread-confined write API for one Redis snapshot bootstrap attempt.
 *
 * <p>A loader may only write data and mark families complete. Publication, READY transitions,
 * session sealing, rollback, and Redis control keys remain exclusively owned by the library
 * coordinator and are intentionally absent from this SPI.</p>
 *
 * <p>Each write is synchronous. The loader may reuse or mutate the supplied map after the call
 * returns, but it must not mutate domain objects while a write call is in progress.</p>
 */
public interface RedisBootstrapSession {

    /**
     * Returns the exact source-database fence locked for this bootstrap attempt.
     *
     * @return the locked dataset identity and source content version
     */
    SecurityDatasetFence getDatasetFence();

    /**
     * Returns the maximum number of entries accepted by one {@code write*} call.
     *
     * @return a positive batch-size limit
     */
    int getPreferredBatchSize();

    /**
     * Writes a batch of persons.
     *
     * @param persons persons keyed by their stable IDs
     */
    void writePersons(Map<Long, PersonDef> persons);

    /**
     * Writes a batch of organizations.
     *
     * @param organizations organizations keyed by their stable IDs
     */
    void writeOrganizations(Map<Long, OrganizationDef> organizations);

    /**
     * Writes a batch from the party-role ID namespace.
     *
     * @param partyRoles party roles keyed by their stable IDs
     */
    void writePartyRoles(Map<Long, RoleDef> partyRoles);

    /**
     * Writes a batch from the position-role ID namespace.
     *
     * @param positionRoles position roles keyed by their stable IDs
     */
    void writePositionRoles(Map<Long, RoleDef> positionRoles);

    /**
     * Writes a batch for the legacy untyped role lookup family.
     *
     * <p>This family must be supplied explicitly. A loader must not create it by a lossy merge of
     * party-role and position-role maps whose numeric ID namespaces may overlap.</p>
     *
     * @param roles legacy roles keyed by their stable IDs
     */
    void writeRoles(Map<Long, RoleDef> roles);

    /**
     * Writes a batch of privilege definitions.
     *
     * @param privileges privileges keyed by their canonical names
     */
    void writePrivileges(Map<String, PrivilegeDef> privileges);

    /**
     * Marks one family complete, including when the family contains no entries.
     *
     * <p>Completion is explicit so an omitted family cannot be mistaken for a valid empty family.
     * A family can be completed exactly once and cannot be written afterwards.</p>
     *
     * @param family the completed family
     */
    void completeFamily(RedisSnapshotFamily family);
}
