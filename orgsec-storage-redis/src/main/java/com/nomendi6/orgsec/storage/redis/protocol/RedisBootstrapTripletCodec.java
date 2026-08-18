package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.storage.redis.protocol.RedisBootstrapTripletCorruptionException.Reason;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Strict decoder for the bounded result of the read-only bootstrap triplet script. */
final class RedisBootstrapTripletCodec {

    static final int MAX_INFO_SECTION_BYTES = 64 * 1024;

    private static final int RESULT_ELEMENT_COUNT = 8;
    private static final Pattern CANONICAL_COUNTER = Pattern.compile("0|[1-9][0-9]*");

    private final RedisPrimarySnapshotCodec primaryCodec;
    private final RedisControlEnvelopeCodec controlCodec;
    private final RedisCoordinatorLeaseCodec leaseCodec;

    RedisBootstrapTripletCodec() {
        this(
            new RedisPrimarySnapshotCodec(new RedisControlEnvelopeCodec()),
            new RedisControlEnvelopeCodec(),
            new RedisCoordinatorLeaseCodec()
        );
    }

    RedisBootstrapTripletCodec(
        RedisPrimarySnapshotCodec primaryCodec,
        RedisControlEnvelopeCodec controlCodec,
        RedisCoordinatorLeaseCodec leaseCodec
    ) {
        this.primaryCodec = Objects.requireNonNull(primaryCodec, "primaryCodec must not be null");
        this.controlCodec = Objects.requireNonNull(controlCodec, "controlCodec must not be null");
        this.leaseCodec = Objects.requireNonNull(leaseCodec, "leaseCodec must not be null");
    }

    RedisBootstrapTripletObservation decode(
        Object rawResult,
        RedisDatasetKeyspace requestedKeyspace
    ) {
        Objects.requireNonNull(requestedKeyspace, "requestedKeyspace must not be null");
        if (!(rawResult instanceof List<?> result) || result.isEmpty()) {
            throw corrupt(Reason.RESULT_SHAPE_INVALID);
        }

        String status = decodeAscii(result.get(0), 32, Reason.RESULT_SHAPE_INVALID);
        if (result.size() == 1) {
            if ("INFO_BOUNDS_INVALID".equals(status)) {
                throw corrupt(Reason.INFO_BOUNDS_INVALID);
            }
            throw corrupt(Reason.RESULT_SHAPE_INVALID);
        }
        if (!"OK".equals(status) || result.size() != RESULT_ELEMENT_COUNT) {
            throw corrupt(Reason.RESULT_SHAPE_INVALID);
        }

        List<Object> infoSections = new ArrayList<>(4);
        for (int index = 1; index <= 4; index++) {
            infoSections.add(requireBoundedInfo(result.get(index)));
        }
        RedisPrimaryObservation primary = decodePrimary(infoSections);
        requireNoConnectedReplicas(infoSections.get(1));

        DecodedHash controlHash = decodeHash(result.get(5), HashKind.CONTROL);
        DecodedHash leaseHash = decodeHash(result.get(6), HashKind.LEASE);
        Long leaseCounter = decodeCounter(result.get(7));

        RedisControlEnvelope control = decodeControl(controlHash);
        RedisCoordinatorLease.Unverified lease = decodeLease(leaseHash);
        return new RedisBootstrapTripletObservation(
            requestedKeyspace,
            primary,
            control,
            lease,
            leaseCounter
        );
    }

    private RedisPrimaryObservation decodePrimary(List<Object> infoSections) {
        List<Object> primaryResult = new ArrayList<>(7);
        primaryResult.addAll(infoSections);
        primaryResult.add(Long.valueOf(0));
        primaryResult.add(List.of());
        primaryResult.add(List.of());
        try {
            return primaryCodec.decode(primaryResult).observation();
        } catch (RedisProtocolTopologyException exception) {
            throw corrupt(Reason.TOPOLOGY_INVALID);
        } catch (RedisProtocolConfigurationException exception) {
            throw corrupt(Reason.CONFIGURATION_INVALID);
        } catch (RedisProtocolException exception) {
            throw corrupt(Reason.INFO_WIRE_INVALID);
        }
    }

