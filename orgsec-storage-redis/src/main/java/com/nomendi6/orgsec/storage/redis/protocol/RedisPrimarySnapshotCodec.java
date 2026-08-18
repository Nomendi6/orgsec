package com.nomendi6.orgsec.storage.redis.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict decoder for the bounded result returned by the primary snapshot script. */
final class RedisPrimarySnapshotCodec {

    private static final int RESULT_ELEMENT_COUNT = 7;
    private static final Pattern CANONICAL_RUN_ID = Pattern.compile("[0-9a-f]{40}");

    private final RedisControlEnvelopeCodec controlCodec;

    RedisPrimarySnapshotCodec(RedisControlEnvelopeCodec controlCodec) {
        if (controlCodec == null) {
            throw new NullPointerException("controlCodec must not be null");
        }
        this.controlCodec = controlCodec;
    }

    RedisPrimarySnapshot decode(Object rawResult) {
        if (!(rawResult instanceof List<?> result)) {
            throw corrupt("primary snapshot result is not an array");
        }
        if (result.size() != RESULT_ELEMENT_COUNT) {
            throw corrupt(
                "primary snapshot result must contain exactly " + RESULT_ELEMENT_COUNT +
                    " elements but contained " + result.size()
            );
        }

        Map<String, String> server = decodeInfoSection(result.get(0), "Server");
        Map<String, String> replication = decodeInfoSection(result.get(1), "Replication");
        Map<String, String> cluster = decodeInfoSection(result.get(2), "Cluster");
        Map<String, String> memory = decodeInfoSection(result.get(3), "Memory");

        long fieldCount = decodeFieldCount(result.get(4));
        List<?> rawLengths = decodeArray(result.get(5), "HSTRLEN");
        List<?> rawValues = decodeArray(result.get(6), "HMGET");
        RedisControlEnvelope controlEnvelope = decodeControl(
            fieldCount,
            rawLengths,
            rawValues
        );

        String runId = requireTopologyValue(server, "run_id", "Server");
        if (!CANONICAL_RUN_ID.matcher(runId).matches()) {
            throw new RedisProtocolTopologyException(
                "Redis run_id is not a canonical lower-case 40-character identifier."
            );
        }

        String role = requireTopologyValue(replication, "role", "Replication");
        if (!"master".equals(role)) {
            throw new RedisProtocolTopologyException(
                "Connected Redis node is not the physical primary: role=" + role + "."
            );
        }

        String clusterEnabledValue = requireTopologyValue(
            cluster,
            "cluster_enabled",
            "Cluster"
        );
        boolean clusterEnabled;
        if ("0".equals(clusterEnabledValue)) {
            clusterEnabled = false;
        } else if ("1".equals(clusterEnabledValue)) {
            throw new RedisProtocolTopologyException(
                "Redis Cluster mode is unsupported by the authorization snapshot protocol."
            );
        } else {
            throw new RedisProtocolTopologyException(
                "Redis reported an invalid cluster_enabled value: " +
                    clusterEnabledValue + "."
            );
        }

        String maxmemoryPolicy = requireConfigurationValue(memory, "maxmemory_policy", "Memory");
        if (!"noeviction".equals(maxmemoryPolicy)) {
            throw new RedisProtocolConfigurationException(
                "Redis maxmemory-policy must be noeviction but was " + maxmemoryPolicy + "."
            );
        }

        RedisPrimaryObservation observation = new RedisPrimaryObservation(
            runId,
            role,
            clusterEnabled,
            maxmemoryPolicy
        );
        return new RedisPrimarySnapshot(observation, controlEnvelope);
    }

