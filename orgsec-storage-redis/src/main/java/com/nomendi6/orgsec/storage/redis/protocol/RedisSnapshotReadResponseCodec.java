package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict decoder for the two bounded snapshot-reader EVAL response shapes. */
final class RedisSnapshotReadResponseCodec {

    private static final int MANIFEST_RESULT_ELEMENTS = 11;
    private static final int PAGE_RESULT_ELEMENTS = 13;
    private static final int MAX_STATUS_BYTES = 64;

    private final RedisPrimarySnapshotCodec primarySnapshotCodec;
    private final RedisSnapshotManifestCodec manifestCodec;

    RedisSnapshotReadResponseCodec() {
        this(
            new RedisPrimarySnapshotCodec(new RedisControlEnvelopeCodec()),
            new RedisSnapshotManifestCodec()
        );
    }

    RedisSnapshotReadResponseCodec(
        RedisPrimarySnapshotCodec primarySnapshotCodec,
        RedisSnapshotManifestCodec manifestCodec
    ) {
        this.primarySnapshotCodec = Objects.requireNonNull(
            primarySnapshotCodec,
            "primarySnapshotCodec must not be null"
        );
        this.manifestCodec = Objects.requireNonNull(
            manifestCodec,
            "manifestCodec must not be null"
        );
    }

    ManifestRead decodeManifest(Object rawResult, RedisSnapshotGeneration expectedGeneration) {
        Objects.requireNonNull(expectedGeneration, "expectedGeneration must not be null");
        List<?> result = requireSuccessfulResult(rawResult);
        if (result.size() != MANIFEST_RESULT_ELEMENTS) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }

        RedisPrimarySnapshot primarySnapshot = decodePrimarySnapshot(result);
        RedisSnapshotGeneration observedGeneration;
        try {
            observedGeneration = RedisSnapshotGeneration.from(
                primarySnapshot,
                expectedGeneration.identity()
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw failure(RedisSnapshotReadException.Reason.GENERATION_CHANGED);
        }
        if (!expectedGeneration.equals(observedGeneration)) {
            throw failure(RedisSnapshotReadException.Reason.GENERATION_CHANGED);
        }

        long fieldCount = requireLong(result.get(8));
        if (fieldCount != RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT) {
            throw failure(RedisSnapshotReadException.Reason.MANIFEST_CORRUPT);
        }
        List<?> rawLengths = requireArray(result.get(9));
        List<?> rawValues = requireArray(result.get(10));
        List<String> orderedFields = RedisSnapshotManifestCodec.orderedFields();
        if (rawLengths.size() != orderedFields.size()
            || rawValues.size() != orderedFields.size()) {
            throw failure(RedisSnapshotReadException.Reason.MANIFEST_CORRUPT);
        }

        Map<String, String> wireFields = new LinkedHashMap<>(orderedFields.size());
        for (int index = 0; index < orderedFields.size(); index++) {
            String field = orderedFields.get(index);
            long length = requireManifestLength(rawLengths.get(index), field);
            Object rawValue = rawValues.get(index);
            if (!(rawValue instanceof byte[] value) || value.length != length) {
                throw failure(RedisSnapshotReadException.Reason.MANIFEST_CORRUPT);
            }
            try {
                wireFields.put(
                    field,
                    RedisSnapshotManifestCodec.decodeBoundedUtf8Value(field, value)
                );
            } catch (RedisSnapshotManifestCorruptionException exception) {
                throw failure(RedisSnapshotReadException.Reason.MANIFEST_CORRUPT);
            }
        }

        Map<String, String> immutableWireFields = Collections.unmodifiableMap(wireFields);
        RedisSnapshotManifest.Unverified candidate;
        try {
            candidate = manifestCodec.decode(immutableWireFields);
        } catch (RedisSnapshotManifestCorruptionException exception) {
            throw failure(RedisSnapshotReadException.Reason.MANIFEST_CORRUPT);
        }
        return new ManifestRead(observedGeneration, immutableWireFields, candidate);
    }

