package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisSnapshotManifestCodecTest {

    private static final String SHA_A = "abcdef0123456789".repeat(4);
    private static final UUID NON_V4_SNAPSHOT_ID =
        UUID.fromString("aaaaaaaa-aaaa-1aaa-8aaa-aaaaaaaaaaaa");

    private static final List<String> NON_NEGATIVE_LONG_FIELDS = List.of(
        RedisSnapshotManifestCodec.FIELD_SECURITY_CONTENT_VERSION,
        RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT,
        RedisSnapshotManifestCodec.FIELD_ORGANIZATIONS_COUNT,
        RedisSnapshotManifestCodec.FIELD_PARTY_ROLES_COUNT,
        RedisSnapshotManifestCodec.FIELD_POSITION_ROLES_COUNT,
        RedisSnapshotManifestCodec.FIELD_ROLES_COUNT,
        RedisSnapshotManifestCodec.FIELD_PRIVILEGES_COUNT,
        RedisSnapshotManifestCodec.FIELD_ACCOUNTED_BYTES
    );

    private final RedisSnapshotManifestCodec codec = new RedisSnapshotManifestCodec();

    @Test
    void encodesOnlyVerifiedValuesAsTheExactImmutableTwelveFieldMap() throws Exception {
        RedisSnapshotContentDigest content = content();
        RedisSnapshotManifest.Verified manifest = RedisSnapshotManifest.publication(
            fence(),
            content
        );

        Map<String, String> encoded = codec.encode(manifest);

        assertThat(encoded).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
            Map.entry(RedisSnapshotManifestCodec.FIELD_DATASET_ID, "tenant-a"),
            Map.entry(RedisSnapshotManifestCodec.FIELD_PROTOCOL_VERSION, "1"),
            Map.entry(RedisSnapshotManifestCodec.FIELD_SECURITY_CONTENT_VERSION, "7"),
            Map.entry(
                RedisSnapshotManifestCodec.FIELD_SNAPSHOT_ID,
                manifest.snapshotId().toString()
            ),
            Map.entry(
                RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST,
                HexFormat.of().formatHex(content.digest())
            ),
            Map.entry(RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT, "1"),
            Map.entry(RedisSnapshotManifestCodec.FIELD_ORGANIZATIONS_COUNT, "2"),
            Map.entry(RedisSnapshotManifestCodec.FIELD_PARTY_ROLES_COUNT, "3"),
            Map.entry(RedisSnapshotManifestCodec.FIELD_POSITION_ROLES_COUNT, "4"),
            Map.entry(RedisSnapshotManifestCodec.FIELD_ROLES_COUNT, "5"),
            Map.entry(RedisSnapshotManifestCodec.FIELD_PRIVILEGES_COUNT, "6"),
            Map.entry(RedisSnapshotManifestCodec.FIELD_ACCOUNTED_BYTES, "2100")
        ));
        assertThat(encoded).hasSize(12);
        assertThat(RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT).isEqualTo(12);
        assertThatThrownBy(() -> encoded.put("unknown", "value"))
            .isInstanceOf(UnsupportedOperationException.class);

        Method encode = RedisSnapshotManifestCodec.class.getDeclaredMethod(
            "encode",
            RedisSnapshotManifest.Verified.class
        );
        assertThat(encode.getParameterTypes())
            .containsExactly(RedisSnapshotManifest.Verified.class)
            .doesNotContain(RedisSnapshotManifest.Unverified.class);
    }

    @Test
    void exposesExactOrderedFieldsAndValueByteLimitsForBoundedTransport() {
        List<String> expectedOrder = List.of(
            RedisSnapshotManifestCodec.FIELD_DATASET_ID,
            RedisSnapshotManifestCodec.FIELD_PROTOCOL_VERSION,
            RedisSnapshotManifestCodec.FIELD_SECURITY_CONTENT_VERSION,
            RedisSnapshotManifestCodec.FIELD_SNAPSHOT_ID,
            RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST,
            RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT,
            RedisSnapshotManifestCodec.FIELD_ORGANIZATIONS_COUNT,
            RedisSnapshotManifestCodec.FIELD_PARTY_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_POSITION_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_PRIVILEGES_COUNT,
            RedisSnapshotManifestCodec.FIELD_ACCOUNTED_BYTES
        );
        Map<String, Integer> expectedLimits = Map.ofEntries(
            Map.entry(RedisSnapshotManifestCodec.FIELD_DATASET_ID, 256),
            Map.entry(RedisSnapshotManifestCodec.FIELD_PROTOCOL_VERSION, 10),
            Map.entry(RedisSnapshotManifestCodec.FIELD_SECURITY_CONTENT_VERSION, 19),
            Map.entry(RedisSnapshotManifestCodec.FIELD_SNAPSHOT_ID, 36),
            Map.entry(RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST, 64),
            Map.entry(RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT, 19),
            Map.entry(RedisSnapshotManifestCodec.FIELD_ORGANIZATIONS_COUNT, 19),
            Map.entry(RedisSnapshotManifestCodec.FIELD_PARTY_ROLES_COUNT, 19),
            Map.entry(RedisSnapshotManifestCodec.FIELD_POSITION_ROLES_COUNT, 19),
            Map.entry(RedisSnapshotManifestCodec.FIELD_ROLES_COUNT, 19),
            Map.entry(RedisSnapshotManifestCodec.FIELD_PRIVILEGES_COUNT, 19),
            Map.entry(RedisSnapshotManifestCodec.FIELD_ACCOUNTED_BYTES, 19)
        );

        assertThat(RedisSnapshotManifestCodec.orderedFields()).containsExactlyElementsOf(
            expectedOrder
        );
        for (String field : expectedOrder) {
            assertThat(RedisSnapshotManifestCodec.maxUtf8Bytes(field))
                .as(field)
                .isEqualTo(expectedLimits.get(field));
        }
        assertThatThrownBy(() -> RedisSnapshotManifestCodec.orderedFields().add("future"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> RedisSnapshotManifestCodec.maxUtf8Bytes("attacker-field"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("field must be a known manifest field")
            .hasMessageNotContaining("attacker-field");

        Map<String, String> encoded = codec.encode(publication());
        assertThat(new ArrayList<>(encoded.keySet())).containsExactlyElementsOf(expectedOrder);
        for (String field : expectedOrder) {
            assertThat(encoded.get(field).getBytes(StandardCharsets.UTF_8).length)
                .as(field)
                .isLessThanOrEqualTo(RedisSnapshotManifestCodec.maxUtf8Bytes(field));
        }
    }

    @Test
    void acceptsExactly256Utf8DatasetBytesAndRejectsTheNextMultibyteCharacter() {
        String maximum = "ž".repeat(128);
        String tooLarge = maximum + "ž";
        RedisSnapshotManifest.Verified maximumManifest = RedisSnapshotManifest.publication(
            fence(maximum),
            content()
        );

        Map<String, String> encoded = codec.encode(maximumManifest);
        assertThat(encoded.get(RedisSnapshotManifestCodec.FIELD_DATASET_ID))
            .isEqualTo(maximum);
        assertThat(RedisSnapshotManifestCodec.encodeBoundedUtf8Value(
            RedisSnapshotManifestCodec.FIELD_DATASET_ID,
            maximum
        )).hasSize(256);
        assertThat(RedisSnapshotManifestCodec.decodeBoundedUtf8Value(
            RedisSnapshotManifestCodec.FIELD_DATASET_ID,
            maximum.getBytes(StandardCharsets.UTF_8)
        )).isEqualTo(maximum);
        assertThat(codec.decode(encoded).sourceFence().getIdentity().getSecurityDatasetId())
            .isEqualTo(maximum);

        RedisSnapshotManifestEncodingException encodeFailure = expectEncoding(() ->
            codec.encode(RedisSnapshotManifest.publication(fence(tooLarge), content()))
        );
        assertEncoding(
            encodeFailure,
            RedisSnapshotManifestEncodingException.Reason.FIELD_VALUE_TOO_LARGE
        );

        Map<String, String> oversizedWire = mutableCanonical();
        oversizedWire.put(RedisSnapshotManifestCodec.FIELD_DATASET_ID, tooLarge);
        assertCorruption(
            expectCorruption(() -> codec.decode(oversizedWire)),
            RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_TOO_LARGE
        );
    }

    @Test
    void strictUtf8HelpersRejectUnpairedSurrogatesAndMalformedWireBytes() {
        String unpaired = "tenant-\ud800";
        RedisSnapshotManifestEncodingException helperEncodeFailure = expectEncoding(() ->
            RedisSnapshotManifestCodec.encodeBoundedUtf8Value(
                RedisSnapshotManifestCodec.FIELD_DATASET_ID,
                unpaired
            )
        );
        assertEncoding(
            helperEncodeFailure,
            RedisSnapshotManifestEncodingException.Reason.FIELD_UTF8_INVALID
        );

        RedisSnapshotManifestEncodingException manifestEncodeFailure = expectEncoding(() ->
            codec.encode(RedisSnapshotManifest.publication(fence(unpaired), content()))
        );
        assertEncoding(
            manifestEncodeFailure,
            RedisSnapshotManifestEncodingException.Reason.FIELD_UTF8_INVALID
        );

        Map<String, String> unpairedWire = mutableCanonical();
        unpairedWire.put(RedisSnapshotManifestCodec.FIELD_DATASET_ID, unpaired);
        assertCorruption(
            expectCorruption(() -> codec.decode(unpairedWire)),
            RedisSnapshotManifestCorruptionException.Reason.FIELD_UTF8_INVALID
        );
        assertCorruption(
            expectCorruption(() -> RedisSnapshotManifestCodec.decodeBoundedUtf8Value(
                RedisSnapshotManifestCodec.FIELD_DATASET_ID,
                new byte[]{(byte) 0xc3, 0x28}
            )),
            RedisSnapshotManifestCorruptionException.Reason.FIELD_UTF8_INVALID
        );
        assertCorruption(
            expectCorruption(() -> RedisSnapshotManifestCodec.decodeBoundedUtf8Value(
                RedisSnapshotManifestCodec.FIELD_DATASET_ID,
                new byte[257]
            )),
            RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_TOO_LARGE
        );
    }

    @Test
    void roundTripRemainsUnverifiedUntilExactContextIsSupplied() {
        SecurityDatasetFence fence = fence();
        RedisSnapshotContentDigest content = content();
        RedisSnapshotManifest.Verified published = RedisSnapshotManifest.publication(
            fence,
            content
        );

        RedisSnapshotManifest.Unverified decoded = codec.decode(codec.encode(published));

        assertThat(decoded.sourceFence()).isEqualTo(fence);
        assertThat(decoded.snapshotId()).isEqualTo(published.snapshotId());
        assertThat(decoded.contentDigest()).isEqualTo(published.contentDigest());
        assertThat(decoded.personsCount()).isEqualTo(1);
        assertThat(decoded.organizationsCount()).isEqualTo(2);
        assertThat(decoded.partyRolesCount()).isEqualTo(3);
        assertThat(decoded.positionRolesCount()).isEqualTo(4);
        assertThat(decoded.rolesCount()).isEqualTo(5);
        assertThat(decoded.privilegesCount()).isEqualTo(6);
        assertThat(decoded.accountedBytes()).isEqualTo(2100);

        RedisSnapshotManifest.Verified verified =
            RedisSnapshotManifest.verifyForRequestedSnapshot(
                decoded,
                fence,
                published.snapshotId(),
                content
            );
        assertThat(verified).isEqualTo(published);
    }

    @Test
    void preservesExactUnicodeDatasetIdentityWithoutNormalizingIt() {
        SecurityDatasetFence unicodeFence = new SecurityDatasetFence(
            new SecurityDatasetIdentity(" tenant-ž ", 1),
            7
        );
        RedisSnapshotManifest.Verified published = RedisSnapshotManifest.publication(
            unicodeFence,
            content()
        );

        RedisSnapshotManifest.Unverified decoded = codec.decode(codec.encode(published));

        assertThat(decoded.sourceFence().getIdentity().getSecurityDatasetId())
            .isEqualTo(" tenant-ž ");
    }

    @Test
    void missingAndUnknownFieldDiagnosticsAreDeterministicAndSanitized() {
        Map<String, String> missingDataset = mutableCanonical();
        missingDataset.remove(RedisSnapshotManifestCodec.FIELD_DATASET_ID);
        Map<String, String> missingDigest = mutableCanonical();
        missingDigest.remove(RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST);

        RedisSnapshotManifestCorruptionException first = expectCorruption(
            () -> codec.decode(missingDataset)
        );
        RedisSnapshotManifestCorruptionException second = expectCorruption(
            () -> codec.decode(missingDigest)
        );
        assertCorruption(
            first,
            RedisSnapshotManifestCorruptionException.Reason.FIELD_SET_MISMATCH
        );
        assertThat(second.getMessage()).isEqualTo(first.getMessage());

        Map<String, String> unknownOne = mutableCanonical();
        unknownOne.remove(RedisSnapshotManifestCodec.FIELD_DATASET_ID);
        unknownOne.put("attacker-secret-field-one", "attacker-secret-value-one");
        Map<String, String> unknownTwo = mutableCanonical();
        unknownTwo.remove(RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST);
        unknownTwo.put("attacker-secret-field-two", "attacker-secret-value-two");

        RedisSnapshotManifestCorruptionException third = expectCorruption(
            () -> codec.decode(unknownOne)
        );
        RedisSnapshotManifestCorruptionException fourth = expectCorruption(
            () -> codec.decode(unknownTwo)
        );
        assertThat(third.getMessage()).isEqualTo(first.getMessage());
        assertThat(fourth.getMessage()).isEqualTo(first.getMessage());
        assertThat(third.getMessage())
            .doesNotContain("attacker", "secret", "field-one", "value-one");
    }

    @Test
    void rejectsNullMapAndNullOrEmptyKnownValuesWithoutEchoingWireData() {
        assertCorruption(
            expectCorruption(() -> codec.decode(null)),
            RedisSnapshotManifestCorruptionException.Reason.NULL_MAP
        );

        Map<String, String> nullValue = mutableCanonical();
        nullValue.put(RedisSnapshotManifestCodec.FIELD_DATASET_ID, null);
        assertCorruption(
            expectCorruption(() -> codec.decode(nullValue)),
            RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_EMPTY
        );

        Map<String, String> emptyValue = mutableCanonical();
        emptyValue.put(RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST, "");
        assertCorruption(
            expectCorruption(() -> codec.decode(emptyValue)),
            RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_EMPTY
        );
    }

    @Test
    void rejectsBlankDatasetAndNonCanonicalOrOverflowingProtocolVersions() {
        for (String value : List.of(" ", "\t\n")) {
            assertFieldCorrupt(
                RedisSnapshotManifestCodec.FIELD_DATASET_ID,
                value,
                RedisSnapshotManifestCorruptionException.Reason.DATASET_ID_INVALID
            );
        }
        for (String value : List.of(
            "0", "2", "01", "+1", "-1", "1.0", "2147483648"
        )) {
            assertFieldCorrupt(
                RedisSnapshotManifestCodec.FIELD_PROTOCOL_VERSION,
                value,
                RedisSnapshotManifestCorruptionException.Reason.PROTOCOL_VERSION_INVALID
            );
        }
    }

    @Test
    void rejectsNonCanonicalOrOverflowingNumbersAndDigests() {
        for (String field : NON_NEGATIVE_LONG_FIELDS) {
            for (String value : List.of("-1", "00", "+1", "1.0", "9223372036854775808")) {
                assertFieldCorrupt(
                    field,
                    value,
                    RedisSnapshotManifestCorruptionException.Reason.NUMBER_INVALID
                );
            }
        }

        for (String value : List.of(SHA_A.toUpperCase(), SHA_A.substring(1), "g".repeat(64))) {
            assertFieldCorrupt(
                RedisSnapshotManifestCodec.FIELD_CONTENT_DIGEST,
                value,
                RedisSnapshotManifestCorruptionException.Reason.DIGEST_INVALID
            );
        }
    }

    @Test
    void rejectsNonCanonicalNonV4AndNonRfc4122SnapshotIds() {
        String canonicalV4 = publication().snapshotId().toString();
        for (String value : List.of(
            canonicalV4.toUpperCase(),
            "1-1-1-1-1",
            "not-a-uuid",
            NON_V4_SNAPSHOT_ID.toString(),
            "bbbbbbbb-bbbb-4bbb-cbbb-bbbbbbbbbbbb"
        )) {
            assertFieldCorrupt(
                RedisSnapshotManifestCodec.FIELD_SNAPSHOT_ID,
                value,
                RedisSnapshotManifestCorruptionException.Reason.SNAPSHOT_ID_INVALID
            );
        }
    }

    @Test
    void syntacticallyValidOverflowingCountCombinationStillCannotBecomeTrusted() {
        RedisSnapshotManifest.Verified publication = publication();
        Map<String, String> fields = new HashMap<>(codec.encode(publication));
        fields.put(RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT, Long.toString(Long.MAX_VALUE));
        fields.put(RedisSnapshotManifestCodec.FIELD_ORGANIZATIONS_COUNT, "1");

        RedisSnapshotManifest.Unverified decoded = codec.decode(fields);
        assertThat(decoded.personsCount()).isEqualTo(Long.MAX_VALUE);

        assertThatThrownBy(() -> RedisSnapshotManifest.verifyForRequestedSnapshot(
            decoded,
            fence(),
            publication.snapshotId(),
            content()
        ))
            .isInstanceOf(RedisSnapshotManifestVerificationException.class)
            .satisfies(exception -> assertThat(
                ((RedisSnapshotManifestVerificationException) exception).reason()
            ).isEqualTo(
                RedisSnapshotManifestVerificationException.Reason.PERSONS_COUNT_MISMATCH
            ));
    }

    @Test
    void encodeRejectsNullAndCorruptionErrorsDropRawParserCauses() {
        assertThatThrownBy(() -> codec.encode(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verified");

        Map<String, String> overflow = mutableCanonical();
        overflow.put(RedisSnapshotManifestCodec.FIELD_ACCOUNTED_BYTES, "9223372036854775808");
        RedisSnapshotManifestCorruptionException exception = expectCorruption(
            () -> codec.decode(overflow)
        );
        assertCorruption(
            exception,
            RedisSnapshotManifestCorruptionException.Reason.NUMBER_INVALID
        );
        assertThat(exception.getCause()).isNull();
        assertThat(exception.getMessage()).doesNotContain("9223372036854775808");
    }

    @Test
    void encodeRejectsFutureProtocolWithASanitizedArgumentError() {
        SecurityDatasetFence futureFence = new SecurityDatasetFence(
            new SecurityDatasetIdentity("sensitive-dataset", 2),
            7
        );
        RedisSnapshotManifest.Verified futureManifest = mock(
            RedisSnapshotManifest.Verified.class
        );
        when(futureManifest.sourceFence()).thenReturn(futureFence);

        assertThatThrownBy(() -> codec.encode(futureManifest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(RedisWireProtocol.UNSUPPORTED_VERSION_MESSAGE)
            .hasMessageNotContaining("sensitive-dataset")
            .hasMessageNotContaining(SHA_A)
            .hasMessageNotContaining("2");
    }

    private void assertFieldCorrupt(
        String field,
        String value,
        RedisSnapshotManifestCorruptionException.Reason reason
    ) {
        Map<String, String> fields = mutableCanonical();
        fields.put(field, value);
        RedisSnapshotManifestCorruptionException exception = expectCorruption(
            () -> codec.decode(fields)
        );
        assertCorruption(exception, reason);
        assertThat(exception.getMessage()).doesNotContain(value);
    }

    private static void assertCorruption(
        RedisSnapshotManifestCorruptionException exception,
        RedisSnapshotManifestCorruptionException.Reason reason
    ) {
        assertThat(exception.reason()).isEqualTo(reason);
        assertThat(exception.getMessage())
            .isEqualTo(RedisSnapshotManifestCorruptionException.DIAGNOSTIC_CODE
                + ":" + reason.name());
    }

    private static void assertEncoding(
        RedisSnapshotManifestEncodingException exception,
        RedisSnapshotManifestEncodingException.Reason reason
    ) {
        assertThat(exception.reason()).isEqualTo(reason);
        assertThat(exception.getMessage())
            .isEqualTo(RedisSnapshotManifestEncodingException.DIAGNOSTIC_CODE
                + ":" + reason.name());
        assertThat(exception.getCause()).isNull();
    }

    private static RedisSnapshotManifestCorruptionException expectCorruption(Runnable action) {
        try {
            action.run();
        } catch (RedisSnapshotManifestCorruptionException exception) {
            return exception;
        }
        throw new AssertionError("expected RedisSnapshotManifestCorruptionException");
    }

    private static RedisSnapshotManifestEncodingException expectEncoding(Runnable action) {
        try {
            action.run();
        } catch (RedisSnapshotManifestEncodingException exception) {
            return exception;
        }
        throw new AssertionError("expected RedisSnapshotManifestEncodingException");
    }

    private Map<String, String> mutableCanonical() {
        return new HashMap<>(codec.encode(publication()));
    }

    private RedisSnapshotManifest.Verified publication() {
        return RedisSnapshotManifest.publication(fence(), content());
    }

    private static SecurityDatasetFence fence() {
        return fence("tenant-a");
    }

    private static SecurityDatasetFence fence(String datasetId) {
        return new SecurityDatasetFence(
            new SecurityDatasetIdentity(datasetId, 1),
            7
        );
    }

    private static RedisSnapshotContentDigest content() {
        return new RedisSnapshotContentDigest(
            family(RedisSnapshotFamilyCode.PERSONS, 1, 100, "persons"),
            family(RedisSnapshotFamilyCode.ORGANIZATIONS, 2, 200, "organizations"),
            family(RedisSnapshotFamilyCode.PARTY_ROLES, 3, 300, "party-roles"),
            family(RedisSnapshotFamilyCode.POSITION_ROLES, 4, 400, "position-roles"),
            family(RedisSnapshotFamilyCode.ROLES, 5, 500, "roles"),
            family(RedisSnapshotFamilyCode.PRIVILEGES, 6, 600, "privileges")
        );
    }

    private static RedisSnapshotFamilyDigest family(
        RedisSnapshotFamilyCode family,
        long count,
        long bytes,
        String digestSeed
    ) {
        byte[] seed = digestSeed.getBytes(StandardCharsets.UTF_8);
        byte[] digest = new byte[32];
        for (int index = 0; index < digest.length; index++) {
            digest[index] = seed[index % seed.length];
        }
        return new RedisSnapshotFamilyDigest(family, count, bytes, digest);
    }
}
