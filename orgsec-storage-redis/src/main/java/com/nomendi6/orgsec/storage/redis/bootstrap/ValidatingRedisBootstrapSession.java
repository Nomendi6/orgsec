package com.nomendi6.orgsec.storage.redis.bootstrap;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Internal state machine that validates the public bootstrap-session contract before delegating
 * to a protocol writer.
 */
public final class ValidatingRedisBootstrapSession implements RedisBootstrapSession {

    private static final int SUPPORTED_PROTOCOL_VERSION = 1;
    private static final String UNSUPPORTED_VERSION_MESSAGE =
        "Redis wire protocol requires protocol version 1";

    enum State {
        OPEN,
        SEALED,
        ABORTED
    }

    // This is a protocol-v1 list. Adding an enum constant must not silently make an existing
    // loader incomplete; a future protocol version must opt into its own explicit family set.
    private static final EnumSet<RedisSnapshotFamily> REQUIRED_FAMILIES = EnumSet.of(
        RedisSnapshotFamily.PERSONS,
        RedisSnapshotFamily.ORGANIZATIONS,
        RedisSnapshotFamily.PARTY_ROLES,
        RedisSnapshotFamily.POSITION_ROLES,
        RedisSnapshotFamily.ROLES,
        RedisSnapshotFamily.PRIVILEGES
    );

