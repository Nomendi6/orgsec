package com.nomendi6.orgsec.storage.redis.protocol;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable aggregate digest and accounting result for all six snapshot families.
 *
 * <p>The aggregate retains the exact family results from which its total count, logical-byte
 * total and content digest are derived. Logical bytes remain separate physical-layout accounting;
 * only canonical family contents and counts contribute to the content digest. A caller cannot
 * supply independent totals that disagree with the family breakdown.</p>
 */
final class RedisSnapshotContentDigest {

    private static final String CONTENT_DOMAIN = "orgsec:redis:snapshot:v1:content";

    private final List<RedisSnapshotFamilyDigest> familyDigests;
    private final long entryCount;
    private final long logicalBytes;
    private final byte[] digest;

    RedisSnapshotContentDigest(
        RedisSnapshotFamilyDigest persons,
        RedisSnapshotFamilyDigest organizations,
        RedisSnapshotFamilyDigest partyRoles,
        RedisSnapshotFamilyDigest positionRoles,
        RedisSnapshotFamilyDigest roles,
        RedisSnapshotFamilyDigest privileges
    ) {
        this.familyDigests = List.of(
            requireFamily(persons, RedisSnapshotFamilyCode.PERSONS),
            requireFamily(organizations, RedisSnapshotFamilyCode.ORGANIZATIONS),
            requireFamily(partyRoles, RedisSnapshotFamilyCode.PARTY_ROLES),
            requireFamily(positionRoles, RedisSnapshotFamilyCode.POSITION_ROLES),
            requireFamily(roles, RedisSnapshotFamilyCode.ROLES),
            requireFamily(privileges, RedisSnapshotFamilyCode.PRIVILEGES)
        );

        long derivedEntryCount = 0;
        long derivedLogicalBytes = 0;
        MessageDigest derivedDigest = RedisSnapshotDigestSupport.sha256(CONTENT_DOMAIN);
        for (RedisSnapshotFamilyDigest familyDigest : familyDigests) {
            derivedEntryCount = Math.addExact(
                derivedEntryCount,
                familyDigest.entryCount()
            );
            derivedLogicalBytes = RedisSnapshotLogicalBytes.addExact(
                derivedLogicalBytes,
                familyDigest.logicalBytes()
            );
            RedisSnapshotDigestSupport.updateFamilyCode(
                derivedDigest,
                familyDigest.family()
            );
            RedisSnapshotDigestSupport.updateFrame(derivedDigest, familyDigest.digest());
            RedisSnapshotDigestSupport.updateLong(derivedDigest, familyDigest.entryCount());
        }
        RedisSnapshotDigestSupport.updateLong(derivedDigest, derivedEntryCount);

        this.entryCount = derivedEntryCount;
        this.logicalBytes = derivedLogicalBytes;
        this.digest = derivedDigest.digest();
    }

    List<RedisSnapshotFamilyDigest> familyDigests() {
        return familyDigests;
    }

    RedisSnapshotFamilyDigest familyDigest(RedisSnapshotFamilyCode family) {
        Objects.requireNonNull(family, "family must not be null");
        return switch (family) {
            case PERSONS -> familyDigests.get(0);
            case ORGANIZATIONS -> familyDigests.get(1);
            case PARTY_ROLES -> familyDigests.get(2);
            case POSITION_ROLES -> familyDigests.get(3);
            case ROLES -> familyDigests.get(4);
            case PRIVILEGES -> familyDigests.get(5);
        };
    }

    long entryCount() {
        return entryCount;
    }

    long logicalBytes() {
        return logicalBytes;
    }

    byte[] digest() {
        return digest.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisSnapshotContentDigest)) {
            return false;
        }
        RedisSnapshotContentDigest that = (RedisSnapshotContentDigest) other;
        return entryCount == that.entryCount
            && logicalBytes == that.logicalBytes
            && familyDigests.equals(that.familyDigests)
            && Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(familyDigests, entryCount, logicalBytes);
        return 31 * result + Arrays.hashCode(digest);
    }

    private static RedisSnapshotFamilyDigest requireFamily(
        RedisSnapshotFamilyDigest familyDigest,
        RedisSnapshotFamilyCode expected
    ) {
        Objects.requireNonNull(familyDigest, expected + " familyDigest must not be null");
        if (familyDigest.family() != expected) {
            throw new IllegalArgumentException(
                "expected family " + expected + " but received " + familyDigest.family()
            );
        }
        return familyDigest;
    }
}
