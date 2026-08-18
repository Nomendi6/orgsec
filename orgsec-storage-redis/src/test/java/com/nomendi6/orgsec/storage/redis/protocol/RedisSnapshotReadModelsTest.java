package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSnapshotReadModelsTest {

    private static final String RUN_ID_A = "0123456789abcdef0123456789abcdef01234567";
    private static final String RUN_ID_B = "89abcdef0123456789abcdef0123456789abcdef";
    private static final UUID STORAGE_UUID =
        UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SNAPSHOT_ID_A =
        UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SNAPSHOT_ID_B =
        UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final RedisSnapshotManifestCodec manifestCodec = new RedisSnapshotManifestCodec();

    @Test
    void generationRetainsTheExactReadyPrimaryObservationAndEnvelope() {
        SecurityDatasetIdentity identity = identityA();
        RedisPrimaryObservation observation = observation(RUN_ID_A);
        RedisControlEnvelope envelope = readyEnvelope(
            identity,
            RUN_ID_A,
            STORAGE_UUID,
            7,
            SNAPSHOT_ID_A
        );

        RedisSnapshotGeneration generation = RedisSnapshotGeneration.from(
            new RedisPrimarySnapshot(observation, envelope),
            identity
        );

        assertThat(generation.observation()).isSameAs(observation);
        assertThat(generation.controlEnvelope()).isSameAs(envelope);
        assertThat(generation.identity()).isSameAs(identity);
        assertThat(generation.incarnation()).isSameAs(envelope.getIncarnation());
        assertThat(generation.counter()).isEqualTo(7);
        assertThat(generation.activeSnapshotId()).isEqualTo(SNAPSHOT_ID_A);
        assertThat(Modifier.isFinal(RedisSnapshotGeneration.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(RedisSnapshotGeneration.class.getModifiers())).isFalse();
    }

    @Test
    void generationRejectsMissingControlAndEveryNonReadyState() {
        RedisPrimaryObservation observation = observation(RUN_ID_A);

        assertThatThrownBy(() -> RedisSnapshotGeneration.from(
            new RedisPrimarySnapshot(observation, null),
            identityA()
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("control envelope");

        for (RedisControlState state : List.of(
            RedisControlState.INITIALIZING,
            RedisControlState.UPDATING
        )) {
            RedisControlEnvelope envelope = new RedisControlEnvelope(
                identityA(),
                incarnation(RUN_ID_A, STORAGE_UUID),
                7,
                state == RedisControlState.UPDATING ? SNAPSHOT_ID_A : null,
                state
            );

            assertThatThrownBy(() -> RedisSnapshotGeneration.from(
                new RedisPrimarySnapshot(observation, envelope),
                identityA()
            )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
        }
    }

    @Test
    void generationRejectsExpectedIdentityMismatchEvenWhenVersionsMatch() {
        RedisControlEnvelope envelope = readyEnvelope(
            identityA(),
            RUN_ID_A,
            STORAGE_UUID,
            7,
            SNAPSHOT_ID_A
        );

        assertThatThrownBy(() -> RedisSnapshotGeneration.from(
            new RedisPrimarySnapshot(observation(RUN_ID_A), envelope),
            identityB()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly match");
    }

    @Test
    void generationRejectsFutureExpectedProtocolBeforeInspectingSnapshotState() {
        SecurityDatasetIdentity futureIdentity = new SecurityDatasetIdentity("sensitive-dataset", 2);
        RedisPrimarySnapshot missingControl = new RedisPrimarySnapshot(
            observation(RUN_ID_A),
            null
        );

        assertThat(futureIdentity.getProtocolVersion()).isEqualTo(2);
        assertThatThrownBy(() -> RedisSnapshotGeneration.from(
            missingControl,
            futureIdentity
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining("2");
    }

    @Test
    void generationRejectsObservationAndEnvelopeRunIdMismatch() {
        RedisControlEnvelope envelope = readyEnvelope(
            identityA(),
            RUN_ID_A,
            STORAGE_UUID,
            7,
            SNAPSHOT_ID_A
        );

        assertThatThrownBy(() -> RedisSnapshotGeneration.from(
            new RedisPrimarySnapshot(observation(RUN_ID_B), envelope),
            identityA()
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("run ID");
    }

    @Test
    void equalGenerationIsAValueButSameCounterWithNewSnapshotOrRunIdIsDifferent() {
        RedisSnapshotGeneration baseline = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 9, SNAPSHOT_ID_A
        );
        RedisSnapshotGeneration equal = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 9, SNAPSHOT_ID_A
        );
        RedisSnapshotGeneration newSnapshot = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 9, SNAPSHOT_ID_B
        );
        RedisSnapshotGeneration newPrimary = generation(
            identityA(), RUN_ID_B, STORAGE_UUID, 9, SNAPSHOT_ID_A
        );

        assertThat(baseline).isEqualTo(equal).hasSameHashCodeAs(equal);
        assertThat(baseline.equals(baseline)).isTrue();
        assertThat(baseline).isNotEqualTo(newSnapshot).isNotEqualTo(newPrimary);
        assertThat(baseline).isNotEqualTo(null).isNotEqualTo(identityA());
    }

    @Test
    void generationDiagnosticsIdentifyTheToken() {
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 9, SNAPSHOT_ID_A
        );

        assertThat(generation.toString())
            .contains(
                "datasetId='tenant-a'",
                "primaryRunId='" + RUN_ID_A + "'",
                "counter=9",
                SNAPSHOT_ID_A.toString()
            );
    }

    @Test
    void readHandleRetainsCanonicalDefensiveWireCopyAndExactImmutableBindings() {
        SecurityDatasetFence expectedFence = fenceA();
        RedisSnapshotManifest.Verified publication = RedisSnapshotManifest.publication(
            expectedFence,
            content()
        );
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 3, publication.snapshotId()
        );
        Map<String, String> encoded = manifestCodec.encode(publication);
        List<String> reverseOrder = new ArrayList<>(RedisSnapshotManifestCodec.orderedFields());
        java.util.Collections.reverse(reverseOrder);
        Map<String, String> supplied = new LinkedHashMap<>();
        for (String field : reverseOrder) {
            supplied.put(field, encoded.get(field));
        }
        RedisSnapshotManifest.Unverified candidate = manifestCodec.decode(encoded);

        RedisSnapshotReadHandle handle = new RedisSnapshotReadHandle(
            generation,
            expectedFence,
            publication.snapshotId(),
            supplied,
            candidate
        );
        supplied.clear();

        assertThat(handle.generation()).isSameAs(generation);
        assertThat(handle.expectedSourceFence()).isSameAs(expectedFence);
        assertThat(handle.requestedSnapshotId()).isEqualTo(publication.snapshotId());
        assertThat(handle.manifestCandidate()).isSameAs(candidate);
        assertThat(handle.manifestWireFields()).isEqualTo(encoded);
        assertThat(handle.manifestWireFields().keySet())
            .containsExactlyElementsOf(RedisSnapshotManifestCodec.orderedFields());
        assertThatThrownBy(() -> handle.manifestWireFields().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(Modifier.isFinal(RedisSnapshotReadHandle.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(RedisSnapshotReadHandle.class.getModifiers())).isFalse();
    }

    @Test
    void readHandleRejectsWrongGenerationSnapshotOrExpectedFenceIdentity() {
        SecurityDatasetFence expectedFence = fenceA();
        RedisSnapshotManifest.Verified publication = RedisSnapshotManifest.publication(
            expectedFence,
            content()
        );
        Map<String, String> fields = manifestCodec.encode(publication);
        RedisSnapshotManifest.Unverified candidate = manifestCodec.decode(fields);
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 3, publication.snapshotId()
        );

        assertThatThrownBy(() -> new RedisSnapshotReadHandle(
            generation,
            expectedFence,
            SNAPSHOT_ID_B,
            fields,
            candidate
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("active snapshot");

        assertThatThrownBy(() -> new RedisSnapshotReadHandle(
            generation,
            new SecurityDatasetFence(identityB(), expectedFence.getSecurityContentVersion()),
            publication.snapshotId(),
            fields,
            candidate
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("generation identity");
    }

    @Test
    void readHandleRequiresExactlyTheCanonicalWirePairs() {
        SecurityDatasetFence expectedFence = fenceA();
        RedisSnapshotManifest.Verified publication = RedisSnapshotManifest.publication(
            expectedFence,
            content()
        );
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 3, publication.snapshotId()
        );
        Map<String, String> fields = new LinkedHashMap<>(manifestCodec.encode(publication));
        RedisSnapshotManifest.Unverified candidate = manifestCodec.decode(fields);

        Map<String, String> missing = new LinkedHashMap<>(fields);
        missing.remove(RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT);
        assertThatThrownBy(() -> new RedisSnapshotReadHandle(
            generation, expectedFence, publication.snapshotId(), missing, candidate
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "exactly " + RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT
            );

        Map<String, String> unknownInstead = new LinkedHashMap<>(fields);
        unknownInstead.remove(RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT);
        unknownInstead.put("unknown", "0");
        assertThatThrownBy(() -> new RedisSnapshotReadHandle(
            generation, expectedFence, publication.snapshotId(), unknownInstead, candidate
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("canonical manifest fields");
    }

    @Test
    void readHandleRejectsCandidateThatDoesNotMatchItsRetainedWirePairs() {
        SecurityDatasetFence expectedFence = fenceA();
        RedisSnapshotManifest.Verified first = RedisSnapshotManifest.publication(
            expectedFence,
            content()
        );
        RedisSnapshotManifest.Verified second = RedisSnapshotManifest.publication(
            expectedFence,
            content()
        );
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 3, first.snapshotId()
        );

        assertThatThrownBy(() -> new RedisSnapshotReadHandle(
            generation,
            expectedFence,
            first.snapshotId(),
            manifestCodec.encode(first),
            manifestCodec.decode(manifestCodec.encode(second))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("wire fields");
    }

    @Test
    void readHandleDoesNotPromoteAnEmbeddedSnapshotMismatchToTrustedState() {
        SecurityDatasetFence expectedFence = fenceA();
        RedisSnapshotManifest.Verified selected = RedisSnapshotManifest.publication(
            expectedFence,
            content()
        );
        RedisSnapshotManifest.Verified attackerCandidate = RedisSnapshotManifest.publication(
            expectedFence,
            content()
        );
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 3, selected.snapshotId()
        );
        Map<String, String> attackerFields = manifestCodec.encode(attackerCandidate);
        RedisSnapshotManifest.Unverified unverified = manifestCodec.decode(attackerFields);

        RedisSnapshotReadHandle handle = new RedisSnapshotReadHandle(
            generation,
            expectedFence,
            selected.snapshotId(),
            attackerFields,
            unverified
        );

        assertThat(handle.requestedSnapshotId()).isEqualTo(selected.snapshotId());
        assertThat(handle.manifestCandidate().snapshotId())
            .isEqualTo(attackerCandidate.snapshotId())
            .isNotEqualTo(handle.requestedSnapshotId());
        assertThatThrownBy(() -> RedisSnapshotManifest.verifyForRequestedSnapshot(
            handle.manifestCandidate(),
            handle.expectedSourceFence(),
            handle.requestedSnapshotId(),
            content()
        )).isInstanceOf(RedisSnapshotManifestVerificationException.class);
    }

    @Test
    void pageRetainsExplicitFamilyOffsetsAndDefensiveEntryCopy() {
        RedisCanonicalEntry first = entry("1", "one");
        RedisCanonicalEntry second = entry("2", "two");
        List<RedisCanonicalEntry> supplied = new ArrayList<>(List.of(first, second));

        RedisSnapshotPage page = new RedisSnapshotPage(
            RedisSnapshotFamily.PERSONS,
            4,
            6,
            supplied,
            false
        );
        supplied.clear();

        assertThat(page.family()).isEqualTo(RedisSnapshotFamily.PERSONS);
        assertThat(page.offset()).isEqualTo(4);
        assertThat(page.nextOffset()).isEqualTo(6);
        assertThat(page.entries()).containsExactly(first, second);
        assertThat(page.done()).isFalse();
        assertThatThrownBy(() -> page.entries().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(Modifier.isFinal(RedisSnapshotPage.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(RedisSnapshotPage.class.getModifiers())).isFalse();
    }

    @Test
    void pageEnforcesFamilyOffsetProgressAndDoneInvariants() {
        RedisCanonicalEntry entry = entry("1", "one");

        assertThatThrownBy(() -> new RedisSnapshotPage(null, 0, 1, List.of(entry), true))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("family");
        assertThatThrownBy(() -> new RedisSnapshotPage(
            RedisSnapshotFamily.PERSONS, -1, 0, List.of(entry), true
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("offset");
        assertThatThrownBy(() -> new RedisSnapshotPage(
            RedisSnapshotFamily.PERSONS, 0, -1, List.of(), true
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nextOffset");
        assertThatThrownBy(() -> new RedisSnapshotPage(
            RedisSnapshotFamily.PERSONS, 4, 4, List.of(entry), true
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("number of entries");
        assertThatThrownBy(() -> new RedisSnapshotPage(
            RedisSnapshotFamily.PERSONS, 4, 4, List.of(), false
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty page");
        assertThatThrownBy(() -> new RedisSnapshotPage(
            RedisSnapshotFamily.PERSONS,
            Long.MAX_VALUE,
            Long.MAX_VALUE,
            List.of(entry),
            true
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overflow");

        RedisSnapshotPage finalNonEmpty = new RedisSnapshotPage(
            RedisSnapshotFamily.PRIVILEGES, 8, 9, List.of(entry), true
        );
        RedisSnapshotPage finalEmpty = new RedisSnapshotPage(
            RedisSnapshotFamily.PRIVILEGES, 9, 9, List.of(), true
        );
        assertThat(finalNonEmpty.done()).isTrue();
        assertThat(finalEmpty.done()).isTrue();
    }

    @Test
    void verifiedViewAdoptsOnlyAfterExactFinalBindingsAndContextualVerification() {
        SecurityDatasetFence fence = fenceA();
        RedisSnapshotManifest.Verified publication = RedisSnapshotManifest.publication(
            fence,
            content()
        );
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 5, publication.snapshotId()
        );
        Map<String, String> fields = manifestCodec.encode(publication);
        RedisSnapshotReadHandle handle = new RedisSnapshotReadHandle(
            generation,
            fence,
            publication.snapshotId(),
            fields,
            manifestCodec.decode(fields)
        );

        RedisVerifiedSnapshotView view = RedisVerifiedSnapshotView.adopt(
            handle,
            generation,
            fields,
            content()
        );

        assertThat(view.generation()).isSameAs(generation);
        assertThat(view.manifest().sourceFence()).isSameAs(fence);
        assertThat(view.manifest().contentDigest()).isEqualTo(publication.contentDigest());
        assertThat(view.snapshotId()).isEqualTo(generation.activeSnapshotId());
        assertThat(Modifier.isFinal(RedisVerifiedSnapshotView.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(RedisVerifiedSnapshotView.class.getModifiers())).isFalse();
    }

    @Test
    void verifiedViewDoesNotExposeAReadyMadePublicationBypass() {
        assertThat(Arrays.stream(RedisVerifiedSnapshotView.class.getDeclaredConstructors()))
            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
        assertThat(Arrays.stream(RedisVerifiedSnapshotView.class.getDeclaredMethods())
            .filter(method -> method.getName().equals("adopt"))
            .flatMap(method -> Arrays.stream(method.getParameterTypes())))
            .doesNotContain(RedisSnapshotManifest.Verified.class);
    }

    @Test
    void verifiedViewRejectsSecurityContentVersionMismatch() {
        SecurityDatasetFence expectedFence = fenceA();
        SecurityDatasetFence wrongFence = new SecurityDatasetFence(identityA(), 12);
        RedisSnapshotManifest.Verified publication = RedisSnapshotManifest.publication(
            wrongFence,
            content()
        );
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 5, publication.snapshotId()
        );
        Map<String, String> fields = manifestCodec.encode(publication);
        RedisSnapshotReadHandle handle = new RedisSnapshotReadHandle(
            generation,
            expectedFence,
            publication.snapshotId(),
            fields,
            manifestCodec.decode(fields)
        );

        assertThatThrownBy(() -> RedisVerifiedSnapshotView.adopt(
            handle,
            generation,
            fields,
            content()
        )).isInstanceOf(RedisSnapshotManifestVerificationException.class);
    }

    @Test
    void verifiedViewRejectsChangedFinalGeneration() {
        SecurityDatasetFence fence = fenceA();
        RedisSnapshotManifest.Verified publication = RedisSnapshotManifest.publication(
            fence,
            content()
        );
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 5, publication.snapshotId()
        );
        RedisSnapshotGeneration changedGeneration = generation(
            identityA(), RUN_ID_B, STORAGE_UUID, 5, publication.snapshotId()
        );
        Map<String, String> fields = manifestCodec.encode(publication);
        RedisSnapshotReadHandle handle = new RedisSnapshotReadHandle(
            generation,
            fence,
            publication.snapshotId(),
            fields,
            manifestCodec.decode(fields)
        );

        assertThatThrownBy(() -> RedisVerifiedSnapshotView.adopt(
            handle,
            changedGeneration,
            fields,
            content()
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("generation");
    }

    @Test
    void verifiedViewRejectsChangedFinalManifestWireFields() {
        SecurityDatasetFence fence = fenceA();
        RedisSnapshotManifest.Verified publication = RedisSnapshotManifest.publication(
            fence,
            content()
        );
        RedisSnapshotGeneration generation = generation(
            identityA(), RUN_ID_A, STORAGE_UUID, 5, publication.snapshotId()
        );
        Map<String, String> fields = manifestCodec.encode(publication);
        RedisSnapshotReadHandle handle = new RedisSnapshotReadHandle(
            generation,
            fence,
            publication.snapshotId(),
            fields,
            manifestCodec.decode(fields)
        );
        Map<String, String> changedFields = new LinkedHashMap<>(fields);
        changedFields.put(RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT, "2");

        assertThatThrownBy(() -> RedisVerifiedSnapshotView.adopt(
            handle,
            generation,
            changedFields,
            content()
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("manifest");
    }

    private static RedisSnapshotGeneration generation(
        SecurityDatasetIdentity identity,
        String runId,
        UUID storageUuid,
        long counter,
        UUID snapshotId
    ) {
        RedisControlEnvelope envelope = readyEnvelope(
            identity,
            runId,
            storageUuid,
            counter,
            snapshotId
        );
        return RedisSnapshotGeneration.from(
            new RedisPrimarySnapshot(observation(runId), envelope),
            identity
        );
    }

    private static RedisControlEnvelope readyEnvelope(
        SecurityDatasetIdentity identity,
        String runId,
        UUID storageUuid,
        long counter,
        UUID snapshotId
    ) {
        return new RedisControlEnvelope(
            identity,
            incarnation(runId, storageUuid),
            counter,
            snapshotId,
            RedisControlState.READY
        );
    }

    private static RedisPrimaryObservation observation(String runId) {
        return new RedisPrimaryObservation(runId, "master", false, "noeviction");
    }

    private static RedisIncarnation incarnation(String runId, UUID storageUuid) {
        return new RedisIncarnation(runId, storageUuid);
    }

    private static SecurityDatasetIdentity identityA() {
        return new SecurityDatasetIdentity("tenant-a", 1);
    }

    private static SecurityDatasetIdentity identityB() {
        return new SecurityDatasetIdentity("tenant-b", 1);
    }

    private static SecurityDatasetFence fenceA() {
        return new SecurityDatasetFence(identityA(), 11);
    }

    private static RedisSnapshotContentDigest content() {
        return new RedisSnapshotContentDigest(
            family(RedisSnapshotFamilyCode.PERSONS, 1),
            family(RedisSnapshotFamilyCode.ORGANIZATIONS, 2),
            family(RedisSnapshotFamilyCode.PARTY_ROLES, 3),
            family(RedisSnapshotFamilyCode.POSITION_ROLES, 4),
            family(RedisSnapshotFamilyCode.ROLES, 5),
            family(RedisSnapshotFamilyCode.PRIVILEGES, 6)
        );
    }

    private static RedisSnapshotFamilyDigest family(
        RedisSnapshotFamilyCode family,
        int salt
    ) {
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) salt);
        return new RedisSnapshotFamilyDigest(family, salt, salt * 10L, digest);
    }

    private static RedisCanonicalEntry entry(String key, String payload) {
        return new RedisCanonicalEntry(
            key.getBytes(StandardCharsets.UTF_8),
            payload.getBytes(StandardCharsets.UTF_8)
        );
    }
}
