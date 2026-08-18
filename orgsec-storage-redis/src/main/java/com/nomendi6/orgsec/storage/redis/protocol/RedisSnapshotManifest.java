package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;

import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Trust boundary for an immutable protocol-v1 Redis snapshot manifest.
 *
 * <p>A publication value is derived only from a source fence, a fresh cryptographic UUIDv4 and a
 * recomputed canonical content result. Decoding wire fields creates only an {@link Unverified}
 * value. Adoption requires {@link #verifyForRequestedSnapshot(Unverified, SecurityDatasetFence,
 * UUID, RedisSnapshotContentDigest)}, which rebuilds the trusted value from expected context
 * after every field matches.</p>
 */
final class RedisSnapshotManifest {

    private final SecurityDatasetFence sourceFence;
    private final UUID snapshotId;
    private final String contentDigest;
    private final long personsCount;
    private final long organizationsCount;
    private final long partyRolesCount;
    private final long positionRolesCount;
    private final long rolesCount;
    private final long privilegesCount;
    private final long totalEntryCount;
    private final long accountedBytes;

    private RedisSnapshotManifest(
        SecurityDatasetFence sourceFence,
        UUID snapshotId,
        RedisSnapshotContentDigest content
    ) {
        this.sourceFence = RedisWireProtocol.requireVersionOne(sourceFence);
        this.snapshotId = requireUuidV4(snapshotId, "snapshotId");
        Objects.requireNonNull(content, "content must not be null");
        this.contentDigest = HexFormat.of().formatHex(content.digest());
        this.personsCount = content.familyDigest(
            RedisSnapshotFamilyCode.PERSONS
        ).entryCount();
        this.organizationsCount = content.familyDigest(
            RedisSnapshotFamilyCode.ORGANIZATIONS
        ).entryCount();
        this.partyRolesCount = content.familyDigest(
            RedisSnapshotFamilyCode.PARTY_ROLES
        ).entryCount();
        this.positionRolesCount = content.familyDigest(
            RedisSnapshotFamilyCode.POSITION_ROLES
        ).entryCount();
        this.rolesCount = content.familyDigest(RedisSnapshotFamilyCode.ROLES).entryCount();
        this.privilegesCount = content.familyDigest(
            RedisSnapshotFamilyCode.PRIVILEGES
        ).entryCount();
        this.totalEntryCount = content.entryCount();
        this.accountedBytes = content.logicalBytes();
    }

    /**
     * Begins publication with a fresh SecureRandom-backed UUIDv4 available for staging keys.
     *
     * @param sourceFence exact fence read with the source snapshot
     * @return one-shot pending publication owned by this library
     */
    static PendingPublication beginPublication(SecurityDatasetFence sourceFence) {
        return new PendingPublication(
            RedisWireProtocol.requireVersionOne(sourceFence),
            UUID.randomUUID()
        );
    }

    /**
     * Convenience for callers that already hold complete recomputed content.
     *
     * <p>The production staged flow uses {@link #beginPublication(SecurityDatasetFence)}, obtains
     * its library-generated snapshot ID, writes immutable staging keys and invokes
     * {@link PendingPublication#seal(RedisSnapshotContentDigest)} exactly once.</p>
     *
     * @param sourceFence exact fence read with the source snapshot
     * @param content recomputed canonical result for all six families
     * @return trusted value eligible for wire encoding
     */
    static Verified publication(
        SecurityDatasetFence sourceFence,
        RedisSnapshotContentDigest content
    ) {
        return beginPublication(sourceFence).seal(content);
    }

    /**
     * Contextually verifies decoded fields for the exact requested manifest key and content.
     *
     * <p>{@code requestedSnapshotId} is the UUID bound by the exact Redis manifest key selected by
     * the bounded transport reader. A syntactically valid manifest stored under another snapshot
     * key is rejected.</p>
     *
     * @param candidate syntactically valid but untrusted wire value
     * @param expectedSourceFence exact source-database fence expected for the requested snapshot
     * @param requestedSnapshotId snapshot UUID bound by the requested manifest key
     * @param recomputedContent canonical content read for that requested snapshot
     * @return trusted verified manifest rebuilt exclusively from expected context
     */
    static Verified verifyForRequestedSnapshot(
        Unverified candidate,
        SecurityDatasetFence expectedSourceFence,
        UUID requestedSnapshotId,
        RedisSnapshotContentDigest recomputedContent
    ) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(
            expectedSourceFence,
            "expectedSourceFence must not be null"
        );
        RedisWireProtocol.requireVersionOne(expectedSourceFence);
        Objects.requireNonNull(recomputedContent, "recomputedContent must not be null");
        if (!isUuidV4(requestedSnapshotId)) {
            throw verification(
                RedisSnapshotManifestVerificationException.Reason.REQUESTED_SNAPSHOT_ID_INVALID
            );
        }

