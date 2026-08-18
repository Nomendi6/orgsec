package com.nomendi6.orgsec.storage.redis.protocol;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Atomically publishes one sealed staging snapshot as READY and returns the writer lease to FREE.
 *
 * <p>The script rechecks the exact primary topology, control generation, ACTIVE lease token and
 * Redis time, then verifies that the named manifest already exists. Only that combined CAS writes
 * control or lease state.</p>
 */
final class RedisSnapshotPublisher {

    private static final byte[] PUBLISH_READY = buildPublishReadyScript();
    private static final byte[] PUBLISHED = ascii("PUBLISHED");
    private static final byte[] CONTROL_CHANGED = ascii("CONTROL_CHANGED");
    private static final byte[] LEASE_CHANGED = ascii("LEASE_CHANGED");
    private static final byte[] MANIFEST_MISMATCH = ascii("MANIFEST_MISMATCH");

    private final RedisConnectionFactory connectionFactory;
    private final RedisControlEnvelopeCodec controlCodec;
    private final RedisCoordinatorLeaseCodec leaseCodec;
    private final RedisSnapshotManifestCodec manifestCodec;

    RedisSnapshotPublisher(RedisConnectionFactory connectionFactory) {
        this(
            connectionFactory,
            new RedisControlEnvelopeCodec(),
            new RedisCoordinatorLeaseCodec(),
            new RedisSnapshotManifestCodec()
        );
    }

    RedisSnapshotPublisher(
        RedisConnectionFactory connectionFactory,
        RedisControlEnvelopeCodec controlCodec,
        RedisCoordinatorLeaseCodec leaseCodec,
        RedisSnapshotManifestCodec manifestCodec
    ) {
        this.connectionFactory = Objects.requireNonNull(
            connectionFactory,
            "connectionFactory must not be null"
        );
        this.controlCodec = Objects.requireNonNull(controlCodec, "controlCodec must not be null");
        this.leaseCodec = Objects.requireNonNull(leaseCodec, "leaseCodec must not be null");
        this.manifestCodec = Objects.requireNonNull(
            manifestCodec,
            "manifestCodec must not be null"
        );
    }

    /**
     * Switches control to READY for the sealed snapshot and releases the writer lease.
     *
     * @return the published READY control envelope
     */
    RedisControlEnvelope publishReady(
        RedisPrimarySnapshot expectedPrimary,
        RedisCoordinatorLease.Verified activeLease,
        RedisSnapshotManifest.Verified manifest
    ) {
        Objects.requireNonNull(expectedPrimary, "expectedPrimary must not be null");
        Objects.requireNonNull(activeLease, "activeLease must not be null");
        Objects.requireNonNull(manifest, "manifest must not be null");
        RedisControlEnvelope current = expectedPrimary.controlEnvelope().orElseThrow(() ->
            new RedisStagingFenceException(RedisStagingFenceException.Reason.EXPECTATION_INCONSISTENT)
        );
        if (activeLease.state() != RedisCoordinatorLease.State.ACTIVE
            || !activeLease.identity().equals(current.getIdentity())
            || !activeLease.incarnation().equals(current.getIncarnation())
            || activeLease.boundControlCounter() != current.getCounter()) {
            throw new RedisStagingFenceException(
                RedisStagingFenceException.Reason.EXPECTATION_INCONSISTENT
            );
        }
        if (current.getCounter() >= RedisControlEnvelope.MAX_COUNTER) {
            throw new RedisProtocolUnavailableException(
                "Redis publication failed because the control counter is exhausted."
            );
        }
        RedisControlEnvelope published = new RedisControlEnvelope(
            current.getIdentity(),
            current.getIncarnation(),
            current.getCounter() + 1,
            manifest.snapshotId(),
            RedisControlState.READY
        );
        RedisDatasetKeyspace keyspace = expectedPrimary.requestedKeyspace().orElseGet(
            () -> new RedisDatasetKeyspace(current.getIdentity().getSecurityDatasetId())
        );

        List<byte[]> arguments = new ArrayList<>();
        arguments.add(ascii(expectedPrimary.observation().runId()));
        appendFields(arguments, controlCodec.encode(current), RedisControlEnvelopeCodec.orderedFields());
        appendFields(arguments, leaseCodec.encode(activeLease), RedisCoordinatorLeaseCodec.orderedFields());
        appendFields(arguments, controlCodec.encode(published), RedisControlEnvelopeCodec.orderedFields());
        appendFields(
            arguments,
            leaseCodec.encode(releasedLease(activeLease, published.getCounter())),
            RedisCoordinatorLeaseCodec.orderedFields()
        );
        appendFields(arguments, manifestCodec.encode(manifest), RedisSnapshotManifestCodec.orderedFields());

        RedisConnection connection = acquireConnection();
        Throwable pendingFailure = null;
        try {
            Object result = evaluate(connection, keyspace, manifest, arguments);
            if (matches(result, PUBLISHED)) {
                return published;
            }
            if (matches(result, CONTROL_CHANGED)) {
                throw new RedisStagingFenceException(
                    RedisStagingFenceException.Reason.GENERATION_CHANGED
                );
            }
            if (matches(result, LEASE_CHANGED)) {
                throw new RedisStagingFenceException(
                    RedisStagingFenceException.Reason.LEASE_CHANGED
                );
            }
            if (matches(result, MANIFEST_MISMATCH)) {
                throw new RedisStagingCorruptionException(
                    RedisStagingCorruptionException.Reason.MANIFEST_MISMATCH
                );
            }
            throw new RedisProtocolUnavailableException(
                "Redis publication script returned an unexpected result."
            );
        } catch (RuntimeException | Error failure) {
            pendingFailure = failure;
            throw failure;
        } finally {
            closeConnection(connection, pendingFailure);
        }
    }

