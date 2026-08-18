package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSnapshotManifestTest {

    private static final String SHA_B = "0123456789abcdef".repeat(4);
    private static final UUID OTHER_SNAPSHOT_ID =
        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID NON_V4_SNAPSHOT_ID =
        UUID.fromString("aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa");

    private final RedisSnapshotManifestCodec codec = new RedisSnapshotManifestCodec();

    @Test
    void publicationDerivesEveryContentFieldAndFreshCanonicalUuidV4() {
        SecurityDatasetFence fence = fence();
        RedisSnapshotContentDigest content = content(1);

        RedisSnapshotManifest.PendingPublication firstPending =
            RedisSnapshotManifest.beginPublication(fence);
        RedisSnapshotManifest.PendingPublication secondPending =
            RedisSnapshotManifest.beginPublication(fence);

        UUID firstStagingId = firstPending.snapshotId();
        assertThat(firstStagingId.version()).isEqualTo(4);
        assertThat(firstStagingId.variant()).isEqualTo(2);
        assertThat(firstStagingId.toString()).isLowerCase();
        assertThat(secondPending.snapshotId()).isNotEqualTo(firstStagingId);

        RedisSnapshotManifest.Verified first = firstPending.seal(content);
        RedisSnapshotManifest.Verified second = secondPending.seal(content);

        assertThat(first.sourceFence()).isSameAs(fence);
        assertThat(first.snapshotId()).isEqualTo(firstStagingId);
        assertThat(second.snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(first.contentDigest()).isEqualTo(HexFormat.of().formatHex(content.digest()));
        assertThat(first.personsCount()).isEqualTo(1);
        assertThat(first.organizationsCount()).isEqualTo(2);
        assertThat(first.partyRolesCount()).isEqualTo(3);
        assertThat(first.positionRolesCount()).isEqualTo(4);
        assertThat(first.rolesCount()).isEqualTo(5);
        assertThat(first.privilegesCount()).isEqualTo(6);
        assertThat(first.totalEntryCount()).isEqualTo(21);
        assertThat(first.accountedBytes()).isEqualTo(2100);

        assertThat(Modifier.isFinal(RedisSnapshotManifest.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(RedisSnapshotManifest.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(RedisSnapshotManifest.class.getDeclaredConstructors()))
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Modifier.isFinal(RedisSnapshotManifest.Unverified.class.getModifiers()))
            .isTrue();
        assertThat(Modifier.isFinal(RedisSnapshotManifest.PendingPublication.class.getModifiers()))
            .isTrue();
        assertThat(Modifier.isFinal(RedisSnapshotManifest.Verified.class.getModifiers()))
            .isTrue();
        assertThat(Arrays.stream(
            RedisSnapshotManifest.PendingPublication.class.getDeclaredConstructors()
        )).allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));

        assertThatThrownBy(() -> firstPending.seal(content))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("pending publication is already sealed");
    }

    @Test
    void failedNullSealDoesNotConsumeTheOneShotPublicationToken() {
        RedisSnapshotManifest.PendingPublication pending =
            RedisSnapshotManifest.beginPublication(fence());

        assertThatThrownBy(() -> pending.seal(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("content");

        RedisSnapshotManifest.Verified sealed = pending.seal(content(1));
        assertThat(sealed.snapshotId()).isEqualTo(pending.snapshotId());
    }

    @Test
    void publicationSupportsACompletelyEmptyDerivedSnapshot() {
        RedisSnapshotManifest.Verified manifest = RedisSnapshotManifest.publication(
            fence(),
            emptyContent()
        );

        assertThat(manifest.totalEntryCount()).isZero();
        assertThat(manifest.accountedBytes()).isZero();
        assertThat(List.of(
            manifest.personsCount(),
            manifest.organizationsCount(),
            manifest.partyRolesCount(),
            manifest.positionRolesCount(),
            manifest.rolesCount(),
            manifest.privilegesCount()
        )).containsOnly(0L);
    }

    @Test
    void syntacticDecodeNeedsContextualVerificationBeforeItBecomesTrusted() {
        SecurityDatasetFence expectedFence = fence();
        RedisSnapshotContentDigest expectedContent = content(1);
        RedisSnapshotManifest.Verified publication = RedisSnapshotManifest.publication(
            expectedFence,
            expectedContent
        );

        RedisSnapshotManifest.Unverified decoded = codec.decode(codec.encode(publication));
        RedisSnapshotManifest.Verified verified =
            RedisSnapshotManifest.verifyForRequestedSnapshot(
                decoded,
                expectedFence,
                publication.snapshotId(),
                expectedContent
            );

        assertThat(decoded).isExactlyInstanceOf(RedisSnapshotManifest.Unverified.class);
        assertThat(verified).isExactlyInstanceOf(RedisSnapshotManifest.Verified.class);
        assertThat(verified).isEqualTo(publication).hasSameHashCodeAs(publication);
        assertThat(codec.encode(verified)).isEqualTo(codec.encode(publication));
        assertThat(verified.toString())
            .contains("Verified", publication.snapshotId().toString(), "totalEntryCount=21")
            .doesNotContain("tenant-a", verified.contentDigest());
    }

    @Test
    void rejectsEveryIdentityAndFenceMismatchWithAFieldSpecificTypedReason() {
        RedisSnapshotManifest.Verified publication = publication();
        List<Mismatch> mismatches = List.of(
            mismatch(
                RedisSnapshotManifestCodec.FIELD_DATASET_ID,
                "attacker-secret-dataset",
                RedisSnapshotManifestVerificationException.Reason.DATASET_ID_MISMATCH
            ),
            mismatch(
                RedisSnapshotManifestCodec.FIELD_SECURITY_CONTENT_VERSION,
                "8",
                RedisSnapshotManifestVerificationException.Reason
                    .SECURITY_CONTENT_VERSION_MISMATCH
            )
        );

        for (Mismatch mismatch : mismatches) {
            Map<String, String> fields = mutableFields(publication);
            fields.put(mismatch.field(), mismatch.value());
            RedisSnapshotManifest.Unverified candidate = codec.decode(fields);

            RedisSnapshotManifestVerificationException exception = expectVerification(() ->
                RedisSnapshotManifest.verifyForRequestedSnapshot(
                    candidate,
                    fence(),
                    publication.snapshotId(),
                    content(1)
                )
            );

            assertVerification(exception, mismatch.reason());
            assertThat(exception.getMessage()).doesNotContain(mismatch.value());
        }
    }

    @Test
    void futureProtocolIsRejectedBeforePublicationVerificationOrWireAdoption() {
        RedisSnapshotManifest.Verified publication = publication();
        Map<String, String> fields = mutableFields(publication);
        fields.put(RedisSnapshotManifestCodec.FIELD_PROTOCOL_VERSION, "2");

        assertThatThrownBy(() -> codec.decode(fields))
            .isInstanceOfSatisfying(
                RedisSnapshotManifestCorruptionException.class,
                exception -> assertThat(exception.reason()).isEqualTo(
                    RedisSnapshotManifestCorruptionException.Reason.PROTOCOL_VERSION_INVALID
                )
            )
            .hasMessage(
                RedisSnapshotManifestCorruptionException.DIAGNOSTIC_CODE
                    + ":PROTOCOL_VERSION_INVALID"
            )
            .hasMessageNotContaining("2")
            .hasMessageNotContaining("tenant-a");

        SecurityDatasetFence futureFence = new SecurityDatasetFence(
            new SecurityDatasetIdentity("sensitive-dataset", 2),
            7
        );
        assertThatThrownBy(() -> RedisSnapshotManifest.beginPublication(futureFence))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");

        RedisSnapshotManifest.Unverified candidate = codec.decode(codec.encode(publication));
        assertThatThrownBy(() ->
            RedisSnapshotManifest.verifyForRequestedSnapshot(
                candidate,
                futureFence,
                publication.snapshotId(),
                content(1)
            )
        ).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
    }

    @Test
    void rejectsEveryContentDigestCountAndByteMismatchWithATypedReason() {
        RedisSnapshotManifest.Verified publication = publication();
        List<Mismatch> mismatches = List.of(
            mismatch(
                RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST,
                SHA_B,
                RedisSnapshotManifestVerificationException.Reason.CONTENT_DIGEST_MISMATCH
            ),
            mismatch(
                RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT,
                "2",
                RedisSnapshotManifestVerificationException.Reason.PERSONS_COUNT_MISMATCH
            ),
            mismatch(
                RedisSnapshotManifestCodec.FIELD_ORGANIZATIONS_COUNT,
                "3",
                RedisSnapshotManifestVerificationException.Reason.ORGANIZATIONS_COUNT_MISMATCH
            ),
            mismatch(
                RedisSnapshotManifestCodec.FIELD_PARTY_ROLES_COUNT,
                "4",
                RedisSnapshotManifestVerificationException.Reason.PARTY_ROLES_COUNT_MISMATCH
            ),
            mismatch(
                RedisSnapshotManifestCodec.FIELD_POSITION_ROLES_COUNT,
                "5",
                RedisSnapshotManifestVerificationException.Reason.POSITION_ROLES_COUNT_MISMATCH
            ),
            mismatch(
                RedisSnapshotManifestCodec.FIELD_ROLES_COUNT,
                "6",
                RedisSnapshotManifestVerificationException.Reason.ROLES_COUNT_MISMATCH
            ),
            mismatch(
                RedisSnapshotManifestCodec.FIELD_PRIVILEGES_COUNT,
                "7",
                RedisSnapshotManifestVerificationException.Reason.PRIVILEGES_COUNT_MISMATCH
            ),
            mismatch(
                RedisSnapshotManifestCodec.FIELD_ACCOUNTED_BYTES,
                "2101",
                RedisSnapshotManifestVerificationException.Reason.ACCOUNTED_BYTES_MISMATCH
            )
        );

        for (Mismatch mismatch : mismatches) {
            Map<String, String> fields = mutableFields(publication);
            fields.put(mismatch.field(), mismatch.value());
            RedisSnapshotManifestVerificationException exception = expectVerification(() ->
                RedisSnapshotManifest.verifyForRequestedSnapshot(
                    codec.decode(fields),
                    fence(),
                    publication.snapshotId(),
                    content(1)
                )
            );
            assertVerification(exception, mismatch.reason());
        }
    }

    @Test
    void recomputedFamilyBytesAreBoundByContentDigestEvenWhenCountsAndTotalBytesMatch() {
        RedisSnapshotManifest.Verified publication = publication();

        RedisSnapshotManifestVerificationException exception = expectVerification(() ->
            RedisSnapshotManifest.verifyForRequestedSnapshot(
                codec.decode(codec.encode(publication)),
                fence(),
                publication.snapshotId(),
                content(2)
            )
        );

        assertVerification(
            exception,
            RedisSnapshotManifestVerificationException.Reason.CONTENT_DIGEST_MISMATCH
        );
        assertThat(content(2).entryCount()).isEqualTo(content(1).entryCount());
        assertThat(content(2).logicalBytes()).isEqualTo(content(1).logicalBytes());
    }

    @Test
    void requestedManifestKeyUuidMustBeMatchingCanonicalRfc4122V4() {
        RedisSnapshotManifest.Verified publication = publication();
        RedisSnapshotManifest.Unverified candidate = codec.decode(codec.encode(publication));

        RedisSnapshotManifestVerificationException mismatch = expectVerification(() ->
            RedisSnapshotManifest.verifyForRequestedSnapshot(
                candidate,
                fence(),
                OTHER_SNAPSHOT_ID,
                content(1)
            )
        );
        assertVerification(
            mismatch,
            RedisSnapshotManifestVerificationException.Reason.SNAPSHOT_ID_MISMATCH
        );

        RedisSnapshotManifestVerificationException invalid = expectVerification(() ->
            RedisSnapshotManifest.verifyForRequestedSnapshot(
                candidate,
                fence(),
                NON_V4_SNAPSHOT_ID,
                content(1)
            )
        );
        assertVerification(
            invalid,
            RedisSnapshotManifestVerificationException.Reason.REQUESTED_SNAPSHOT_ID_INVALID
        );
    }

    @Test
    void rejectsMissingPublicationAndVerificationInputsBeforeTrustingAnything() {
        assertThatThrownBy(() -> RedisSnapshotManifest.beginPublication(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("sourceFence");
        assertThatThrownBy(() -> RedisSnapshotManifest.publication(null, content(1)))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("sourceFence");
        assertThatThrownBy(() -> RedisSnapshotManifest.publication(fence(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("content");

        RedisSnapshotManifest.Verified publication = publication();
        RedisSnapshotManifest.Unverified candidate = codec.decode(codec.encode(publication));
        assertThatThrownBy(() -> RedisSnapshotManifest.verifyForRequestedSnapshot(
            null,
            fence(),
            publication.snapshotId(),
            content(1)
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("candidate");
        assertThatThrownBy(() -> RedisSnapshotManifest.verifyForRequestedSnapshot(
            candidate,
            null,
            publication.snapshotId(),
            content(1)
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("expectedSourceFence");
        assertThatThrownBy(() -> RedisSnapshotManifest.verifyForRequestedSnapshot(
            candidate,
            fence(),
            publication.snapshotId(),
            null
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("recomputedContent");
    }

    private RedisSnapshotManifest.Verified publication() {
        return RedisSnapshotManifest.publication(fence(), content(1));
    }

    private Map<String, String> mutableFields(RedisSnapshotManifest.Verified manifest) {
        return new HashMap<>(codec.encode(manifest));
    }

    private static void assertVerification(
        RedisSnapshotManifestVerificationException exception,
        RedisSnapshotManifestVerificationException.Reason reason
    ) {
        assertThat(exception.reason()).isEqualTo(reason);
        assertThat(exception.getMessage())
            .isEqualTo(RedisSnapshotManifestVerificationException.DIAGNOSTIC_CODE
                + ":" + reason.name());
    }

    private static RedisSnapshotManifestVerificationException expectVerification(
        Runnable action
    ) {
        try {
            action.run();
        } catch (RedisSnapshotManifestVerificationException exception) {
            return exception;
        }
        throw new AssertionError("expected RedisSnapshotManifestVerificationException");
    }

    private static Mismatch mismatch(
        String field,
        String value,
        RedisSnapshotManifestVerificationException.Reason reason
    ) {
        return new Mismatch(field, value, reason);
    }

    private static SecurityDatasetFence fence() {
        return new SecurityDatasetFence(
            new SecurityDatasetIdentity("tenant-a", 1),
            7
        );
    }

    private static RedisSnapshotContentDigest content(int digestSalt) {
        return new RedisSnapshotContentDigest(
            family(RedisSnapshotFamilyCode.PERSONS, 1, 100, digestSalt),
            family(RedisSnapshotFamilyCode.ORGANIZATIONS, 2, 200, digestSalt + 1),
            family(RedisSnapshotFamilyCode.PARTY_ROLES, 3, 300, digestSalt + 2),
            family(RedisSnapshotFamilyCode.POSITION_ROLES, 4, 400, digestSalt + 3),
            family(RedisSnapshotFamilyCode.ROLES, 5, 500, digestSalt + 4),
            family(RedisSnapshotFamilyCode.PRIVILEGES, 6, 600, digestSalt + 5)
        );
    }

    private static RedisSnapshotContentDigest emptyContent() {
        return new RedisSnapshotContentDigest(
            family(RedisSnapshotFamilyCode.PERSONS, 0, 0, 1),
            family(RedisSnapshotFamilyCode.ORGANIZATIONS, 0, 0, 2),
            family(RedisSnapshotFamilyCode.PARTY_ROLES, 0, 0, 3),
            family(RedisSnapshotFamilyCode.POSITION_ROLES, 0, 0, 4),
            family(RedisSnapshotFamilyCode.ROLES, 0, 0, 5),
            family(RedisSnapshotFamilyCode.PRIVILEGES, 0, 0, 6)
        );
    }

    private static RedisSnapshotFamilyDigest family(
        RedisSnapshotFamilyCode family,
        long count,
        long bytes,
        int digestByte
    ) {
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) digestByte);
        return new RedisSnapshotFamilyDigest(family, count, bytes, digest);
    }

    private record Mismatch(
        String field,
        String value,
        RedisSnapshotManifestVerificationException.Reason reason
    ) {
    }
}
