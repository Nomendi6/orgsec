package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.List;

/**
 * Stable one-byte family identifiers used by snapshot digest framing.
 *
 * <p>The codes and their canonical order are protocol values. Neither enum ordinals nor an
 * automatically discovered set of enum constants may define the wire format.</p>
 */
enum RedisSnapshotFamilyCode {

    PERSONS(0x01),
    ORGANIZATIONS(0x02),
    PARTY_ROLES(0x03),
    POSITION_ROLES(0x04),
    ROLES(0x05),
    PRIVILEGES(0x06);

    private static final List<RedisSnapshotFamilyCode> CANONICAL_ORDER = List.of(
        PERSONS,
        ORGANIZATIONS,
        PARTY_ROLES,
        POSITION_ROLES,
        ROLES,
        PRIVILEGES
    );

    private final byte wireCode;

    RedisSnapshotFamilyCode(int wireCode) {
        this.wireCode = (byte) wireCode;
    }

    byte wireCode() {
        return wireCode;
    }

    static int familyCount() {
        return 6;
    }

    static RedisSnapshotFamilyCode atCanonicalIndex(int index) {
        return CANONICAL_ORDER.get(index);
    }
}
