package com.nomendi6.orgsec.storage.redis.bootstrap;

/**
 * A data family that must be present in every complete Redis security snapshot.
 *
 * <p>The enum names are API identifiers, not Redis key or manifest wire values. The Redis
 * protocol implementation is responsible for defining and fingerprinting its canonical wire
 * representation.</p>
 */
public enum RedisSnapshotFamily {
    PERSONS,
    ORGANIZATIONS,
    PARTY_ROLES,
    POSITION_ROLES,
    ROLES,
    PRIVILEGES
}