    RedisSnapshotPage decodePage(
        Object rawResult,
        RedisSnapshotGeneration expectedGeneration,
        RedisSnapshotFamily family,
        long offset,
        long familyCount,
        RedisSnapshotReadLimits limits
    ) {
        Objects.requireNonNull(expectedGeneration, "expectedGeneration must not be null");
        Objects.requireNonNull(family, "family must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (offset < 0 || familyCount < 0 || offset > familyCount) {
            throw new IllegalArgumentException("offset must be within the family range");
        }

        List<?> result = requireSuccessfulResult(rawResult);
        if (result.size() != PAGE_RESULT_ELEMENTS) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
        RedisPrimarySnapshot primarySnapshot = decodePrimarySnapshot(result);
        RedisSnapshotGeneration observedGeneration;
        try {
            observedGeneration = RedisSnapshotGeneration.from(
                primarySnapshot,
                expectedGeneration.identity()
            );
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw failure(RedisSnapshotReadException.Reason.GENERATION_CHANGED);
        }
        if (!expectedGeneration.equals(observedGeneration)) {
            throw failure(RedisSnapshotReadException.Reason.GENERATION_CHANGED);
        }

        if (requireLong(result.get(8)) != offset || requireLong(result.get(9)) != familyCount) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }

        long remaining = Math.subtractExact(familyCount, offset);
        int expectedEntries = (int) Math.min(remaining, limits.pageEntries());
        List<?> rawKeys = requireArray(result.get(10));
        List<?> rawLengths = requireArray(result.get(11));
        List<?> rawPayloads = requireArray(result.get(12));
        if (rawKeys.size() != expectedEntries
            || rawLengths.size() != expectedEntries
            || rawPayloads.size() != expectedEntries) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }

        List<RedisCanonicalEntry> entries = new ArrayList<>(expectedEntries);
        byte[] previousKey = null;
        long cumulativeBytes = 0;
        for (int index = 0; index < expectedEntries; index++) {
            Object rawKey = rawKeys.get(index);
            Object rawPayload = rawPayloads.get(index);
            if (!(rawKey instanceof byte[] key) || !(rawPayload instanceof byte[] payload)) {
                throw failure(RedisSnapshotReadException.Reason.PAGE_ENTRY_INVALID);
            }
            if (key.length == 0 || key.length > limits.pageKeyBytes()) {
                throw failure(RedisSnapshotReadException.Reason.PAGE_ENTRY_INVALID);
            }
            if (previousKey != null && Arrays.compareUnsigned(previousKey, key) >= 0) {
                throw failure(RedisSnapshotReadException.Reason.INDEX_ENTRY_INVALID);
            }

            long declaredPayloadLength = requireLong(rawLengths.get(index));
            if (declaredPayloadLength <= 0
                || declaredPayloadLength > limits.pagePayloadBytes()
                || declaredPayloadLength != payload.length) {
                throw failure(RedisSnapshotReadException.Reason.PAGE_ENTRY_INVALID);
            }
            try {
                cumulativeBytes = Math.addExact(cumulativeBytes, key.length);
                cumulativeBytes = Math.addExact(cumulativeBytes, payload.length);
            } catch (ArithmeticException exception) {
                throw failure(RedisSnapshotReadException.Reason.PAGE_LIMIT_EXCEEDED);
            }
            if (cumulativeBytes > limits.pageTotalBytes()) {
                throw failure(RedisSnapshotReadException.Reason.PAGE_LIMIT_EXCEEDED);
            }

            entries.add(new RedisCanonicalEntry(key, payload));
            previousKey = key;
        }

        long nextOffset;
        try {
            nextOffset = Math.addExact(offset, expectedEntries);
        } catch (ArithmeticException exception) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
        boolean done = nextOffset == familyCount;
        return new RedisSnapshotPage(family, offset, nextOffset, entries, done);
    }

    private RedisPrimarySnapshot decodePrimarySnapshot(List<?> result) {
        List<?> primaryResult = Arrays.asList(
            result.get(1),
            result.get(2),
            result.get(3),
            result.get(4),
            result.get(5),
            result.get(6),
            result.get(7)
        );
        try {
            return primarySnapshotCodec.decode(primaryResult);
        } catch (RedisProtocolConfigurationException exception) {
            throw failure(RedisSnapshotReadException.Reason.PRIMARY_CONFIGURATION_INVALID);
        } catch (RedisProtocolTopologyException exception) {
            throw failure(RedisSnapshotReadException.Reason.GENERATION_CHANGED);
        } catch (RedisControlCorruptionException exception) {
            throw failure(RedisSnapshotReadException.Reason.CONTROL_CORRUPT);
        } catch (RedisProtocolException exception) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
    }

    private static List<?> requireSuccessfulResult(Object rawResult) {
        if (!(rawResult instanceof List<?> result) || result.isEmpty()) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
        String status = decodeStatus(result.get(0));
        if ("OK".equals(status)) {
            return result;
        }
        if (result.size() != 1) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
        throw switch (status) {
            case "GENERATION_CHANGED" -> failure(
                RedisSnapshotReadException.Reason.GENERATION_CHANGED
            );
            case "CONTROL_CORRUPT" -> failure(
                RedisSnapshotReadException.Reason.CONTROL_CORRUPT
            );
            case "MANIFEST_CORRUPT" -> failure(
                RedisSnapshotReadException.Reason.MANIFEST_CORRUPT
            );
            case "MANIFEST_CHANGED" -> failure(
                RedisSnapshotReadException.Reason.MANIFEST_CHANGED
            );
            case "FAMILY_CARDINALITY_MISMATCH" -> failure(
                RedisSnapshotReadException.Reason.FAMILY_CARDINALITY_MISMATCH
            );
            case "INDEX_ENTRY_INVALID" -> failure(
                RedisSnapshotReadException.Reason.INDEX_ENTRY_INVALID
            );
            case "PAGE_ENTRY_INVALID" -> failure(
                RedisSnapshotReadException.Reason.PAGE_ENTRY_INVALID
            );
            case "PAGE_LIMIT_EXCEEDED" -> failure(
                RedisSnapshotReadException.Reason.PAGE_LIMIT_EXCEEDED
            );
            case "PRIMARY_CONFIGURATION_INVALID" -> failure(
                RedisSnapshotReadException.Reason.PRIMARY_CONFIGURATION_INVALID
            );
            default -> failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        };
    }

    private static String decodeStatus(Object rawStatus) {
        if (!(rawStatus instanceof byte[] bytes)
            || bytes.length == 0
            || bytes.length > MAX_STATUS_BYTES) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
        StringBuilder status = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int character = value & 0xff;
            if ((character < 'A' || character > 'Z') && character != '_') {
                throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
            }
            status.append((char) character);
        }
        return status.toString();
    }

    private static long requireManifestLength(Object rawLength, String field) {
        long length = requireLong(rawLength);
        if (length < 0 || length > RedisSnapshotManifestCodec.maxUtf8Bytes(field)) {
            throw failure(RedisSnapshotReadException.Reason.MANIFEST_CORRUPT);
        }
        return length;
    }

    private static long requireLong(Object rawValue) {
        if (!(rawValue instanceof Long value)) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
        return value;
    }

    private static List<?> requireArray(Object rawValue) {
        if (!(rawValue instanceof List<?> values)) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
        return values;
    }

    private static RedisSnapshotReadException failure(
        RedisSnapshotReadException.Reason reason
    ) {
        return new RedisSnapshotReadException(reason);
    }

    static final class ManifestRead {

        private final RedisSnapshotGeneration generation;
        private final Map<String, String> wireFields;
        private final RedisSnapshotManifest.Unverified candidate;

        ManifestRead(
            RedisSnapshotGeneration generation,
            Map<String, String> wireFields,
            RedisSnapshotManifest.Unverified candidate
        ) {
            this.generation = generation;
            this.wireFields = wireFields;
            this.candidate = candidate;
        }

        RedisSnapshotGeneration generation() {
            return generation;
        }

        Map<String, String> wireFields() {
            return wireFields;
        }

        RedisSnapshotManifest.Unverified candidate() {
            return candidate;
        }
    }
}
