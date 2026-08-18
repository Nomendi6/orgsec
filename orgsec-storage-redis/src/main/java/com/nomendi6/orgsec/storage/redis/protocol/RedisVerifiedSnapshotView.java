package com.nomendi6.orgsec.storage.redis.protocol;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Contextually verified manifest bound to the exact READY generation that selected it.
 */
final class RedisVerifiedSnapshotView {

    private final RedisSnapshotGeneration generation;
    private final RedisSnapshotManifest.Verified manifest;

    private RedisVerifiedSnapshotView(
        RedisSnapshotGeneration generation,
        RedisSnapshotManifest.Verified manifest
    ) {
        this.generation = Objects.requireNonNull(generation, "generation must not be null");
        this.manifest = Objects.requireNonNull(manifest, "manifest must not be null");
        if (!generation.activeSnapshotId().equals(manifest.snapshotId())) {
            throw new IllegalArgumentException(
                "verified manifest snapshot must exactly match the generation active snapshot"
            );
        }
        if (!generation.identity().equals(manifest.sourceFence().getIdentity())) {
            throw new IllegalArgumentException(
                "verified manifest identity must exactly match the generation identity"
            );
        }
    }

    /**
     * Adopts a snapshot only after the transport has atomically rechecked the exact generation and
     * manifest wire fields retained by the read handle.
     *
     * @param handle unverified read handle selected before bounded family reads
     * @param recheckedGeneration exact generation observed by the final atomic recheck
     * @param recheckedManifestWireFields exact protocol-v1 manifest pairs observed by that recheck
     * @param recomputedContent canonical content recomputed from all six bounded family reads
     * @return contextually verified view bound to the exact rechecked generation
     */
    static RedisVerifiedSnapshotView adopt(
        RedisSnapshotReadHandle handle,
        RedisSnapshotGeneration recheckedGeneration,
        Map<String, String> recheckedManifestWireFields,
        RedisSnapshotContentDigest recomputedContent
    ) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(
            recheckedGeneration,
            "recheckedGeneration must not be null"
        );
        Objects.requireNonNull(
            recheckedManifestWireFields,
            "recheckedManifestWireFields must not be null"
        );
        Objects.requireNonNull(recomputedContent, "recomputedContent must not be null");

        if (!handle.generation().equals(recheckedGeneration)) {
            throw new IllegalStateException(
                "final generation must exactly match the retained read generation"
            );
        }
        if (recheckedManifestWireFields.size()
            != RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT) {
            throw new IllegalStateException(
                "final manifest must contain exactly the retained " +
                    RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT + " wire fields"
            );
        }
        for (String field : RedisSnapshotManifestCodec.orderedFields()) {
            if (!recheckedManifestWireFields.containsKey(field)
                || !Objects.equals(
                    handle.manifestWireFields().get(field),
                    recheckedManifestWireFields.get(field)
                )) {
                throw new IllegalStateException(
                    "final manifest must exactly match the retained wire fields"
                );
            }
        }

        RedisSnapshotManifest.Verified manifest =
            RedisSnapshotManifest.verifyForRequestedSnapshot(
                handle.manifestCandidate(),
                handle.expectedSourceFence(),
                handle.requestedSnapshotId(),
                recomputedContent
            );
        return new RedisVerifiedSnapshotView(recheckedGeneration, manifest);
    }

    RedisSnapshotGeneration generation() {
        return generation;
    }

    RedisSnapshotManifest.Verified manifest() {
        return manifest;
    }

    UUID snapshotId() {
        return manifest.snapshotId();
    }
}
