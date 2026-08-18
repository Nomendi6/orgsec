package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable owner-neutral handle for a bounded read of one requested Redis snapshot.
 *
 * <p>The manifest candidate remains explicitly unverified. Holding this value never implies that
 * its source fence, digest, counts or snapshot ID have crossed the contextual trust boundary.</p>
 */
final class RedisSnapshotReadHandle {

    private final RedisSnapshotGeneration generation;
    private final SecurityDatasetFence expectedSourceFence;
    private final UUID requestedSnapshotId;
    private final Map<String, String> manifestWireFields;
    private final RedisSnapshotManifest.Unverified manifestCandidate;

    RedisSnapshotReadHandle(
        RedisSnapshotGeneration generation,
        SecurityDatasetFence expectedSourceFence,
        UUID requestedSnapshotId,
        Map<String, String> manifestWireFields,
        RedisSnapshotManifest.Unverified manifestCandidate
    ) {
        this.generation = Objects.requireNonNull(generation, "generation must not be null");
        Objects.requireNonNull(
            expectedSourceFence,
            "expectedSourceFence must not be null"
        );
        this.expectedSourceFence = RedisWireProtocol.requireVersionOne(expectedSourceFence);
        if (!generation.identity().equals(expectedSourceFence.getIdentity())) {
            throw new IllegalArgumentException(
                "expected source-fence identity must exactly match the generation identity"
            );
        }
        this.requestedSnapshotId = Objects.requireNonNull(
            requestedSnapshotId,
            "requestedSnapshotId must not be null"
        );
        if (!generation.activeSnapshotId().equals(requestedSnapshotId)) {
            throw new IllegalArgumentException(
                "requested snapshot ID must exactly match the generation active snapshot"
            );
        }

        Map<String, String> canonicalFields = canonicalManifestFields(manifestWireFields);
        RedisSnapshotManifest.Unverified decoded = new RedisSnapshotManifestCodec().decode(
            canonicalFields
        );
        this.manifestCandidate = Objects.requireNonNull(
            manifestCandidate,
            "manifestCandidate must not be null"
        );
        if (!sameCandidate(decoded, manifestCandidate)) {
            throw new IllegalArgumentException(
                "manifestCandidate must exactly match the retained manifest wire fields"
            );
        }
        this.manifestWireFields = Collections.unmodifiableMap(canonicalFields);
    }

    RedisSnapshotGeneration generation() {
        return generation;
    }

    SecurityDatasetFence expectedSourceFence() {
        return expectedSourceFence;
    }

    UUID requestedSnapshotId() {
        return requestedSnapshotId;
    }

    Map<String, String> manifestWireFields() {
        return manifestWireFields;
    }

    RedisSnapshotManifest.Unverified manifestCandidate() {
        return manifestCandidate;
    }

    private static Map<String, String> canonicalManifestFields(
        Map<String, String> manifestWireFields
    ) {
        Objects.requireNonNull(manifestWireFields, "manifestWireFields must not be null");
        Map<String, String> supplied = new LinkedHashMap<>(manifestWireFields);
        if (supplied.size() != RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT) {
            throw new IllegalArgumentException(
                "manifestWireFields must contain exactly " +
                    RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT + " canonical fields"
            );
        }

        Map<String, String> canonical = new LinkedHashMap<>();
        for (String field : RedisSnapshotManifestCodec.orderedFields()) {
            if (!supplied.containsKey(field)) {
                throw new IllegalArgumentException(
                    "manifestWireFields must contain exactly the canonical manifest fields"
                );
            }
            canonical.put(field, supplied.get(field));
        }
        return canonical;
    }

    private static boolean sameCandidate(
        RedisSnapshotManifest.Unverified first,
        RedisSnapshotManifest.Unverified second
    ) {
        return first.sourceFence().equals(second.sourceFence())
            && first.snapshotId().equals(second.snapshotId())
            && first.contentDigest().equals(second.contentDigest())
            && first.personsCount() == second.personsCount()
            && first.organizationsCount() == second.organizationsCount()
            && first.partyRolesCount() == second.partyRolesCount()
            && first.positionRolesCount() == second.positionRolesCount()
            && first.rolesCount() == second.rolesCount()
            && first.privilegesCount() == second.privilegesCount()
            && first.accountedBytes() == second.accountedBytes();
    }
}
