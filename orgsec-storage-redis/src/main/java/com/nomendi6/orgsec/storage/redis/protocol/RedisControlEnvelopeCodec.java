package com.nomendi6.orgsec.storage.redis.protocol;

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
 * Strict canonical codec for the Redis control hash.
 *
 * <p>Redis hashes are unordered, so canonical form is defined by the exact field set and the
 * canonical textual representation of every value. Unknown fields are rejected to prevent an
 * older binary from silently adopting metadata with semantics it does not understand.</p>
 */
final class RedisControlEnvelopeCodec {

    static final String FIELD_DATASET_ID = "datasetId";
    static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
    static final String FIELD_PRIMARY_RUN_ID = "primaryRunId";
    static final String FIELD_STORAGE_UUID = "storageUuid";
    static final String FIELD_COUNTER = "counter";
    static final String FIELD_ACTIVE_SNAPSHOT_ID = "activeSnapshotId";
    static final String FIELD_STATE = "state";

    static final int MAX_DATASET_ID_UTF8_BYTES = 256;

    private static final List<String> ORDERED_FIELDS = List.of(
        FIELD_DATASET_ID,
        FIELD_PROTOCOL_VERSION,
        FIELD_PRIMARY_RUN_ID,
        FIELD_STORAGE_UUID,
        FIELD_COUNTER,
        FIELD_ACTIVE_SNAPSHOT_ID,
        FIELD_STATE
    );
    private static final Set<String> REQUIRED_FIELDS = Set.copyOf(ORDERED_FIELDS);
    private static final Map<String, Integer> MAX_UTF8_BYTES = Map.ofEntries(
        Map.entry(FIELD_DATASET_ID, MAX_DATASET_ID_UTF8_BYTES),
        Map.entry(FIELD_PROTOCOL_VERSION, 10),
        Map.entry(FIELD_PRIMARY_RUN_ID, 40),
        Map.entry(FIELD_STORAGE_UUID, 36),
        Map.entry(FIELD_COUNTER, 16),
        Map.entry(FIELD_ACTIVE_SNAPSHOT_ID, 36),
        Map.entry(FIELD_STATE, 12)
    );
    static final int REQUIRED_FIELD_COUNT = REQUIRED_FIELDS.size();
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");
    private static final Pattern NON_NEGATIVE_INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern PRIMARY_RUN_ID = Pattern.compile("[0-9a-f]{40}");