    private RedisControlEnvelope decodeControl(DecodedHash hash) {
        if (!hash.present()) {
            return null;
        }
        try {
            return controlCodec.decode(hash.fields());
        } catch (RedisProtocolException exception) {
            throw corrupt(Reason.CONTROL_WIRE_INVALID);
        }
    }

    private RedisCoordinatorLease.Unverified decodeLease(DecodedHash hash) {
        if (!hash.present()) {
            return null;
        }
        try {
            return leaseCodec.decode(hash.fields());
        } catch (RedisProtocolException exception) {
            throw corrupt(Reason.LEASE_WIRE_INVALID);
        }
    }

    private static DecodedHash decodeHash(Object rawValue, HashKind kind) {
        Reason wireReason = kind.wireReason();
        if (!(rawValue instanceof List<?> values) || values.size() != 5) {
            throw corrupt(wireReason);
        }
        String keyType = decodeAscii(values.get(0), 16, wireReason);
        long ttl = decodeInteger(values.get(1), wireReason);
        long fieldCount = decodeInteger(values.get(2), wireReason);
        List<?> lengths = decodeArray(values.get(3), wireReason);
        List<?> fieldValues = decodeArray(values.get(4), wireReason);

        if ("none".equals(keyType)) {
            if (ttl != -2) {
                throw corrupt(kind.ttlReason());
            }
            if (fieldCount != 0 || !lengths.isEmpty() || !fieldValues.isEmpty()) {
                throw corrupt(wireReason);
            }
            return DecodedHash.absent();
        }
        if (!"hash".equals(keyType)) {
            throw corrupt(kind.typeReason());
        }
        if (ttl != -1) {
            throw corrupt(kind.ttlReason());
        }

        List<String> fields = kind.fields();
        if (fieldCount != fields.size()
            || lengths.size() != fields.size()
            || fieldValues.size() != fields.size()) {
            throw corrupt(wireReason);
        }

        Map<String, String> decoded = new LinkedHashMap<>(fields.size());
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            long length = decodeInteger(lengths.get(index), wireReason);
            int maximum = kind.maximumBytes(field);
            if (length < 0 || length > maximum) {
                throw corrupt(wireReason);
            }
            Object rawFieldValue = fieldValues.get(index);
            if (!(rawFieldValue instanceof byte[] bytes) || bytes.length != length) {
                throw corrupt(wireReason);
            }
            decoded.put(field, decodeUtf8(bytes, wireReason));
        }
        return DecodedHash.present(Collections.unmodifiableMap(decoded));
    }

    private static Long decodeCounter(Object rawValue) {
        if (!(rawValue instanceof List<?> values) || values.size() != 4) {
            throw corrupt(Reason.COUNTER_WIRE_INVALID);
        }
        String keyType = decodeAscii(values.get(0), 16, Reason.COUNTER_WIRE_INVALID);
        long ttl = decodeInteger(values.get(1), Reason.COUNTER_WIRE_INVALID);
        long length = decodeInteger(values.get(2), Reason.COUNTER_WIRE_INVALID);
        Object counterValue = values.get(3);

        if ("none".equals(keyType)) {
            if (ttl != -2) {
                throw corrupt(Reason.COUNTER_TTL_INVALID);
            }
            if (length != 0 || counterValue != null) {
                throw corrupt(Reason.COUNTER_WIRE_INVALID);
            }
            return null;
        }
        if (!"string".equals(keyType)) {
            throw corrupt(Reason.COUNTER_TYPE_INVALID);
        }
        if (ttl != -1) {
            throw corrupt(Reason.COUNTER_TTL_INVALID);
        }
        if (length <= 0 || length > 16
            || !(counterValue instanceof byte[] bytes)
            || bytes.length != length) {
            throw corrupt(Reason.COUNTER_WIRE_INVALID);
        }
        String text = decodeAscii(bytes, 16, Reason.COUNTER_WIRE_INVALID);
        if (!CANONICAL_COUNTER.matcher(text).matches()) {
            throw corrupt(Reason.COUNTER_WIRE_INVALID);
        }
        final long parsed;
        try {
            parsed = Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw corrupt(Reason.COUNTER_WIRE_INVALID);
        }
        if (parsed > RedisCoordinatorLease.MAX_LUA_SAFE_INTEGER) {
            throw corrupt(Reason.COUNTER_WIRE_INVALID);
        }
        return parsed;
    }

    private static byte[] requireBoundedInfo(Object rawValue) {
        if (!(rawValue instanceof byte[] bytes)) {
            throw corrupt(Reason.INFO_WIRE_INVALID);
        }
        if (bytes.length == 0 || bytes.length > MAX_INFO_SECTION_BYTES) {
            throw corrupt(Reason.INFO_BOUNDS_INVALID);
        }
        return bytes;
    }

    private static void requireNoConnectedReplicas(Object rawReplication) {
        byte[] bytes = (byte[]) rawReplication;
        String replication = decodeUtf8(bytes, Reason.INFO_WIRE_INVALID);
        String connectedReplicas = null;
        String[] lines = replication.split("\r\n", -1);
        for (int index = 1; index < lines.length - 1; index++) {
            String line = lines[index];
            int separator = line.indexOf(':');
            if (separator > 0 && "connected_slaves".equals(line.substring(0, separator))) {
                connectedReplicas = line.substring(separator + 1);
                break;
            }
        }
        if (!"0".equals(connectedReplicas)) {
            throw corrupt(Reason.TOPOLOGY_INVALID);
        }
    }

    private static List<?> decodeArray(Object rawValue, Reason reason) {
        if (!(rawValue instanceof List<?> values)) {
            throw corrupt(reason);
        }
        return values;
    }

    private static long decodeInteger(Object rawValue, Reason reason) {
        if (!(rawValue instanceof Long value)) {
            throw corrupt(reason);
        }
        return value;
    }

    private static String decodeAscii(Object rawValue, int maximumBytes, Reason reason) {
        if (!(rawValue instanceof byte[] bytes)) {
            throw corrupt(reason);
        }
        return decodeAscii(bytes, maximumBytes, reason);
    }

    private static String decodeAscii(byte[] bytes, int maximumBytes, Reason reason) {
        if (bytes.length == 0 || bytes.length > maximumBytes) {
            throw corrupt(reason);
        }
        for (byte value : bytes) {
            if (value < 0 || value > 0x7f) {
                throw corrupt(reason);
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static String decodeUtf8(byte[] bytes, Reason reason) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw corrupt(reason);
        }
    }

    private static RedisBootstrapTripletCorruptionException corrupt(Reason reason) {
        return new RedisBootstrapTripletCorruptionException(reason);
    }

    private enum HashKind {
        CONTROL,
        LEASE;

        List<String> fields() {
            return this == CONTROL
                ? RedisControlEnvelopeCodec.orderedFields()
                : RedisCoordinatorLeaseCodec.orderedFields();
        }

        int maximumBytes(String field) {
            return this == CONTROL
                ? RedisControlEnvelopeCodec.maxUtf8Bytes(field)
                : RedisCoordinatorLeaseCodec.maxUtf8Bytes(field);
        }

        Reason typeReason() {
            return this == CONTROL ? Reason.CONTROL_TYPE_INVALID : Reason.LEASE_TYPE_INVALID;
        }

        Reason ttlReason() {
            return this == CONTROL ? Reason.CONTROL_TTL_INVALID : Reason.LEASE_TTL_INVALID;
        }

        Reason wireReason() {
            return this == CONTROL ? Reason.CONTROL_WIRE_INVALID : Reason.LEASE_WIRE_INVALID;
        }
    }

    private static final class DecodedHash {

        private final Map<String, String> fields;

        private DecodedHash(Map<String, String> fields) {
            this.fields = fields;
        }

        static DecodedHash absent() {
            return new DecodedHash(null);
        }

        static DecodedHash present(Map<String, String> fields) {
            return new DecodedHash(Objects.requireNonNull(fields, "fields must not be null"));
        }

        boolean present() {
            return fields != null;
        }

        Map<String, String> fields() {
            if (fields == null) {
                throw new IllegalStateException("absent hash has no fields");
            }
            return fields;
        }
    }

}