    private RedisControlEnvelope decodeControl(
        long fieldCount,
        List<?> rawLengths,
        List<?> rawValues
    ) {
        if (fieldCount != 0 && fieldCount != RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT) {
            throw corrupt(
                "Redis control hash contains " + fieldCount + " fields; expected either 0 or " +
                    RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
            );
        }

        if (fieldCount == 0) {
            if (!rawLengths.isEmpty() || !rawValues.isEmpty()) {
                throw corrupt("Absent Redis control hash returned field metadata or values");
            }
            return null;
        }

        List<String> orderedFields = RedisControlEnvelopeCodec.orderedFields();
        if (rawLengths.size() != orderedFields.size()) {
            throw corrupt(
                "Redis control HSTRLEN result contains " + rawLengths.size() +
                    " elements but expected " + orderedFields.size()
            );
        }

        long[] lengths = new long[orderedFields.size()];
        for (int index = 0; index < orderedFields.size(); index++) {
            String field = orderedFields.get(index);
            Object rawLength = rawLengths.get(index);
            if (!(rawLength instanceof Long length) || length < 0) {
                throw corrupt(
                    "Redis control HSTRLEN for " + field + " is not a non-negative integer"
                );
            }
            int limit = RedisControlEnvelopeCodec.maxUtf8Bytes(field);
            if (length > limit) {
                throw corrupt(
                    "Redis control field " + field + " contains " + length +
                        " UTF-8 bytes; maximum is " + limit
                );
            }
            lengths[index] = length;
        }

        if (rawValues.size() != orderedFields.size()) {
            throw corrupt(
                "Redis control HMGET result contains " + rawValues.size() +
                    " elements but expected " + orderedFields.size()
            );
        }

        Map<String, String> fields = new LinkedHashMap<>(orderedFields.size());
        for (int index = 0; index < orderedFields.size(); index++) {
            String field = orderedFields.get(index);
            Object rawValue = rawValues.get(index);
            if (rawValue == null) {
                throw corrupt("Redis control hash is missing required field " + field);
            }
            if (!(rawValue instanceof byte[] bytes)) {
                throw corrupt("Redis control field " + field + " is not a bulk string");
            }
            if (bytes.length != lengths[index]) {
                throw corrupt(
                    "Redis control field " + field + " byte length does not match HSTRLEN"
                );
            }
            fields.put(field, decodeUtf8(bytes, "control field " + field));
        }

        RedisControlEnvelope envelope = controlCodec.decode(
            Collections.unmodifiableMap(fields)
        );
        if (envelope == null) {
            throw corrupt("Redis control envelope codec returned no envelope");
        }
        return envelope;
    }

    private static long decodeFieldCount(Object rawFieldCount) {
        if (!(rawFieldCount instanceof Long fieldCount)) {
            throw corrupt("Redis control HLEN result is not an integer");
        }
        if (fieldCount < 0) {
            throw corrupt("Redis control HLEN result must not be negative");
        }
        return fieldCount;
    }

    private static List<?> decodeArray(Object rawValue, String operation) {
        if (!(rawValue instanceof List<?> values)) {
            throw corrupt("Redis control " + operation + " result is not an array");
        }
        return values;
    }

    private static Map<String, String> decodeInfoSection(Object rawSection, String sectionName) {
        String value = decodeBulkString(rawSection, "INFO " + sectionName + " result");
        if (!value.endsWith("\r\n")) {
            throw corrupt("Redis INFO " + sectionName + " result is not CRLF terminated");
        }

        String[] lines = value.split("\r\n", -1);
        String expectedHeader = "# " + sectionName;
        if (lines.length < 2 || !expectedHeader.equals(lines[0])) {
            throw corrupt("Redis INFO " + sectionName + " result has an invalid section header");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < lines.length - 1; index++) {
            String line = lines[index];
            if (line.isEmpty() || line.charAt(0) == '#') {
                throw corrupt("Redis INFO " + sectionName + " result has an invalid field line");
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw corrupt("Redis INFO " + sectionName + " result has a malformed field line");
            }
            String name = line.substring(0, separator);
            String fieldValue = line.substring(separator + 1);
            if (fields.putIfAbsent(name, fieldValue) != null) {
                throw corrupt(
                    "Redis INFO " + sectionName + " result contains duplicate field " + name
                );
            }
        }
        return Collections.unmodifiableMap(fields);
    }

    private static String requireTopologyValue(
        Map<String, String> values,
        String field,
        String section
    ) {
        String value = values.get(field);
        if (value == null || value.isBlank()) {
            throw new RedisProtocolTopologyException(
                "Redis INFO " + section + " did not provide required field " + field + "."
            );
        }
        return value;
    }

    private static String requireConfigurationValue(
        Map<String, String> values,
        String field,
        String section
    ) {
        String value = values.get(field);
        if (value == null || value.isBlank()) {
            throw new RedisProtocolConfigurationException(
                "Redis INFO " + section + " did not provide required field " + field + "."
            );
        }
        return value;
    }

    private static String decodeBulkString(Object rawValue, String description) {
        if (!(rawValue instanceof byte[] bytes)) {
            throw corrupt("Redis " + description + " is not a bulk string");
        }
        return decodeUtf8(bytes, description);
    }

    private static String decodeUtf8(byte[] bytes, String description) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException failure) {
            throw corrupt("Redis " + description + " is not canonical UTF-8", failure);
        }
    }

    private static RedisControlCorruptionException corrupt(String detail) {
        return new RedisControlCorruptionException(detail);
    }

    private static RedisControlCorruptionException corrupt(String detail, Throwable cause) {
        return new RedisControlCorruptionException(detail, cause);
    }
}