    private final SecurityDatasetFence datasetFence;
    private final int preferredBatchSize;
    private final RedisSnapshotBatchSink sink;
    private final Thread ownerThread;
    private final EnumSet<RedisSnapshotFamily> completedFamilies =
        EnumSet.noneOf(RedisSnapshotFamily.class);
    private final Map<RedisSnapshotFamily, Set<Object>> writtenKeys =
        new EnumMap<>(RedisSnapshotFamily.class);

    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);

    public ValidatingRedisBootstrapSession(
        SecurityDatasetFence datasetFence,
        int preferredBatchSize,
        RedisSnapshotBatchSink sink
    ) {
        this.datasetFence = Objects.requireNonNull(datasetFence, "datasetFence must not be null");
        if (datasetFence.getIdentity().getProtocolVersion() != SUPPORTED_PROTOCOL_VERSION) {
            throw new IllegalArgumentException(UNSUPPORTED_VERSION_MESSAGE);
        }
        if (preferredBatchSize <= 0) {
            throw new IllegalArgumentException("preferredBatchSize must be greater than zero");
        }
        this.preferredBatchSize = preferredBatchSize;
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        this.ownerThread = Thread.currentThread();
        REQUIRED_FAMILIES.forEach(family -> writtenKeys.put(family, new HashSet<>()));
    }

    @Override
    public SecurityDatasetFence getDatasetFence() {
        checkOwnerThread();
        checkOpen();
        return datasetFence;
    }

    @Override
    public int getPreferredBatchSize() {
        checkOwnerThread();
        checkOpen();
        return preferredBatchSize;
    }

    @Override
    public void writePersons(Map<Long, PersonDef> persons) {
        writeBatch(
            RedisSnapshotFamily.PERSONS,
            persons,
            (key, person) -> Objects.equals(key, person.personId),
            "personId",
            sink::writePersons
        );
    }

    @Override
    public void writeOrganizations(Map<Long, OrganizationDef> organizations) {
        writeBatch(
            RedisSnapshotFamily.ORGANIZATIONS,
            organizations,
            (key, organization) -> Objects.equals(key, organization.organizationId),
            "organizationId",
            sink::writeOrganizations
        );
    }

    @Override
    public void writePartyRoles(Map<Long, RoleDef> partyRoles) {
        writeBatch(
            RedisSnapshotFamily.PARTY_ROLES,
            partyRoles,
            (key, role) -> Objects.equals(key, role.roleId),
            "roleId",
            sink::writePartyRoles
        );
    }

    @Override
    public void writePositionRoles(Map<Long, RoleDef> positionRoles) {
        writeBatch(
            RedisSnapshotFamily.POSITION_ROLES,
            positionRoles,
            (key, role) -> Objects.equals(key, role.roleId),
            "roleId",
            sink::writePositionRoles
        );
    }

    @Override
    public void writeRoles(Map<Long, RoleDef> roles) {
        writeBatch(
            RedisSnapshotFamily.ROLES,
            roles,
            (key, role) -> Objects.equals(key, role.roleId),
            "roleId",
            sink::writeRoles
        );
    }

    @Override
    public void writePrivileges(Map<String, PrivilegeDef> privileges) {
        writeBatch(
            RedisSnapshotFamily.PRIVILEGES,
            privileges,
            (key, privilege) -> !key.isBlank() && Objects.equals(key, privilege.name),
            "name",
            sink::writePrivileges
        );
    }

    @Override
    public void completeFamily(RedisSnapshotFamily family) {
        checkOwnerThread();
        checkOpen();
        if (family == null) {
            abortWith(new IllegalArgumentException("family must not be null"));
        }
        if (!completedFamilies.add(family)) {
            abortWith(new IllegalStateException("Snapshot family is already complete: " + family));
        }
    }

    public void seal() {
        checkOwnerThread();
        checkOpen();
        if (!completedFamilies.containsAll(REQUIRED_FAMILIES)) {
            EnumSet<RedisSnapshotFamily> missing = EnumSet.copyOf(REQUIRED_FAMILIES);
            missing.removeAll(completedFamilies);
            abortWith(new IllegalStateException("Snapshot is missing completed families: " + missing));
        }
        if (!state.compareAndSet(State.OPEN, State.SEALED)) {
            throw new IllegalStateException("Redis bootstrap session is not open: " + state.get());
        }
    }

    void abort() {
        state.set(State.ABORTED);
    }

    State state() {
        return state.get();
    }

    private <K, V> void writeBatch(
        RedisSnapshotFamily family,
        Map<K, V> batch,
        BiPredicate<K, V> identityMatches,
        String identityField,
        Consumer<Map<K, V>> writer
    ) {
        checkOwnerThread();
        checkOpen();
        if (completedFamilies.contains(family)) {
            abortWith(new IllegalStateException("Snapshot family is already complete: " + family));
        }

        Map<K, V> batchCopy = immutableBatchCopy(family, batch);
        validateIdentitiesAndUniqueness(family, batchCopy, identityMatches, identityField);
        try {
            writer.accept(batchCopy);
        } catch (RuntimeException | Error failure) {
            state.set(State.ABORTED);
            throw failure;
        }
    }

    private <K, V> void validateIdentitiesAndUniqueness(
        RedisSnapshotFamily family,
        Map<K, V> batch,
        BiPredicate<K, V> identityMatches,
        String identityField
    ) {
        for (Map.Entry<K, V> entry : batch.entrySet()) {
            if (!identityMatches.test(entry.getKey(), entry.getValue())) {
                abortWith(new IllegalArgumentException(
                    family + " key " + entry.getKey()
                        + " does not match the value's " + identityField
                ));
            }
        }

        Set<Object> familyKeys = writtenKeys.get(family);
        for (K key : batch.keySet()) {
            if (familyKeys.contains(key)) {
                abortWith(new IllegalStateException(
                    family + " key was written more than once: " + key
                ));
            }
        }
        familyKeys.addAll(batch.keySet());
    }

    private <K, V> Map<K, V> immutableBatchCopy(
        RedisSnapshotFamily family,
        Map<K, V> batch
    ) {
        if (batch == null) {
            return abortWith(new IllegalArgumentException(family + " batch must not be null"));
        }
        if (batch.size() > preferredBatchSize) {
            return abortWith(new IllegalArgumentException(
                family + " batch has " + batch.size()
                    + " entries, exceeding the limit of " + preferredBatchSize
            ));
        }
        try {
            return Map.copyOf(batch);
        } catch (NullPointerException failure) {
            return abortWith(new IllegalArgumentException(
                family + " batch must not contain null keys or values",
                failure
            ));
        }
    }

    private void checkOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            abortWith(new IllegalStateException(
                "Redis bootstrap session must be used synchronously on its owner thread"
            ));
        }
    }

    private void checkOpen() {
        State currentState = state.get();
        if (currentState != State.OPEN) {
            state.set(State.ABORTED);
            throw new IllegalStateException("Redis bootstrap session is not open: " + currentState);
        }
    }

    private <T, E extends RuntimeException> T abortWith(E failure) {
        state.set(State.ABORTED);
        throw failure;
    }
}
