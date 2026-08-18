package com.nomendi6.orgsec.storage.redis.protocol;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Strict access to one atomic observation of the authoritative Redis primary and control hash.
 *
 * <p>The snapshot is obtained through one {@code EVAL} command. Lettuce classifies {@code EVAL}
 * as write-intent and routes it to the primary even when ordinary reads use replicas. Redis runs
 * the script atomically, so topology/configuration fields and the control hash cannot come from
 * different physical nodes or different points in a failover.</p>
 */
final class StrictRedisProtocolClient {

    private static final byte[] PRIMARY_SNAPSHOT_SCRIPT = buildPrimarySnapshotScript();

    private final RedisConnectionFactory connectionFactory;
    private final RedisPrimarySnapshotCodec snapshotCodec;

    StrictRedisProtocolClient(RedisConnectionFactory connectionFactory) {
        this(
            connectionFactory,
            new RedisPrimarySnapshotCodec(new RedisControlEnvelopeCodec())
        );
    }

    StrictRedisProtocolClient(
        RedisConnectionFactory connectionFactory,
        RedisPrimarySnapshotCodec snapshotCodec
    ) {
        this.connectionFactory = Objects.requireNonNull(
            connectionFactory,
            "connectionFactory must not be null"
        );
        this.snapshotCodec = Objects.requireNonNull(
            snapshotCodec,
            "snapshotCodec must not be null"
        );
    }

    /**
     * Reads one bounded primary snapshot for the canonical dataset keyspace.
     *
     * @param keyspace canonical dataset-isolated keyspace
     * @return immutable primary observation and optional control envelope
     */
    RedisPrimarySnapshot readPrimarySnapshot(RedisDatasetKeyspace keyspace) {
        Objects.requireNonNull(keyspace, "keyspace must not be null");

        RedisConnection connection = acquireConnection();
        Throwable pendingFailure = null;
        try {
            Object rawResult = executeSnapshot(connection, keyspace.controlKey());
            return snapshotCodec.decode(rawResult).bindRequestedKeyspace(keyspace);
        } catch (RuntimeException | Error failure) {
            pendingFailure = failure;
            throw failure;
        } finally {
            closeConnection(connection, pendingFailure);
        }
    }

    private RedisConnection acquireConnection() {
        RedisConnection connection;
        try {
            connection = connectionFactory.getConnection();
        } catch (RuntimeException failure) {
            throw new RedisProtocolUnavailableException(
                "Failed to obtain an authoritative Redis connection.",
                failure
            );
        }
        if (connection == null) {
            throw new RedisProtocolUnavailableException(
                "RedisConnectionFactory returned no connection."
            );
        }
        return connection;
    }

    private static Object executeSnapshot(RedisConnection connection, String controlKey) {
        try {
            RedisScriptingCommands scriptingCommands = connection.scriptingCommands();
            if (scriptingCommands == null) {
                throw new RedisProtocolUnavailableException(
                    "RedisConnection returned no scripting commands."
                );
            }
            Object rawResult = scriptingCommands.eval(
                PRIMARY_SNAPSHOT_SCRIPT,
                ReturnType.MULTI,
                1,
                controlKey.getBytes(StandardCharsets.UTF_8)
            );
            if (rawResult == null) {
                throw new RedisProtocolUnavailableException(
                    "Redis primary snapshot script returned no result."
                );
            }
            return rawResult;
        } catch (RedisProtocolException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RedisProtocolUnavailableException(
                "Redis primary snapshot script failed.",
                failure
            );
        }
    }

    private static byte[] buildPrimarySnapshotScript() {
        List<String> fields = RedisControlEnvelopeCodec.orderedFields();
        StringJoiner fieldNames = new StringJoiner(", ", "{", "}");
        StringJoiner fieldLimits = new StringJoiner(", ", "{", "}");
        for (String field : fields) {
            fieldNames.add("'" + field + "'");
            fieldLimits.add(Integer.toString(RedisControlEnvelopeCodec.maxUtf8Bytes(field)));
        }

        String script = "local fieldNames = " + fieldNames + "\n" +
            "local fieldLimits = " + fieldLimits + "\n" +
            "local fieldCount = redis.call('HLEN', KEYS[1])\n" +
            "local fieldLengths = {}\n" +
            "local withinLimits = fieldCount == " + fields.size() + "\n" +
            "if withinLimits then\n" +
            "  for index, fieldName in ipairs(fieldNames) do\n" +
            "    local fieldLength = redis.call('HSTRLEN', KEYS[1], fieldName)\n" +
            "    fieldLengths[index] = fieldLength\n" +
            "    if fieldLength > fieldLimits[index] then\n" +
            "      withinLimits = false\n" +
            "    end\n" +
            "  end\n" +
            "end\n" +
            "local fieldValues = {}\n" +
            "if withinLimits then\n" +
            "  fieldValues = redis.call('HMGET', KEYS[1], unpack(fieldNames))\n" +
            "end\n" +
            "return {\n" +
            "  redis.call('INFO', 'server'),\n" +
            "  redis.call('INFO', 'replication'),\n" +
            "  redis.call('INFO', 'cluster'),\n" +
            "  redis.call('INFO', 'memory'),\n" +
            "  fieldCount,\n" +
            "  fieldLengths,\n" +
            "  fieldValues\n" +
            "}";
        return script.getBytes(StandardCharsets.UTF_8);
    }

    private static void closeConnection(RedisConnection connection, Throwable pendingFailure) {
        try {
            connection.close();
        } catch (RuntimeException closeFailure) {
            RedisProtocolUnavailableException unavailable = new RedisProtocolUnavailableException(
                "Failed to close the authoritative Redis connection.",
                closeFailure
            );
            if (pendingFailure != null) {
                pendingFailure.addSuppressed(unavailable);
            } else {
                throw unavailable;
            }
        }
    }
}
