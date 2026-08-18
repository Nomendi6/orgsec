package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict canonical map codec for the protocol-v1 Redis snapshot manifest.
 *
 * <p>This codec is not a Redis transport boundary. Before calling {@link #decode(Map)}, a separate
 * bounded reader must verify {@code HLEN == 12}; use {@code HSTRLEN} to enforce per-field byte
 * limits before retrieving values; fetch only the 12 ordered known fields with {@code HMGET}; and
 * strictly decode every field name and value as UTF-8 while rejecting duplicate decoded names.
 * The reader must not materialize an unbounded {@code HGETALL}. This class does not read Redis and
 * does not make a snapshot READY.</p>
 *
 * <p>Successful syntactic decoding yields only {@link RedisSnapshotManifest.Unverified}. It is not
 * adoption. Encoding accepts only {@link RedisSnapshotManifest.Verified} values created by
 * publication or contextual verification.</p>
 */
final class RedisSnapshotManifestCodec {

    static final String FIELD_DATASET_ID = "datasetId";
    static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
    static final String FIELD_SECURITY_CONTENT_VERSION = "securityContentVersion";
    static final String FIELD_SNAPSHOT_ID = "snapshotId";
    static final String FIELD_CONTENT_DIGEST = "contentDigest";
    static final String FIELD_PERSONS_COUNT = "personsCount";
    static final String FIELD_ORGANIZATIONS_COUNT = "organizationsCount";
    static final String FIELD_PARTY_ROLES_COUNT = "partyRolesCount";
    static final String FIELD_POSITION_ROLES_COUNT = "positionRolesCount";
    static final String FIELD_ROLES_COUNT = "rolesCount";
    static final String FIELD_PRIVILEGES_COUNT = "privilegesCount";
    static final String FIELD_ACCOUNTED_BYTES = "accountedBytes";

    private static final List<String> ORDERED_FIELDS = List.of(
        FIELD_DATASET_ID,
        FIELD_PROTOCOL_VERSION,
        FIELD_SECURITY_CONTENT_VERSION,
        FIELD_SNAPSHOT_ID,
        FIELD_CONTENT_DIGEST,
        FIELD_PERSONS_COUNT,
        FIELD_ORGANIZATIONS_COUNT,
        FIELD_PARTY_ROLES_COUNT,
        FIELD_POSITION_ROLES_COUNT,
        FIELD_ROLES_COUNT,
        FIELD_PRIVILEGES_COUNT,
        FIELD_ACCOUNTED_BYTES
    );
    private static final Set<String> REQUIRED_FIELDS = Set.copyOf(ORDERED_FIELDS);
    static final int REQUIRED_FIELD_COUNT = ORDERED_FIELDS.size();

    private static final int MAX_DATASET_ID_UTF8_BYTES = 256;
    private static final int MAX_PROTOCOL_VERSION_UTF8_BYTES = 10;
    private static final int MAX_LONG_UTF8_BYTES = 19;
    private static final int SHA_256_UTF8_BYTES = 64;
    private static final int UUID_UTF8_BYTES = 36;

    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");
    private static final Pattern NON_NEGATIVE_INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern LOWER_SHA_256 = Pattern.compile("[0-9a-f]{64}");

    Map<String, String> encode(RedisSnapshotManifest.Verified manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("verified manifest must not be null");
        }

        SecurityDatasetFence fence = manifest.sourceFence();
        SecurityDatasetIdentity identity = RedisWireProtocol.requireVersionOne(
            fence.getIdentity()
        );
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_DATASET_ID, identity.getSecurityDatasetId());
        fields.put(FIELD_PROTOCOL_VERSION, Integer.toString(identity.getProtocolVersion()));
        fields.put(
            FIELD_SECURITY_CONTENT_VERSION,
            Long.toString(fence.getSecurityContentVersion())
        );
        fields.put(FIELD_SNAPSHOT_ID, manifest.snapshotId().toString());
        fields.put(FIELD_CONTENT_DIGEST, manifest.contentDigest());
        fields.put(FIELD_PERSONS_COUNT, Long.toString(manifest.personsCount()));
        fields.put(
            FIELD_ORGANIZATIONS_COUNT,
            Long.toString(manifest.organizationsCount())
        );
        fields.put(FIELD_PARTY_ROLES_COUNT, Long.toString(manifest.partyRolesCount()));
        fields.put(
            FIELD_POSITION_ROLES_COUNT,
            Long.toString(manifest.positionRolesCount())
        );
        fields.put(FIELD_ROLES_COUNT, Long.toString(manifest.rolesCount()));
        fields.put(FIELD_PRIVILEGES_COUNT, Long.toString(manifest.privilegesCount()));
        fields.put(FIELD_ACCOUNTED_BYTES, Long.toString(manifest.accountedBytes()));
        for (String field : ORDERED_FIELDS) {
            encodeBoundedUtf8Value(field, fields.get(field));
        }
        return Collections.unmodifiableMap(fields);
    }

    RedisSnapshotManifest.Unverified decode(Map<String, String> fields) {
        requireExactFields(fields);

        String datasetId = requiredValue(fields, FIELD_DATASET_ID);
        if (datasetId.trim().isEmpty()) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.DATASET_ID_INVALID);
        }
        int protocolVersion = parsePositiveInt(requiredValue(
            fields,
            FIELD_PROTOCOL_VERSION
        ));
        if (!RedisWireProtocol.isVersionOne(protocolVersion)) {
            throw corrupt(
                RedisSnapshotManifestCorruptionException.Reason.PROTOCOL_VERSION_INVALID
            );
        }
        long securityContentVersion = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_SECURITY_CONTENT_VERSION
        ));
        UUID snapshotId = parseCanonicalUuidV4(requiredValue(fields, FIELD_SNAPSHOT_ID));
        String contentDigest = parseSha256(requiredValue(fields, FIELD_CONTENT_DIGEST));
        long personsCount = parseNonNegativeLong(requiredValue(fields, FIELD_PERSONS_COUNT));
        long organizationsCount = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_ORGANIZATIONS_COUNT
        ));
        long partyRolesCount = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_PARTY_ROLES_COUNT
        ));
        long positionRolesCount = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_POSITION_ROLES_COUNT
        ));
        long rolesCount = parseNonNegativeLong(requiredValue(fields, FIELD_ROLES_COUNT));
        long privilegesCount = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_PRIVILEGES_COUNT
        ));
        long accountedBytes = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_ACCOUNTED_BYTES
        ));

        try {
            SecurityDatasetIdentity identity = new SecurityDatasetIdentity(
                datasetId,
                protocolVersion
            );
            SecurityDatasetFence sourceFence = new SecurityDatasetFence(
                identity,
                securityContentVersion
            );
            return RedisSnapshotManifest.unverified(
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
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.IDENTITY_INVALID);
        }
    }

    /**
     * Returns the exact protocol-v1 HMGET field order.
     *
     * @return immutable ordered field names
     */
    static List<String> orderedFields() {
        return ORDERED_FIELDS;
    }

    /**
     * Returns the exact maximum UTF-8 value length for one known field.
     *
     * @param field exact canonical field name
     * @return maximum encoded value bytes
     */
    static int maxUtf8Bytes(String field) {
        if (FIELD_DATASET_ID.equals(field)) {
            return MAX_DATASET_ID_UTF8_BYTES;
        }
        if (FIELD_PROTOCOL_VERSION.equals(field)) {
            return MAX_PROTOCOL_VERSION_UTF8_BYTES;
        }
        if (FIELD_CONTENT_DIGEST.equals(field)) {
            return SHA_256_UTF8_BYTES;
        }
        if (FIELD_SNAPSHOT_ID.equals(field)) {
            return UUID_UTF8_BYTES;
        }
        if (FIELD_SECURITY_CONTENT_VERSION.equals(field)
            || FIELD_PERSONS_COUNT.equals(field)
            || FIELD_ORGANIZATIONS_COUNT.equals(field)
            || FIELD_PARTY_ROLES_COUNT.equals(field)
            || FIELD_POSITION_ROLES_COUNT.equals(field)
            || FIELD_ROLES_COUNT.equals(field)
            || FIELD_PRIVILEGES_COUNT.equals(field)
            || FIELD_ACCOUNTED_BYTES.equals(field)) {
            return MAX_LONG_UTF8_BYTES;
        }
        throw new IllegalArgumentException("field must be a known manifest field");
    }

    /**
     * Strictly encodes one trusted value for a bounded Redis write.
     *
     * @param field exact canonical field name
     * @param value trusted Java value
     * @return exact UTF-8 bytes
     */
    static byte[] encodeBoundedUtf8Value(String field, String value) {
        if (value == null || value.isEmpty()) {
            throw encoding(
                RedisSnapshotManifestEncodingException.Reason.FIELD_VALUE_EMPTY
            );
        }
        final ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
        } catch (CharacterCodingException exception) {
            throw encoding(
                RedisSnapshotManifestEncodingException.Reason.FIELD_UTF8_INVALID
            );
        }
        if (encoded.remaining() > maxUtf8Bytes(field)) {
            throw encoding(
                RedisSnapshotManifestEncodingException.Reason.FIELD_VALUE_TOO_LARGE
            );
        }
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    /**
     * Strictly decodes one HSTRLEN-bounded Redis value before map construction.
     *
     * @param field exact canonical field name
     * @param value bounded wire bytes
     * @return exact Java string
     */
    static String decodeBoundedUtf8Value(String field, byte[] value) {
        if (value == null || value.length == 0) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_EMPTY);
        }
        if (value.length > maxUtf8Bytes(field)) {
            throw corrupt(
                RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_TOO_LARGE
            );
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException exception) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.FIELD_UTF8_INVALID);
        }
    }

    private static void requireExactFields(Map<String, String> fields) {
        if (fields == null) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.NULL_MAP);
        }
        if (fields.size() != REQUIRED_FIELD_COUNT || !fields.keySet().equals(REQUIRED_FIELDS)) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.FIELD_SET_MISMATCH);
        }
    }

    private static String requiredValue(Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null || value.isEmpty()) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_EMPTY);
        }
        try {
            encodeBoundedUtf8Value(field, value);
        } catch (RedisSnapshotManifestEncodingException exception) {
            RedisSnapshotManifestEncodingException.Reason reason = exception.reason();
            if (reason == RedisSnapshotManifestEncodingException.Reason.FIELD_UTF8_INVALID) {
                throw corrupt(
                    RedisSnapshotManifestCorruptionException.Reason.FIELD_UTF8_INVALID
                );
            }
            if (reason == RedisSnapshotManifestEncodingException.Reason.FIELD_VALUE_TOO_LARGE) {
                throw corrupt(
                    RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_TOO_LARGE
                );
            }
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.FIELD_VALUE_EMPTY);
        }
        return value;
    }

    private static int parsePositiveInt(String value) {
        if (!POSITIVE_INTEGER.matcher(value).matches()) {
            throw corrupt(
                RedisSnapshotManifestCorruptionException.Reason.PROTOCOL_VERSION_INVALID
            );
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw corrupt(
                RedisSnapshotManifestCorruptionException.Reason.PROTOCOL_VERSION_INVALID
            );
        }
    }

    private static long parseNonNegativeLong(String value) {
        if (!NON_NEGATIVE_INTEGER.matcher(value).matches()) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.NUMBER_INVALID);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.NUMBER_INVALID);
        }
    }

    private static String parseSha256(String value) {
        if (!LOWER_SHA_256.matcher(value).matches()) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.DIGEST_INVALID);
        }
        return value;
    }

    private static UUID parseCanonicalUuidV4(String value) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.SNAPSHOT_ID_INVALID);
        }
        if (!parsed.toString().equals(value) || parsed.version() != 4 || parsed.variant() != 2) {
            throw corrupt(RedisSnapshotManifestCorruptionException.Reason.SNAPSHOT_ID_INVALID);
        }
        return parsed;
    }

    private static RedisSnapshotManifestCorruptionException corrupt(
        RedisSnapshotManifestCorruptionException.Reason reason
    ) {
        return new RedisSnapshotManifestCorruptionException(reason);
    }

    private static RedisSnapshotManifestEncodingException encoding(
        RedisSnapshotManifestEncodingException.Reason reason
    ) {
        return new RedisSnapshotManifestEncodingException(reason);
    }

}
