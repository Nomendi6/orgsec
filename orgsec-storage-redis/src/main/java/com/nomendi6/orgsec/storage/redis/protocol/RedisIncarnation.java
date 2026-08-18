package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Immutable identity of one Redis storage incarnation.
 *
 * <p>The physical primary run ID prevents a promoted or restarted primary from being mistaken for
 * its predecessor. The independently random UUID prevents metadata loss followed by a repeated
 * numeric publication counter from recreating a previously accepted identity.</p>
 */
final class RedisIncarnation {

    private static final Pattern PRIMARY_RUN_ID = Pattern.compile("[0-9a-f]{40}");

    private final String primaryRunId;
    private final UUID storageUuid;

    /**
     * Creates an incarnation.
     *
     * @param primaryRunId canonical lower-case Redis primary run ID
     * @param storageUuid cryptographically random RFC 4122 version-4 UUID
     */
    RedisIncarnation(String primaryRunId, UUID storageUuid) {
        if (primaryRunId == null || !PRIMARY_RUN_ID.matcher(primaryRunId).matches()) {
            throw new IllegalArgumentException(
                "primaryRunId must be a canonical 40-character lower-case hexadecimal Redis run ID"
            );
        }
        if (storageUuid == null || storageUuid.version() != 4 || storageUuid.variant() != 2) {
            throw new IllegalArgumentException(
                "storageUuid must be an RFC 4122 version-4 UUID"
            );
        }
        this.primaryRunId = primaryRunId;
        this.storageUuid = storageUuid;
    }

    /**
     * Creates an incarnation with a cryptographically strong random UUID.
     *
     * @param primaryRunId canonical lower-case Redis primary run ID
     * @return a new incarnation
     */
    static RedisIncarnation create(String primaryRunId) {
        return new RedisIncarnation(primaryRunId, UUID.randomUUID());
    }

    String getPrimaryRunId() {
        return primaryRunId;
    }

    UUID getStorageUuid() {
        return storageUuid;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisIncarnation)) {
            return false;
        }
        RedisIncarnation that = (RedisIncarnation) other;
        return primaryRunId.equals(that.primaryRunId) && storageUuid.equals(that.storageUuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primaryRunId, storageUuid);
    }

    @Override
    public String toString() {
        return "RedisIncarnation{" +
            "primaryRunId='" + primaryRunId + '\'' +
            ", storageUuid=" + storageUuid +
            '}';
    }
}
