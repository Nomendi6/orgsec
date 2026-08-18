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
 * Strict canonical map codec for persistent protocol-v1 coordinator lease metadata.
 *
 * <p>This codec performs no Redis I/O. A bounded transport must verify the exact hash field count,
 * enforce the published per-field byte bounds with {@code HSTRLEN}, fetch only the ordered fields
 * with {@code HMGET}, and strictly decode UTF-8. Successful decoding yields only
 * {@link RedisCoordinatorLease.Unverified}; it cannot authorize a mutation. Encoding accepts only
 * a contextually {@link RedisCoordinatorLease.Verified} value.</p>
 */
final class RedisCoordinatorLeaseCodec {

    static final String FIELD_SCHEMA_VERSION = "schemaVersion";
    static final String FIELD_DATASET_ID = "datasetId";
    static final String FIELD_DATASET_HASH = "datasetHash";
    static final String FIELD_PROTOCOL_VERSION = "protocolVersion";
    static final String FIELD_PRIMARY_RUN_ID = "primaryRunId";
    static final String FIELD_STORAGE_UUID = "storageUuid";
    static final String FIELD_BOUND_CONTROL_COUNTER = "boundControlCounter";
    static final String FIELD_STATE = "state";
    static final String FIELD_REVISION = "revision";
    static final String FIELD_FENCING_SEQUENCE = "fencingSequence";
    static final String FIELD_OWNER_SESSION_ID = "ownerSessionId";
    static final String FIELD_ACQUISITION_ID = "acquisitionId";
    static final String FIELD_ISSUED_AT_REDIS_MILLIS = "issuedAtRedisMillis";
    static final String FIELD_EXPIRES_AT_REDIS_MILLIS = "expiresAtRedisMillis";

    private static final List<String> ORDERED_FIELDS = List.of(
        FIELD_SCHEMA_VERSION,
        FIELD_DATASET_ID,
        FIELD_DATASET_HASH,
        FIELD_PROTOCOL_VERSION,
        FIELD_PRIMARY_RUN_ID,
        FIELD_STORAGE_UUID,
        FIELD_BOUND_CONTROL_COUNTER,
        FIELD_STATE,
        FIELD_REVISION,
        FIELD_FENCING_SEQUENCE,
        FIELD_OWNER_SESSION_ID,
        FIELD_ACQUISITION_ID,
        FIELD_ISSUED_AT_REDIS_MILLIS,
        FIELD_EXPIRES_AT_REDIS_MILLIS
    );
    private static final Set<String> REQUIRED_FIELDS = Set.copyOf(ORDERED_FIELDS);
    static final int REQUIRED_FIELD_COUNT = ORDERED_FIELDS.size();

    private static final int MAX_DATASET_ID_UTF8_BYTES = 256;
    private static final int MAX_PROTOCOL_VERSION_UTF8_BYTES = 10;
    private static final int MAX_LUA_SAFE_INTEGER_UTF8_BYTES = 16;
    private static final int SHA_256_UTF8_BYTES = 64;
    private static final int PRIMARY_RUN_ID_UTF8_BYTES = 40;
    private static final int UUID_UTF8_BYTES = 36;
    private static final int STATE_UTF8_BYTES = 6;

    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");
    private static final Pattern NON_NEGATIVE_INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final Pattern LOWER_SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LOWER_PRIMARY_RUN_ID = Pattern.compile("[0-9a-f]{40}");

