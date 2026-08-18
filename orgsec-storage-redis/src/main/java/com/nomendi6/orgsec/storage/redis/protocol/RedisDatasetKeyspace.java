package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Canonical, dataset-isolated Redis protocol-v1 keyspace.
 *
 * <p>The raw dataset identifier is never placed in a key. Its SHA-256 over the exact UTF-8 byte
 * sequence is used as the Redis hash tag, keeping all keys for one dataset in one slot while
 * keeping distinct datasets in distinct namespaces.</p>
 */
final class RedisDatasetKeyspace {

    private static final String KEYSPACE_PREFIX =
        "orgsec:v" + RedisWireProtocol.VERSION_ONE + ":";

    private final String securityDatasetId;
    private final String datasetHash;
    private final String hashTag;
    private final String datasetPrefix;

    /**
     * Creates the canonical protocol-v1 keyspace for a stable dataset identifier.
     *
     * <p>This constructor intentionally accepts the raw identifier because the control key must
     * be selected before its identity can be read. It does not negotiate a protocol version: all
     * keys returned by this class remain fixed to the {@code orgsec:v1:} namespace.</p>
     *
     * @param securityDatasetId stable non-blank dataset identifier
     */
    RedisDatasetKeyspace(String securityDatasetId) {
        if (securityDatasetId == null || securityDatasetId.trim().isEmpty()) {
            throw new IllegalArgumentException("securityDatasetId must not be blank");
        }
        this.securityDatasetId = securityDatasetId;
        this.datasetHash = sha256(securityDatasetId);
        this.hashTag = "{" + datasetHash + "}";
        this.datasetPrefix = KEYSPACE_PREFIX + hashTag;
    }

    /**
     * Returns the exact trusted dataset identifier from which this keyspace was derived.
     *
     * <p>The value is retained so mutation protocols can exact-check decoded Redis metadata instead
     * of deriving write keys from that untrusted metadata.</p>
     *
     * @return exact dataset identifier
     */
    String securityDatasetId() {
        return securityDatasetId;
    }

    /** Returns whether a candidate identifier is the exact source of this namespace and hash. */
    boolean matchesSecurityDatasetId(String candidate) {
        return securityDatasetId.equals(candidate) && datasetHash.equals(sha256(candidate));
    }

    /**
     * Returns the lower-case SHA-256 of the exact UTF-8 dataset identifier.
     *
     * @return 64-character lower-case hexadecimal digest
     */
    String datasetHash() {
        return datasetHash;
    }

    /**
     * Returns the Redis Cluster hash tag, including braces.
     *
     * @return canonical hash tag
     */
    String hashTag() {
        return hashTag;
    }

    /**
     * Returns the authoritative control-envelope key.
     *
     * @return control key
     */
    String controlKey() {
        return datasetPrefix + ":control";
    }

    /**
     * Returns the dataset-scoped coordinator lease key.
     *
     * @return coordinator lease key
     */
    String leaseKey() {
        return datasetPrefix + ":lease";
    }

    /**
     * Returns the persistent monotonic coordinator fencing-sequence key.
     *
     * <p>This key is protocol metadata. It must never receive a TTL and must never be a GC
     * target.</p>
     *
     * @return coordinator fencing-sequence key
     */
    String leaseCounterKey() {
        return datasetPrefix + ":lease-counter";
    }

    /**
     * Returns the immutable manifest key for a snapshot.
     *
     * @param snapshotId snapshot UUID
     * @return manifest key
     */
    String manifestKey(UUID snapshotId) {
        return snapshotPrefix(snapshotId) + ":manifest";
    }

    /**
     * Returns the data key for one family in a snapshot.
     *
     * @param snapshotId snapshot UUID
     * @param family snapshot family
     * @return family data key
     */
    String familyKey(UUID snapshotId, RedisSnapshotFamily family) {
        Objects.requireNonNull(family, "family must not be null");
        return snapshotPrefix(snapshotId) + ":family:" + familyWireName(family);
    }

    /**
     * Returns the canonical sorted-key index for one snapshot family.
     *
     * <p>The immutable data hash and this lexicographic index are accounted and published as one
     * family. The index makes digest calculation independent of bootstrap batch order.</p>
     *
     * @param snapshotId snapshot UUID
     * @param family snapshot family
     * @return family index key
     */
    String familyIndexKey(UUID snapshotId, RedisSnapshotFamily family) {
        return familyKey(snapshotId, family) + ":index";
    }

    private String snapshotPrefix(UUID snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        return datasetPrefix + ":snapshot:" + snapshotId;
    }

    private static String familyWireName(RedisSnapshotFamily family) {
        return switch (family) {
            case PERSONS -> "persons";
            case ORGANIZATIONS -> "organizations";
            case PARTY_ROLES -> "party-roles";
            case POSITION_ROLES -> "position-roles";
            case ROLES -> "roles";
            case PRIVILEGES -> "privileges";
        };
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            digest.update(encoded);
            return HexFormat.of().formatHex(digest.digest());
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "securityDatasetId must be a well-formed Unicode string",
                exception
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