        SecurityDatasetIdentity actualIdentity = candidate.sourceFence.getIdentity();
        SecurityDatasetIdentity expectedIdentity = expectedSourceFence.getIdentity();
        requireEqual(
            actualIdentity.getSecurityDatasetId(),
            expectedIdentity.getSecurityDatasetId(),
            RedisSnapshotManifestVerificationException.Reason.DATASET_ID_MISMATCH
        );
        requireEqual(
            actualIdentity.getProtocolVersion(),
            expectedIdentity.getProtocolVersion(),
            RedisSnapshotManifestVerificationException.Reason.PROTOCOL_VERSION_MISMATCH
        );
        requireEqual(
            candidate.sourceFence.getSecurityContentVersion(),
            expectedSourceFence.getSecurityContentVersion(),
            RedisSnapshotManifestVerificationException.Reason.SECURITY_CONTENT_VERSION_MISMATCH
        );
        requireEqual(
            candidate.snapshotId,
            requestedSnapshotId,
            RedisSnapshotManifestVerificationException.Reason.SNAPSHOT_ID_MISMATCH
        );

        String expectedContentDigest = HexFormat.of().formatHex(recomputedContent.digest());
        requireEqual(
            candidate.contentDigest,
            expectedContentDigest,
            RedisSnapshotManifestVerificationException.Reason.CONTENT_DIGEST_MISMATCH
        );
        requireEqual(
            candidate.personsCount,
            recomputedContent.familyDigest(RedisSnapshotFamilyCode.PERSONS).entryCount(),
            RedisSnapshotManifestVerificationException.Reason.PERSONS_COUNT_MISMATCH
        );
        requireEqual(
            candidate.organizationsCount,
            recomputedContent.familyDigest(RedisSnapshotFamilyCode.ORGANIZATIONS).entryCount(),
            RedisSnapshotManifestVerificationException.Reason.ORGANIZATIONS_COUNT_MISMATCH
        );
        requireEqual(
            candidate.partyRolesCount,
            recomputedContent.familyDigest(RedisSnapshotFamilyCode.PARTY_ROLES).entryCount(),
            RedisSnapshotManifestVerificationException.Reason.PARTY_ROLES_COUNT_MISMATCH
        );
        requireEqual(
            candidate.positionRolesCount,
            recomputedContent.familyDigest(RedisSnapshotFamilyCode.POSITION_ROLES).entryCount(),
            RedisSnapshotManifestVerificationException.Reason.POSITION_ROLES_COUNT_MISMATCH
        );
        requireEqual(
            candidate.rolesCount,
            recomputedContent.familyDigest(RedisSnapshotFamilyCode.ROLES).entryCount(),
            RedisSnapshotManifestVerificationException.Reason.ROLES_COUNT_MISMATCH
        );
        requireEqual(
            candidate.privilegesCount,
            recomputedContent.familyDigest(RedisSnapshotFamilyCode.PRIVILEGES).entryCount(),
            RedisSnapshotManifestVerificationException.Reason.PRIVILEGES_COUNT_MISMATCH
        );
        requireEqual(
            candidate.accountedBytes,
            recomputedContent.logicalBytes(),
            RedisSnapshotManifestVerificationException.Reason.ACCOUNTED_BYTES_MISMATCH
        );

