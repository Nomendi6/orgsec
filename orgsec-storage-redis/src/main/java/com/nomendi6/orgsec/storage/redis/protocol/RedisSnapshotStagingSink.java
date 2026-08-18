package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotBatchSink;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Encodes validated domain batches into one owner-thread staging session. */
final class RedisSnapshotStagingSink implements RedisSnapshotBatchSink {

    private final RedisStagingSession session;
    private final RedisCanonicalSnapshotPayloadCodec codec;
    private final Map<RedisSnapshotFamily, RedisSnapshotFamilyAccumulator> accumulators =
        new EnumMap<>(RedisSnapshotFamily.class);

    RedisSnapshotStagingSink(
        RedisStagingSession session,
        RedisDatasetKeyspace keyspace,
        UUID snapshotId,
        RedisCanonicalSnapshotPayloadCodec codec
    ) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        Objects.requireNonNull(keyspace, "keyspace must not be null");
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        for (RedisSnapshotFamily family : RedisSnapshotFamily.values()) {
            accumulators.put(
                family,
                new RedisSnapshotFamilyAccumulator(
                    familyCode(family),
                    keyspace.familyKey(snapshotId, family),
                    keyspace.familyIndexKey(snapshotId, family)
                )
            );
        }
    }

    @Override
    public void writePersons(Map<Long, PersonDef> persons) {
        append(RedisSnapshotFamily.PERSONS, persons, this::personEntry);
    }

    @Override
    public void writeOrganizations(Map<Long, OrganizationDef> organizations) {
        append(RedisSnapshotFamily.ORGANIZATIONS, organizations, this::organizationEntry);
    }

    @Override
    public void writePartyRoles(Map<Long, RoleDef> partyRoles) {
        append(RedisSnapshotFamily.PARTY_ROLES, partyRoles, this::roleEntry);
    }

    @Override
    public void writePositionRoles(Map<Long, RoleDef> positionRoles) {
        append(RedisSnapshotFamily.POSITION_ROLES, positionRoles, this::roleEntry);
    }

    @Override
    public void writeRoles(Map<Long, RoleDef> roles) {
        append(RedisSnapshotFamily.ROLES, roles, this::roleEntry);
    }

    @Override
    public void writePrivileges(Map<String, PrivilegeDef> privileges) {
        append(RedisSnapshotFamily.PRIVILEGES, privileges, this::privilegeEntry);
    }

    void completeAllFamilies() {
        for (RedisSnapshotFamily family : RedisSnapshotFamily.values()) {
            session.complete(family);
        }
    }

    RedisSnapshotContentDigest content() {
        List<RedisSnapshotFamilyDigest> digests = new ArrayList<>(6);
        for (RedisSnapshotFamily family : RedisSnapshotFamily.values()) {
            digests.add(accumulators.get(family).finish());
        }
        return new RedisSnapshotContentDigest(
            digests.get(0),
            digests.get(1),
            digests.get(2),
            digests.get(3),
            digests.get(4),
            digests.get(5)
        );
    }

    private <K, V> void append(
        RedisSnapshotFamily family,
        Map<K, V> batch,
        Function<Map.Entry<K, V>, RedisCanonicalEntry> encoder
    ) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        List<RedisCanonicalEntry> entries = new ArrayList<>(batch.size());
        for (Map.Entry<K, V> entry : batch.entrySet()) {
            entries.add(encoder.apply(entry));
        }
        List<RedisCanonicalEntry> ordered = RedisCanonicalEntryOrder.sortedCopy(entries);
        session.append(family, ordered);
        RedisSnapshotFamilyAccumulator accumulator = accumulators.get(family);
        for (RedisCanonicalEntry entry : ordered) {
            accumulator.add(entry);
        }
    }

    private RedisCanonicalEntry personEntry(Map.Entry<Long, PersonDef> entry) {
        return new RedisCanonicalEntry(longKey(entry.getKey()), codec.encode(entry.getValue()));
    }

    private RedisCanonicalEntry organizationEntry(Map.Entry<Long, OrganizationDef> entry) {
        return new RedisCanonicalEntry(longKey(entry.getKey()), codec.encode(entry.getValue()));
    }

    private RedisCanonicalEntry roleEntry(Map.Entry<Long, RoleDef> entry) {
        return new RedisCanonicalEntry(longKey(entry.getKey()), codec.encode(entry.getValue()));
    }

    private RedisCanonicalEntry privilegeEntry(Map.Entry<String, PrivilegeDef> entry) {
        return new RedisCanonicalEntry(
            entry.getKey().getBytes(StandardCharsets.UTF_8),
            codec.encode(entry.getValue())
        );
    }

    private static byte[] longKey(Long id) {
        return Long.toString(id).getBytes(StandardCharsets.US_ASCII);
    }

    private static RedisSnapshotFamilyCode familyCode(RedisSnapshotFamily family) {
        return switch (family) {
            case PERSONS -> RedisSnapshotFamilyCode.PERSONS;
            case ORGANIZATIONS -> RedisSnapshotFamilyCode.ORGANIZATIONS;
            case PARTY_ROLES -> RedisSnapshotFamilyCode.PARTY_ROLES;
            case POSITION_ROLES -> RedisSnapshotFamilyCode.POSITION_ROLES;
            case ROLES -> RedisSnapshotFamilyCode.ROLES;
            case PRIVILEGES -> RedisSnapshotFamilyCode.PRIVILEGES;
        };
    }
}