    private Object evaluate(
        RedisConnection connection,
        RedisDatasetKeyspace keyspace,
        RedisSnapshotManifest.Verified manifest,
        List<byte[]> arguments
    ) {
        RedisScriptingCommands commands = connection.scriptingCommands();
        if (commands == null) {
            throw new RedisProtocolUnavailableException(
                "Redis connection returned no scripting commands."
            );
        }
        byte[][] keys = new byte[][] {
            utf8(keyspace.controlKey()),
            utf8(keyspace.leaseKey()),
            utf8(keyspace.leaseCounterKey()),
            utf8(keyspace.manifestKey(manifest.snapshotId()))
        };
        byte[][] keysAndArguments = new byte[keys.length + arguments.size()][];
        System.arraycopy(keys, 0, keysAndArguments, 0, keys.length);
        for (int index = 0; index < arguments.size(); index++) {
            keysAndArguments[keys.length + index] = arguments.get(index);
        }
        Object result = commands.eval(
            PUBLISH_READY,
            ReturnType.VALUE,
            keys.length,
            keysAndArguments
        );
        if (result == null) {
            throw new RedisProtocolUnavailableException(
                "Redis publication script returned no result."
            );
        }
        return result;
    }

    private RedisConnection acquireConnection() {
        final RedisConnection connection;
        try {
            connection = connectionFactory.getConnection();
        } catch (RuntimeException failure) {
            throw new RedisProtocolUnavailableException(
                "Failed to acquire a Redis publication connection.",
                failure
            );
        }
        if (connection == null) {
            throw new RedisProtocolUnavailableException(
                "RedisConnectionFactory returned no publication connection."
            );
        }
        return connection;
    }

    private static void closeConnection(RedisConnection connection, Throwable pendingFailure) {
        try {
            connection.close();
        } catch (RuntimeException closeFailure) {
            RedisProtocolUnavailableException unavailable = new RedisProtocolUnavailableException(
                "Failed to close the Redis publication connection.",
                closeFailure
            );
            if (pendingFailure != null) {
                pendingFailure.addSuppressed(unavailable);
            } else {
                throw unavailable;
            }
        }
    }