        return new Verified(new RedisSnapshotManifest(
            expectedSourceFence,
            requestedSnapshotId,
            recomputedContent
        ));
    }

    static Unverified unverified(
        SecurityDatasetFence sourceFence,
        UUID snapshotId,
        String contentDigest,
        long personsCount,
        long organizationsCount,
        long partyRolesCount,
        long positionRolesCount,
        long rolesCount,
        long privilegesCount,
        long accountedBytes
    ) {
        return new Unverified(
            sourceFence,
            snapshotId,
            contentDigest,
            personsCount,
            organizationsCount,
            partyRolesCount,
            positionRolesCount,
            rolesCount,
            privilegesCount,
            accountedBytes
        );
    }

    private static void requireEqual(
        Object actual,
        Object expected,
        RedisSnapshotManifestVerificationException.Reason reason
    ) {
        if (!Objects.equals(actual, expected)) {
            throw verification(reason);
        }
    }

    private static void requireEqual(
        long actual,
        long expected,
        RedisSnapshotManifestVerificationException.Reason reason
    ) {
        if (actual != expected) {
            throw verification(reason);
        }
    }

    private static UUID requireUuidV4(UUID value, String name) {
        if (!isUuidV4(value)) {
            throw new IllegalArgumentException(name + " must be an RFC 4122 UUIDv4");
        }
        return value;
    }

    private static boolean isUuidV4(UUID value) {
        return value != null && value.version() == 4 && value.variant() == 2;
    }

    private static RedisSnapshotManifestVerificationException verification(
        RedisSnapshotManifestVerificationException.Reason reason
    ) {
        return new RedisSnapshotManifestVerificationException(reason);
    }

    /**
     * Syntactically canonical wire fields that have not crossed the contextual trust boundary.
     */
    static final class Unverified {

        private final SecurityDatasetFence sourceFence;
        private final UUID snapshotId;
        private final String contentDigest;
        private final long personsCount;
        private final long organizationsCount;
        private final long partyRolesCount;
        private final long positionRolesCount;
        private final long rolesCount;
        private final long privilegesCount;
        private final long accountedBytes;

        private Unverified(
            SecurityDatasetFence sourceFence,
            UUID snapshotId,
            String contentDigest,
            long personsCount,
            long organizationsCount,
            long partyRolesCount,
            long positionRolesCount,
            long rolesCount,
            long privilegesCount,
            long accountedBytes
        ) {
            this.sourceFence = RedisWireProtocol.requireVersionOne(sourceFence);
            this.snapshotId = requireUuidV4(snapshotId, "snapshotId");
            this.contentDigest = Objects.requireNonNull(
                contentDigest,
                "contentDigest must not be null"
            );
            this.personsCount = personsCount;
            this.organizationsCount = organizationsCount;
            this.partyRolesCount = partyRolesCount;
            this.positionRolesCount = positionRolesCount;
            this.rolesCount = rolesCount;
            this.privilegesCount = privilegesCount;
            this.accountedBytes = accountedBytes;
        }

        SecurityDatasetFence sourceFence() {
            return sourceFence;
        }

        UUID snapshotId() {
            return snapshotId;
        }

        String contentDigest() {
            return contentDigest;
        }

        long personsCount() {
            return personsCount;
        }

        long organizationsCount() {
            return organizationsCount;
        }

        long partyRolesCount() {
            return partyRolesCount;
        }

        long positionRolesCount() {
            return positionRolesCount;
        }

        long rolesCount() {
            return rolesCount;
        }

        long privilegesCount() {
            return privilegesCount;
        }

        long accountedBytes() {
            return accountedBytes;
        }
    }

    /**
     * Library-owned one-shot publication token exposing its UUIDv4 before content staging.
     */
    static final class PendingPublication {

        private final SecurityDatasetFence sourceFence;
        private final UUID snapshotId;

        private boolean sealed;

        private PendingPublication(SecurityDatasetFence sourceFence, UUID snapshotId) {
            this.sourceFence = RedisWireProtocol.requireVersionOne(sourceFence);
            this.snapshotId = requireUuidV4(snapshotId, "snapshotId");
        }

        UUID snapshotId() {
            return snapshotId;
        }

        /**
         * Seals the staged content exactly once and returns a trusted manifest.
         *
         * @param content recomputed canonical result for the staged snapshot
         * @return trusted value eligible for wire encoding
         */
        synchronized Verified seal(RedisSnapshotContentDigest content) {
            if (sealed) {
                throw new IllegalStateException("pending publication is already sealed");
            }
            Objects.requireNonNull(content, "content must not be null");
            sealed = true;
            return new Verified(new RedisSnapshotManifest(sourceFence, snapshotId, content));
        }
    }

    /**
     * Contextually trusted manifest eligible for publication/adoption wire encoding.
     */
    static final class Verified {

        private final RedisSnapshotManifest manifest;

        private Verified(RedisSnapshotManifest manifest) {
            this.manifest = Objects.requireNonNull(manifest, "manifest must not be null");
        }

        SecurityDatasetFence sourceFence() {
            return manifest.sourceFence;
        }

        UUID snapshotId() {
            return manifest.snapshotId;
        }

        String contentDigest() {
            return manifest.contentDigest;
        }

        long personsCount() {
            return manifest.personsCount;
        }

        long organizationsCount() {
            return manifest.organizationsCount;
        }

        long partyRolesCount() {
            return manifest.partyRolesCount;
        }

        long positionRolesCount() {
            return manifest.positionRolesCount;
        }

        long rolesCount() {
            return manifest.rolesCount;
        }

        long privilegesCount() {
            return manifest.privilegesCount;
        }

        long totalEntryCount() {
            return manifest.totalEntryCount;
        }

        long accountedBytes() {
            return manifest.accountedBytes;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Verified)) {
                return false;
            }
            Verified that = (Verified) other;
            return manifest.equals(that.manifest);
        }

        @Override
        public int hashCode() {
            return manifest.hashCode();
        }

        @Override
        public String toString() {
            return "RedisSnapshotManifest.Verified{" +
                "snapshotId=" + snapshotId() +
                ", totalEntryCount=" + totalEntryCount() +
                ", accountedBytes=" + accountedBytes() +
                '}';
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RedisSnapshotManifest)) {
            return false;
        }
        RedisSnapshotManifest that = (RedisSnapshotManifest) other;
        return personsCount == that.personsCount
            && organizationsCount == that.organizationsCount
            && partyRolesCount == that.partyRolesCount
            && positionRolesCount == that.positionRolesCount
            && rolesCount == that.rolesCount
            && privilegesCount == that.privilegesCount
            && totalEntryCount == that.totalEntryCount
            && accountedBytes == that.accountedBytes
            && sourceFence.equals(that.sourceFence)
            && snapshotId.equals(that.snapshotId)
            && contentDigest.equals(that.contentDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            sourceFence,
            snapshotId,
            contentDigest,
            personsCount,
            organizationsCount,
            partyRolesCount,
            positionRolesCount,
            rolesCount,
            privilegesCount,
            totalEntryCount,
            accountedBytes
        );
    }
}