    /**
     * Encodes an envelope as the exact canonical Redis hash field map.
     *
     * @param envelope validated envelope
     * @return immutable canonical field map
     */
    Map<String, String> encode(RedisControlEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        SecurityDatasetIdentity identity = RedisWireProtocol.requireVersionOne(
            envelope.getIdentity()
        );
        RedisIncarnation incarnation = envelope.getIncarnation();
        UUID activeSnapshotId = envelope.getActiveSnapshotId();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_DATASET_ID, identity.getSecurityDatasetId());
        fields.put(FIELD_PROTOCOL_VERSION, Integer.toString(identity.getProtocolVersion()));
        fields.put(FIELD_PRIMARY_RUN_ID, incarnation.getPrimaryRunId());
        fields.put(FIELD_STORAGE_UUID, incarnation.getStorageUuid().toString());
        fields.put(FIELD_COUNTER, Long.toString(envelope.getCounter()));
        fields.put(
            FIELD_ACTIVE_SNAPSHOT_ID,
            activeSnapshotId == null ? "" : activeSnapshotId.toString()
        );
        fields.put(FIELD_STATE, envelope.getState().name());
        validateEncodedFieldLengths(fields);
        return Collections.unmodifiableMap(fields);
    }

    /**
     * Decodes an exact canonical Redis hash field map.
     *
     * <p>The transport layer retrieves only the ordered canonical fields and owns strict UTF-8
     * decoding. This codec independently enforces the exact field set, per-field byte bounds and
     * canonical value grammar.</p>
     *
     * @param fields strictly decoded canonical Redis hash fields
     * @return validated immutable envelope
     * @throws RedisControlCorruptionException if any field is missing, unknown, malformed or
     * non-canonical
     */
    RedisControlEnvelope decode(Map<String, String> fields) {
        requireExactFields(fields);
        validateDecodedFieldLengths(fields);

        String datasetId = requiredValue(fields, FIELD_DATASET_ID);
        if (datasetId.trim().isEmpty()) {
            throw corrupt(FIELD_DATASET_ID + " must not be blank");
        }
        int protocolVersion = parsePositiveInt(
            FIELD_PROTOCOL_VERSION,
            requiredValue(fields, FIELD_PROTOCOL_VERSION)
        );
        if (!RedisWireProtocol.isVersionOne(protocolVersion)) {
            throw corrupt("PROTOCOL_VERSION_INVALID");
        }
        String primaryRunId = parsePrimaryRunId(requiredValue(fields, FIELD_PRIMARY_RUN_ID));
        UUID storageUuid = parseRandomUuid(
            FIELD_STORAGE_UUID,
            requiredValue(fields, FIELD_STORAGE_UUID)
        );
        long counter = parseLuaSafeCounter(
            FIELD_COUNTER,
            requiredValue(fields, FIELD_COUNTER)
        );
        RedisControlState state = parseState(requiredValue(fields, FIELD_STATE));
        UUID activeSnapshotId = parseActiveSnapshotId(
            requiredValueAllowEmpty(fields, FIELD_ACTIVE_SNAPSHOT_ID)
        );

        try {
            SecurityDatasetIdentity identity = new SecurityDatasetIdentity(
                datasetId,
                protocolVersion
            );
            RedisIncarnation incarnation = new RedisIncarnation(primaryRunId, storageUuid);
            return new RedisControlEnvelope(
                identity,
                incarnation,
                counter,
                activeSnapshotId,
                state
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw corrupt("control envelope violates protocol invariants", exception);
        }
    }

    static List<String> orderedFields() {
        return ORDERED_FIELDS;
    }

    static int maxUtf8Bytes(String field) {
        Integer limit = MAX_UTF8_BYTES.get(field);
        if (limit == null) {
            throw new IllegalArgumentException("Unknown Redis control field " + field);
        }
        return limit;
    }

    private static void requireExactFields(Map<String, String> fields) {
        if (fields == null) {
            throw corrupt("control envelope map is null");
        }
        if (!fields.keySet().equals(REQUIRED_FIELDS)) {
            Set<String> missing = new java.util.HashSet<>(REQUIRED_FIELDS);
            missing.removeAll(fields.keySet());
            Set<String> unknown = new java.util.HashSet<>(fields.keySet());
            unknown.removeAll(REQUIRED_FIELDS);
            throw corrupt("control envelope has missing fields " + missing +
                " and unknown fields " + unknown);
        }
    }

    private static void validateEncodedFieldLengths(Map<String, String> fields) {
        for (String field : ORDERED_FIELDS) {
            String value = fields.get(field);
            int byteLength;
            try {
                byteLength = strictUtf8Length(value);
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException(
                    field + " is not encodable as canonical UTF-8",
                    exception
                );
            }
            int limit = maxUtf8Bytes(field);
            if (byteLength > limit) {
                throw new IllegalArgumentException(
                    field + " exceeds the maximum of " + limit + " UTF-8 bytes"
                );
            }
        }
    }

    private static void validateDecodedFieldLengths(Map<String, String> fields) {
        for (String field : ORDERED_FIELDS) {
            String value = fields.get(field);
            if (value == null) {
                continue;
            }
            int byteLength;
            try {
                byteLength = strictUtf8Length(value);
            } catch (CharacterCodingException exception) {
                throw corrupt(field + " is not canonical UTF-8", exception);
            }
            int limit = maxUtf8Bytes(field);
            if (byteLength > limit) {
                throw corrupt(field + " exceeds the maximum of " + limit + " UTF-8 bytes");
            }
        }
    }

    private static int strictUtf8Length(String value) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
        return encoded.remaining();
    }

    private static String requiredValue(Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null || value.isEmpty()) {
            throw corrupt(field + " must not be empty");
        }
        return value;
    }

    private static String requiredValueAllowEmpty(Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null) {
            throw corrupt(field + " must not be null");
        }
        return value;
    }

    private static int parsePositiveInt(String field, String value) {
        if (!POSITIVE_INTEGER.matcher(value).matches()) {
            throw corrupt(field + " is not a canonical positive integer");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw corrupt(field + " is outside the supported integer range", exception);
        }
    }

    private static long parseNonNegativeLong(String field, String value) {
        if (!NON_NEGATIVE_INTEGER.matcher(value).matches()) {
            throw corrupt(field + " is not a canonical non-negative integer");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw corrupt(field + " is outside the supported integer range", exception);
        }
    }

    private static long parseLuaSafeCounter(String field, String value) {
        long parsed = parseNonNegativeLong(field, value);
        if (parsed > RedisControlEnvelope.MAX_COUNTER) {
            throw corrupt(field + " exceeds the Redis Lua-safe integer maximum");
        }
        return parsed;
    }

    private static String parsePrimaryRunId(String value) {
        if (!PRIMARY_RUN_ID.matcher(value).matches()) {
            throw corrupt(
                FIELD_PRIMARY_RUN_ID + " is not a canonical lower-case Redis run ID"
            );
        }
        return value;
    }

    private static UUID parseCanonicalUuid(String field, String value) {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw corrupt(field + " is not a canonical lower-case UUID", exception);
        }
        if (!parsed.toString().equals(value)) {
            throw corrupt(field + " is not a canonical lower-case UUID");
        }
        return parsed;
    }

    private static UUID parseRandomUuid(String field, String value) {
        UUID parsed = parseCanonicalUuid(field, value);
        if (parsed.version() != 4 || parsed.variant() != 2) {
            throw corrupt(field +
                " violates control envelope invariants: expected an RFC 4122 version-4 UUID");
        }
        return parsed;
    }

    private static RedisControlState parseState(String value) {
        try {
            return RedisControlState.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw corrupt(FIELD_STATE + " is not a recognized canonical state", exception);
        }
    }

    private static UUID parseActiveSnapshotId(String value) {
        return value.isEmpty() ? null : parseCanonicalUuid(FIELD_ACTIVE_SNAPSHOT_ID, value);
    }

    private static RedisControlCorruptionException corrupt(String detail) {
        return new RedisControlCorruptionException(detail);
    }

    private static RedisControlCorruptionException corrupt(String detail, Throwable cause) {
        return new RedisControlCorruptionException(detail, cause);
    }
}