    private static RedisCoordinatorLease.Verified releasedLease(
        RedisCoordinatorLease.Verified active,
        long publishedControlCounter
    ) {
        long revision = active.revision() + 1;
        RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
            active.identity(),
            active.datasetHash(),
            active.incarnation(),
            publishedControlCounter,
            RedisCoordinatorLease.State.FREE,
            revision,
            active.fencingSequence(),
            null,
            null,
            0,
            0
        );
        return RedisCoordinatorLease.verifyFreeForContext(
            candidate,
            active.identity(),
            active.datasetHash(),
            active.incarnation(),
            publishedControlCounter,
            revision,
            active.fencingSequence()
        );
    }

    private static void appendFields(
        List<byte[]> target,
        Map<String, String> fields,
        List<String> order
    ) {
        for (String field : order) {
            target.add(utf8(fields.get(field)));
        }
    }

    private static boolean matches(Object result, byte[] expected) {
        return result instanceof byte[] bytes && Arrays.equals(bytes, expected);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildPublishReadyScript() {
        int controlCount = RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;
        int leaseCount = RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT;
        int manifestCount = RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT;
        int expectedArguments = 1 + (2 * controlCount) + (2 * leaseCount) + manifestCount;
        int currentControlOffset = 1;
        int currentLeaseOffset = currentControlOffset + controlCount;
        int publishedControlOffset = currentLeaseOffset + leaseCount;
        int releasedLeaseOffset = publishedControlOffset + controlCount;
        int manifestOffset = releasedLeaseOffset + leaseCount;
        int issuedIndex = currentLeaseOffset
            + RedisCoordinatorLeaseCodec.orderedFields().indexOf(
                RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS
            )
            + 1;
        int expiresIndex = currentLeaseOffset
            + RedisCoordinatorLeaseCodec.orderedFields().indexOf(
                RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS
            )
            + 1;
        StringJoiner controlFields = fieldTable(RedisControlEnvelopeCodec.orderedFields());
        StringJoiner leaseFields = fieldTable(RedisCoordinatorLeaseCodec.orderedFields());
        StringJoiner manifestFields = fieldTable(RedisSnapshotManifestCodec.orderedFields());
        return (
            "if #KEYS ~= 4 or #ARGV ~= " + expectedArguments + " then return 'CONTROL_CHANGED' end\n" +
            "local controlFields = " + controlFields + "\n" +
            "local leaseFields = " + leaseFields + "\n" +
            "local manifestFields = " + manifestFields + "\n" +
            "local serverInfo = redis.call('INFO', 'server')\n" +
            "local replicationInfo = redis.call('INFO', 'replication')\n" +
            "local clusterInfo = redis.call('INFO', 'cluster')\n" +
            "local memoryInfo = redis.call('INFO', 'memory')\n" +
            "if #serverInfo > 16384 or #replicationInfo > 16384 " +
                "or #clusterInfo > 4096 or #memoryInfo > 32768 then\n" +
            "  return 'CONTROL_CHANGED'\n" +
            "end\n" +
            "local currentRunId = string.match(serverInfo, '\\r\\nrun_id:([0-9a-f]+)\\r\\n')\n" +
            "local currentRole = string.match(replicationInfo, '\\r\\nrole:([^\\r\\n]+)\\r\\n')\n" +
            "local clusterEnabled = string.match(clusterInfo, '\\r\\ncluster_enabled:([^\\r\\n]+)\\r\\n')\n" +
            "local maxmemoryPolicy = string.match(memoryInfo, '\\r\\nmaxmemory_policy:([^\\r\\n]+)\\r\\n')\n" +
            "if currentRunId == nil or currentRunId ~= ARGV[1] " +
                "or currentRole ~= 'master' or clusterEnabled ~= '0' " +
                "or maxmemoryPolicy ~= 'noeviction' then\n" +
            "  return 'CONTROL_CHANGED'\n" +
            "end\n" +
            exactHash("KEYS[1]", "controlFields", currentControlOffset, "CONTROL_CHANGED") +
            exactHash("KEYS[2]", "leaseFields", currentLeaseOffset, "LEASE_CHANGED") +
            "local redisTime = redis.call('TIME')\n" +
            "if type(redisTime) ~= 'table' or #redisTime < 2 then return 'LEASE_CHANGED' end\n" +
            "local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)\n" +
            "local issued = tonumber(ARGV[" + issuedIndex + "])\n" +
            "local expires = tonumber(ARGV[" + expiresIndex + "])\n" +
            "if now == nil or issued == nil or expires == nil or now < issued or now >= expires then\n" +
            "  return 'LEASE_CHANGED'\n" +
            "end\n" +
            "if redis.call('EXISTS', KEYS[4]) == 0 " +
                "or redis.call('HLEN', KEYS[4]) ~= " + manifestCount + " then\n" +
            "  return 'MANIFEST_MISMATCH'\n" +
            "end\n" +
            "for fieldIndex, fieldName in ipairs(manifestFields) do\n" +
            "  if redis.call('HGET', KEYS[4], fieldName) ~= ARGV[" +
                manifestOffset + " + fieldIndex] then\n" +
            "    return 'MANIFEST_MISMATCH'\n" +
            "  end\n" +
            "end\n" +
            "local controlArgs = {}\n" +
            "for fieldIndex, fieldName in ipairs(controlFields) do\n" +
            "  controlArgs[#controlArgs + 1] = fieldName\n" +
            "  controlArgs[#controlArgs + 1] = ARGV[" + publishedControlOffset + " + fieldIndex]\n" +
            "end\n" +
            "local leaseArgs = {}\n" +
            "for fieldIndex, fieldName in ipairs(leaseFields) do\n" +
            "  leaseArgs[#leaseArgs + 1] = fieldName\n" +
            "  leaseArgs[#leaseArgs + 1] = ARGV[" + releasedLeaseOffset + " + fieldIndex]\n" +
            "end\n" +
            "redis.call('HSET', KEYS[1], unpack(controlArgs))\n" +
            "redis.call('HSET', KEYS[2], unpack(leaseArgs))\n" +
            "return 'PUBLISHED'\n"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private static String exactHash(
        String key,
        String fieldTable,
        int argumentOffset,
        String mismatch
    ) {
        return "if redis.call('HLEN', " + key + ") ~= #" + fieldTable + " then\n" +
            "  return '" + mismatch + "'\n" +
            "end\n" +
            "for fieldIndex, fieldName in ipairs(" + fieldTable + ") do\n" +
            "  if redis.call('HGET', " + key + ", fieldName) ~= ARGV[" +
                argumentOffset + " + fieldIndex] then\n" +
            "    return '" + mismatch + "'\n" +
            "  end\n" +
            "end\n";
    }

    private static StringJoiner fieldTable(List<String> fields) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (String field : fields) {
            joiner.add("'" + field + "'");
        }
        return joiner;
    }
}