    Map<String, String> encode(RedisCoordinatorLease.Verified lease) {
        if (lease == null) {
            throw new IllegalArgumentException("verified lease must not be null");
        }

        SecurityDatasetIdentity identity = RedisWireProtocol.requireVersionOne(
            lease.identity()
        );
        RedisIncarnation incarnation = lease.incarnation();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_SCHEMA_VERSION, Long.toString(RedisCoordinatorLease.SCHEMA_VERSION));
        fields.put(FIELD_DATASET_ID, identity.getSecurityDatasetId());
        fields.put(FIELD_DATASET_HASH, lease.datasetHash());
        fields.put(FIELD_PROTOCOL_VERSION, Integer.toString(identity.getProtocolVersion()));
        fields.put(FIELD_PRIMARY_RUN_ID, incarnation.getPrimaryRunId());
        fields.put(FIELD_STORAGE_UUID, incarnation.getStorageUuid().toString());
        fields.put(
            FIELD_BOUND_CONTROL_COUNTER,
            Long.toString(lease.boundControlCounter())
        );
        fields.put(FIELD_STATE, lease.state().name());
        fields.put(FIELD_REVISION, Long.toString(lease.revision()));
        fields.put(FIELD_FENCING_SEQUENCE, Long.toString(lease.fencingSequence()));
        fields.put(
            FIELD_OWNER_SESSION_ID,
            lease.ownerSessionId() == null ? "" : lease.ownerSessionId().toString()
        );
        fields.put(
            FIELD_ACQUISITION_ID,
            lease.acquisitionId() == null ? "" : lease.acquisitionId().toString()
        );
        fields.put(
            FIELD_ISSUED_AT_REDIS_MILLIS,
            Long.toString(lease.issuedAtRedisMillis())
        );
        fields.put(
            FIELD_EXPIRES_AT_REDIS_MILLIS,
            Long.toString(lease.expiresAtRedisMillis())
        );
        for (String field : ORDERED_FIELDS) {
            encodeBoundedUtf8Value(field, fields.get(field));
        }
        return Collections.unmodifiableMap(fields);
    }

    RedisCoordinatorLease.Unverified decode(Map<String, String> fields) {
        requireExactFields(fields);

        long schemaVersion = parsePositiveLong(
            requiredValue(fields, FIELD_SCHEMA_VERSION),
            RedisCoordinatorLeaseCorruptionException.Reason.SCHEMA_VERSION_INVALID
        );
        if (schemaVersion != RedisCoordinatorLease.SCHEMA_VERSION) {
            throw corrupt(
                RedisCoordinatorLeaseCorruptionException.Reason.SCHEMA_VERSION_INVALID
            );
        }
        String datasetId = requiredValue(fields, FIELD_DATASET_ID);
        if (datasetId.trim().isEmpty()) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.DATASET_ID_INVALID);
        }
        String datasetHash = parseSha256(requiredValue(fields, FIELD_DATASET_HASH));
        int protocolVersion = parsePositiveInt(requiredValue(
            fields,
            FIELD_PROTOCOL_VERSION
        ));
        if (!RedisWireProtocol.isVersionOne(protocolVersion)) {
            throw corrupt(
                RedisCoordinatorLeaseCorruptionException.Reason.PROTOCOL_VERSION_INVALID
            );
        }
        String primaryRunId = parsePrimaryRunId(requiredValue(
            fields,
            FIELD_PRIMARY_RUN_ID
        ));
        UUID storageUuid = parseCanonicalUuidV4(requiredValue(fields, FIELD_STORAGE_UUID));
        long boundControlCounter = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_BOUND_CONTROL_COUNTER
        ));
        RedisCoordinatorLease.State state = parseState(requiredValue(fields, FIELD_STATE));
        long revision = parseNonNegativeLong(requiredValue(fields, FIELD_REVISION));
        long fencingSequence = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_FENCING_SEQUENCE
        ));
        String ownerSessionValue = requiredValueAllowEmpty(fields, FIELD_OWNER_SESSION_ID);
        String acquisitionValue = requiredValueAllowEmpty(fields, FIELD_ACQUISITION_ID);
        long issuedAtRedisMillis = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_ISSUED_AT_REDIS_MILLIS
        ));
        long expiresAtRedisMillis = parseNonNegativeLong(requiredValue(
            fields,
            FIELD_EXPIRES_AT_REDIS_MILLIS
        ));

        UUID ownerSessionId;
        UUID acquisitionId;
        if (state == RedisCoordinatorLease.State.FREE) {
            if (!ownerSessionValue.isEmpty()
                || !acquisitionValue.isEmpty()
                || issuedAtRedisMillis != 0
                || expiresAtRedisMillis != 0) {
                throw corrupt(
                    RedisCoordinatorLeaseCorruptionException.Reason.STATE_FIELDS_INVALID
                );
            }
            ownerSessionId = null;
            acquisitionId = null;
        } else {
            ownerSessionId = parseCanonicalUuidV4(ownerSessionValue);
            acquisitionId = parseCanonicalUuidV4(acquisitionValue);
            if (fencingSequence == 0) {
                throw corrupt(
                    RedisCoordinatorLeaseCorruptionException.Reason.STATE_FIELDS_INVALID
                );
            }
            if (issuedAtRedisMillis == 0 || expiresAtRedisMillis == 0) {
                throw corrupt(
                    RedisCoordinatorLeaseCorruptionException.Reason.STATE_FIELDS_INVALID
                );
            }
            if (expiresAtRedisMillis <= issuedAtRedisMillis) {
                throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.TIME_RANGE_INVALID);
            }
        }

        try {
            SecurityDatasetIdentity identity = new SecurityDatasetIdentity(
                datasetId,
                protocolVersion
            );
            RedisIncarnation incarnation = new RedisIncarnation(primaryRunId, storageUuid);
            return RedisCoordinatorLease.unverified(
                identity,
                datasetHash,
                incarnation,
                boundControlCounter,
                state,
                revision,
                fencingSequence,
                ownerSessionId,
                acquisitionId,
                issuedAtRedisMillis,
                expiresAtRedisMillis
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.IDENTITY_INVALID);
        }
    }

    static List<String> orderedFields() {
        return ORDERED_FIELDS;
    }

    static int maxUtf8Bytes(String field) {
        if (FIELD_DATASET_ID.equals(field)) {
            return MAX_DATASET_ID_UTF8_BYTES;
        }
        if (FIELD_SCHEMA_VERSION.equals(field)
            || FIELD_BOUND_CONTROL_COUNTER.equals(field)
            || FIELD_REVISION.equals(field)
            || FIELD_FENCING_SEQUENCE.equals(field)
            || FIELD_ISSUED_AT_REDIS_MILLIS.equals(field)
            || FIELD_EXPIRES_AT_REDIS_MILLIS.equals(field)) {
            return MAX_LUA_SAFE_INTEGER_UTF8_BYTES;
        }
        if (FIELD_PROTOCOL_VERSION.equals(field)) {
            return MAX_PROTOCOL_VERSION_UTF8_BYTES;
        }
        if (FIELD_DATASET_HASH.equals(field)) {
            return SHA_256_UTF8_BYTES;
        }
        if (FIELD_PRIMARY_RUN_ID.equals(field)) {
            return PRIMARY_RUN_ID_UTF8_BYTES;
        }
        if (FIELD_STORAGE_UUID.equals(field)
            || FIELD_OWNER_SESSION_ID.equals(field)
            || FIELD_ACQUISITION_ID.equals(field)) {
            return UUID_UTF8_BYTES;
        }
        if (FIELD_STATE.equals(field)) {
            return STATE_UTF8_BYTES;
        }
        throw new IllegalArgumentException("field must be a known coordinator lease field");
    }

    static boolean allowsEmpty(String field) {
        if (!ORDERED_FIELDS.contains(field)) {
            throw new IllegalArgumentException("field must be a known coordinator lease field");
        }
        return FIELD_OWNER_SESSION_ID.equals(field) || FIELD_ACQUISITION_ID.equals(field);
    }

    static byte[] encodeBoundedUtf8Value(String field, String value) {
        if (value == null || (value.isEmpty() && !allowsEmpty(field))) {
            throw encoding(
                RedisCoordinatorLeaseEncodingException.Reason.FIELD_VALUE_EMPTY
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
                RedisCoordinatorLeaseEncodingException.Reason.FIELD_UTF8_INVALID
            );
        }
        if (encoded.remaining() > maxUtf8Bytes(field)) {
            throw encoding(
                RedisCoordinatorLeaseEncodingException.Reason.FIELD_VALUE_TOO_LARGE
            );
        }
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    static String decodeBoundedUtf8Value(String field, byte[] value) {
        if (value == null || (value.length == 0 && !allowsEmpty(field))) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_EMPTY);
        }
        if (value.length > maxUtf8Bytes(field)) {
            throw corrupt(
                RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_TOO_LARGE
            );
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException exception) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.FIELD_UTF8_INVALID);
        }
    }

    private static void requireExactFields(Map<String, String> fields) {
        if (fields == null) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.NULL_MAP);
        }
        if (fields.size() != REQUIRED_FIELD_COUNT || !fields.keySet().equals(REQUIRED_FIELDS)) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.FIELD_SET_MISMATCH);
        }
    }

    private static String requiredValue(Map<String, String> fields, String field) {
        String value = requiredValueAllowEmpty(fields, field);
        if (value.isEmpty()) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_EMPTY);
        }
        return value;
    }

    private static String requiredValueAllowEmpty(Map<String, String> fields, String field) {
        String value = fields.get(field);
        if (value == null) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_EMPTY);
        }
        try {
            encodeBoundedUtf8Value(field, value);
        } catch (RedisCoordinatorLeaseEncodingException exception) {
            RedisCoordinatorLeaseEncodingException.Reason reason = exception.reason();
            if (reason == RedisCoordinatorLeaseEncodingException.Reason.FIELD_UTF8_INVALID) {
                throw corrupt(
                    RedisCoordinatorLeaseCorruptionException.Reason.FIELD_UTF8_INVALID
                );
            }
            if (reason == RedisCoordinatorLeaseEncodingException.Reason.FIELD_VALUE_TOO_LARGE) {
                throw corrupt(
                    RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_TOO_LARGE
                );
            }
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.FIELD_VALUE_EMPTY);
        }
        return value;
    }

    private static int parsePositiveInt(String value) {
        if (!POSITIVE_INTEGER.matcher(value).matches()) {
            throw corrupt(
                RedisCoordinatorLeaseCorruptionException.Reason.PROTOCOL_VERSION_INVALID
            );
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw corrupt(
                RedisCoordinatorLeaseCorruptionException.Reason.PROTOCOL_VERSION_INVALID
            );
        }
    }

    private static long parseNonNegativeLong(String value) {
        if (!NON_NEGATIVE_INTEGER.matcher(value).matches()) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.NUMBER_INVALID);
        }
        return parseLuaSafeLong(
            value,
            RedisCoordinatorLeaseCorruptionException.Reason.NUMBER_INVALID
        );
    }

    private static long parsePositiveLong(
        String value,
        RedisCoordinatorLeaseCorruptionException.Reason reason
    ) {
        if (!POSITIVE_INTEGER.matcher(value).matches()) {
            throw corrupt(reason);
        }
        return parseLuaSafeLong(value, reason);
    }

    private static long parseLuaSafeLong(
        String value,
        RedisCoordinatorLeaseCorruptionException.Reason reason
    ) {
        final long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw corrupt(reason);
        }
        if (parsed > RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER) {
            throw corrupt(reason);
        }
        return parsed;
    }

    private static String parseSha256(String value) {
        if (!LOWER_SHA_256.matcher(value).matches()) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.DIGEST_INVALID);
        }
        return value;
    }

    private static String parsePrimaryRunId(String value) {
        if (!LOWER_PRIMARY_RUN_ID.matcher(value).matches()) {
            throw corrupt(
                RedisCoordinatorLeaseCorruptionException.Reason.PRIMARY_RUN_ID_INVALID
            );
        }
        return value;
    }

    private static UUID parseCanonicalUuidV4(String value) {
        final UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.UUID_INVALID);
        }
        if (!parsed.toString().equals(value) || parsed.version() != 4 || parsed.variant() != 2) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.UUID_INVALID);
        }
        return parsed;
    }

    private static RedisCoordinatorLease.State parseState(String value) {
        try {
            return RedisCoordinatorLease.State.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw corrupt(RedisCoordinatorLeaseCorruptionException.Reason.STATE_INVALID);
        }
    }

    private static RedisCoordinatorLeaseCorruptionException corrupt(
        RedisCoordinatorLeaseCorruptionException.Reason reason
    ) {
        return new RedisCoordinatorLeaseCorruptionException(reason);
    }

    private static RedisCoordinatorLeaseEncodingException encoding(
        RedisCoordinatorLeaseEncodingException.Reason reason
    ) {
        return new RedisCoordinatorLeaseEncodingException(reason);
    }
}
