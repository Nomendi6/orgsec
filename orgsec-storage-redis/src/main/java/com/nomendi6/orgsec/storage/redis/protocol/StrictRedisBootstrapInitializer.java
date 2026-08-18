package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetIdentity;
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
import java.util.UUID;
import java.util.function.Supplier;

/** Installs the absent protocol-v1 bootstrap metadata triplet on one direct Redis primary. */
final class StrictRedisBootstrapInitializer {

    private static final int KEY_COUNT = 3;
    private static final int CONTROL_ARGUMENT_OFFSET = 0;
    private static final int LEASE_ARGUMENT_OFFSET =
        RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;
    private static final int COUNTER_ARGUMENT_INDEX =
        LEASE_ARGUMENT_OFFSET + RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT + 1;
    private static final int EXPECTED_ARGUMENT_COUNT = COUNTER_ARGUMENT_INDEX;

    private static final byte[] INITIALIZE_IF_ABSENT = buildInitializeScript();
    private static final byte[] INITIALIZED = ascii(Outcome.INITIALIZED.name());
    private static final byte[] PRESENT_REQUIRES_VALIDATION = ascii(
        Outcome.PRESENT_REQUIRES_VALIDATION.name()
    );

    private final RedisConnectionFactory connectionFactory;
    private final RedisControlEnvelopeCodec controlCodec;
    private final RedisCoordinatorLeaseCodec leaseCodec;
    private final Supplier<UUID> storageUuidSupplier;

    StrictRedisBootstrapInitializer(RedisConnectionFactory connectionFactory) {
        this(
            connectionFactory,
            new RedisControlEnvelopeCodec(),
            new RedisCoordinatorLeaseCodec(),
            UUID::randomUUID
        );
    }

    StrictRedisBootstrapInitializer(
        RedisConnectionFactory connectionFactory,
        RedisControlEnvelopeCodec controlCodec,
        RedisCoordinatorLeaseCodec leaseCodec,
        Supplier<UUID> storageUuidSupplier
    ) {
        this.connectionFactory = Objects.requireNonNull(
            connectionFactory,
            "connectionFactory must not be null"
        );
        this.controlCodec = Objects.requireNonNull(
            controlCodec,
            "controlCodec must not be null"
        );
        this.leaseCodec = Objects.requireNonNull(
            leaseCodec,
            "leaseCodec must not be null"
        );
        this.storageUuidSupplier = Objects.requireNonNull(
            storageUuidSupplier,
            "storageUuidSupplier must not be null"
        );
    }

    /**
     * Atomically creates INITIALIZING control, FREE lease and a zero lease counter.
     *
     * <p>The supplied primary snapshot must carry the trusted keyspace selected by its caller.
     * Its physical run ID is rechecked inside the same write-routed EVAL that performs the
     * installation. Existing metadata is never overwritten or rebound. A
     * {@link Outcome#PRESENT_REQUIRES_VALIDATION} result only reports bounded structural
     * presence; the caller must atomically probe and classify the triplet before using it.</p>
     */
    Outcome initialize(
        SecurityDatasetIdentity trustedIdentity,
        RedisPrimarySnapshot expectedPrimary
    ) {
        Request request = prepareRequest(trustedIdentity, expectedPrimary);
        RedisConnection connection = acquireConnection();
        Throwable pendingFailure = null;
        try {
            Object result = execute(connection, request);
            return decodeOutcome(result);
        } catch (RuntimeException | Error failure) {
            pendingFailure = failure;
            throw failure;
        } finally {
            closeConnection(connection, pendingFailure);
        }
    }

    private Request prepareRequest(
        SecurityDatasetIdentity trustedIdentity,
        RedisPrimarySnapshot expectedPrimary
    ) {
        SecurityDatasetIdentity identity = RedisWireProtocol.requireVersionOne(trustedIdentity);
        Objects.requireNonNull(expectedPrimary, "expectedPrimary must not be null");
        RedisDatasetKeyspace keyspace = expectedPrimary.requestedKeyspace().orElseThrow(
            () -> new IllegalArgumentException(
                "expectedPrimary must be bound to its trusted requested keyspace"
            )
        );
        expectedPrimary.bindRequestedKeyspace(keyspace);
        if (!keyspace.matchesSecurityDatasetId(identity.getSecurityDatasetId())) {
            throw new IllegalArgumentException(
                "trustedIdentity must exactly match the requested Redis keyspace"
            );
        }
        expectedPrimary.controlEnvelope().ifPresent(control -> {
            if (!identity.equals(control.getIdentity())) {
                throw new IllegalArgumentException(
                    "trustedIdentity must exactly match the observed control identity"
                );
            }
        });

        RedisIncarnation incarnation = new RedisIncarnation(
            expectedPrimary.observation().runId(),
            storageUuidSupplier.get()
        );
        RedisControlEnvelope control = new RedisControlEnvelope(
            identity,
            incarnation,
            0,
            null,
            RedisControlState.INITIALIZING
        );
        RedisCoordinatorLease.Unverified candidate = RedisCoordinatorLease.unverified(
            identity,
            keyspace.datasetHash(),
            incarnation,
            0,
            RedisCoordinatorLease.State.FREE,
            0,
            0,
            null,
            null,
            0,
            0
        );
        RedisCoordinatorLease.Verified lease = RedisCoordinatorLease.verifyFreeForContext(
            candidate,
            identity,
            keyspace.datasetHash(),
            incarnation,
            0,
            0,
            0
        );

        List<byte[]> arguments = new ArrayList<>(EXPECTED_ARGUMENT_COUNT);
        appendControlValues(arguments, controlCodec.encode(control));
        appendLeaseValues(arguments, leaseCodec.encode(lease));
        arguments.add(ascii("0"));
        if (arguments.size() != EXPECTED_ARGUMENT_COUNT) {
            throw new IllegalStateException("bootstrap initialization argument shape is invalid");
        }
        return new Request(
            new byte[][] {
                utf8(keyspace.controlKey()),
                utf8(keyspace.leaseKey()),
                utf8(keyspace.leaseCounterKey())
            },
            arguments.toArray(byte[][]::new)
        );
    }

    private void appendControlValues(List<byte[]> target, Map<String, String> values) {
        for (String field : RedisControlEnvelopeCodec.orderedFields()) {
            target.add(utf8(values.get(field)));
        }
    }

    private void appendLeaseValues(List<byte[]> target, Map<String, String> values) {
        for (String field : RedisCoordinatorLeaseCodec.orderedFields()) {
            target.add(RedisCoordinatorLeaseCodec.encodeBoundedUtf8Value(
                field,
                values.get(field)
            ));
        }
    }

    private RedisConnection acquireConnection() {
        final RedisConnection connection;
        try {
            connection = connectionFactory.getConnection();
        } catch (RuntimeException failure) {
            throw new RedisProtocolUnavailableException(
                "Failed to obtain an authoritative Redis connection for bootstrap initialization.",
                failure
            );
        }
        if (connection == null) {
            throw new RedisProtocolUnavailableException(
                "RedisConnectionFactory returned no connection for bootstrap initialization."
            );
        }
        return connection;
    }

    private static Object execute(RedisConnection connection, Request request) {
        try {
            RedisScriptingCommands commands = connection.scriptingCommands();
            if (commands == null) {
                throw new RedisProtocolUnavailableException(
                    "RedisConnection returned no scripting commands for bootstrap initialization."
                );
            }
            byte[][] keysAndArguments = new byte[
                request.keys().length + request.arguments().length
            ][];
            System.arraycopy(
                request.keys(),
                0,
                keysAndArguments,
                0,
                request.keys().length
            );
            System.arraycopy(
                request.arguments(),
                0,
                keysAndArguments,
                request.keys().length,
                request.arguments().length
            );
            Object result = commands.eval(
                INITIALIZE_IF_ABSENT,
                ReturnType.VALUE,
                KEY_COUNT,
                keysAndArguments
            );
            if (result == null) {
                throw new RedisProtocolUnavailableException(
                    "Redis bootstrap initialization script returned no result; " +
                        "the installation outcome is ambiguous."
                );
            }
            return result;
        } catch (RedisProtocolException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RedisProtocolUnavailableException(
                "Redis bootstrap initialization script failed; " +
                    "the installation outcome is ambiguous.",
                failure
            );
        }
    }

    private static Outcome decodeOutcome(Object result) {
        if (matches(result, INITIALIZED)) {
            return Outcome.INITIALIZED;
        }
        if (matches(result, PRESENT_REQUIRES_VALIDATION)) {
            return Outcome.PRESENT_REQUIRES_VALIDATION;
        }
        if (result instanceof byte[] bytes) {
            for (RedisBootstrapInitializationException.Reason reason :
                RedisBootstrapInitializationException.Reason.values()) {
                if (reason != RedisBootstrapInitializationException.Reason
                    .INVALID_SCRIPT_RESPONSE
                    && Arrays.equals(bytes, ascii(reason.name()))) {
                    throw new RedisBootstrapInitializationException(reason);
                }
            }
        }
        throw new RedisBootstrapInitializationException(
            RedisBootstrapInitializationException.Reason.INVALID_SCRIPT_RESPONSE
        );
    }

    private static void closeConnection(
        RedisConnection connection,
        Throwable pendingFailure
    ) {
        try {
            connection.close();
        } catch (RuntimeException closeFailure) {
            RedisProtocolUnavailableException unavailable =
                new RedisProtocolUnavailableException(
                    "Failed to close the Redis bootstrap initialization connection.",
                    closeFailure
                );
            if (pendingFailure != null) {
                pendingFailure.addSuppressed(unavailable);
            } else {
                throw unavailable;
            }
        }
    }

    private static byte[] buildInitializeScript() {
        List<String> controlFields = RedisControlEnvelopeCodec.orderedFields();
        List<String> leaseFields = RedisCoordinatorLeaseCodec.orderedFields();

        int controlDataset = argumentIndex(
            CONTROL_ARGUMENT_OFFSET,
            controlFields,
            RedisControlEnvelopeCodec.FIELD_DATASET_ID
        );
        int controlProtocol = argumentIndex(
            CONTROL_ARGUMENT_OFFSET,
            controlFields,
            RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION
        );
        int controlRunId = argumentIndex(
            CONTROL_ARGUMENT_OFFSET,
            controlFields,
            RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID
        );
        int controlStorageUuid = argumentIndex(
            CONTROL_ARGUMENT_OFFSET,
            controlFields,
            RedisControlEnvelopeCodec.FIELD_STORAGE_UUID
        );
        int controlCounter = argumentIndex(
            CONTROL_ARGUMENT_OFFSET,
            controlFields,
            RedisControlEnvelopeCodec.FIELD_COUNTER
        );
        int controlSnapshot = argumentIndex(
            CONTROL_ARGUMENT_OFFSET,
            controlFields,
            RedisControlEnvelopeCodec.FIELD_ACTIVE_SNAPSHOT_ID
        );
        int controlState = argumentIndex(
            CONTROL_ARGUMENT_OFFSET,
            controlFields,
            RedisControlEnvelopeCodec.FIELD_STATE
        );

        int leaseSchema = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_SCHEMA_VERSION
        );
        int leaseDataset = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_DATASET_ID
        );
        int leaseProtocol = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_PROTOCOL_VERSION
        );
        int leaseRunId = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID
        );
        int leaseStorageUuid = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_STORAGE_UUID
        );
        int leaseBoundCounter = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER
        );
        int leaseState = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_STATE
        );
        int leaseRevision = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_REVISION
        );
        int leaseSequence = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE
        );
        int leaseOwner = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID
        );
        int leaseAcquisition = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID
        );
        int leaseIssued = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS
        );
        int leaseExpires = argumentIndex(
            LEASE_ARGUMENT_OFFSET,
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS
        );

        String script = "redis.replicate_commands()\n" +
            "local MAX_SAFE = 9007199254740991\n" +
            "local MAX_INFO = " + RedisBootstrapTripletCodec.MAX_INFO_SECTION_BYTES + "\n" +
            "local controlNames = " + luaStringTable(controlFields) + "\n" +
            "local controlLimits = " + luaLimitTable(controlFields, true) + "\n" +
            "local leaseNames = " + luaStringTable(leaseFields) + "\n" +
            "local leaseLimits = " + luaLimitTable(leaseFields, false) + "\n" +
            "if #KEYS ~= 3 or #ARGV ~= " + EXPECTED_ARGUMENT_COUNT + " then\n" +
            "  return 'INVALID_REQUEST'\n" +
            "end\n" +
            luaHelpers() +
            "if not argumentsWithinLimits(controlNames, controlLimits, " +
                CONTROL_ARGUMENT_OFFSET + ")\n" +
            "    or not argumentsWithinLimits(leaseNames, leaseLimits, " +
                LEASE_ARGUMENT_OFFSET + ") then\n" +
            "  return 'INVALID_REQUEST'\n" +
            "end\n" +
            "if ARGV[" + controlProtocol + "] ~= '1'\n" +
            "    or ARGV[" + controlCounter + "] ~= '0'\n" +
            "    or ARGV[" + controlSnapshot + "] ~= ''\n" +
            "    or ARGV[" + controlState + "] ~= 'INITIALIZING'\n" +
            "    or ARGV[" + leaseSchema + "] ~= '1'\n" +
            "    or ARGV[" + leaseDataset + "] ~= ARGV[" + controlDataset + "]\n" +
            "    or ARGV[" + leaseProtocol + "] ~= ARGV[" + controlProtocol + "]\n" +
            "    or ARGV[" + leaseRunId + "] ~= ARGV[" + controlRunId + "]\n" +
            "    or ARGV[" + leaseStorageUuid + "] ~= ARGV[" +
                controlStorageUuid + "]\n" +
            "    or ARGV[" + leaseBoundCounter + "] ~= ARGV[" + controlCounter + "]\n" +
            "    or ARGV[" + leaseState + "] ~= 'FREE'\n" +
            "    or ARGV[" + leaseRevision + "] ~= '0'\n" +
            "    or ARGV[" + leaseSequence + "] ~= '0'\n" +
            "    or ARGV[" + leaseOwner + "] ~= ''\n" +
            "    or ARGV[" + leaseAcquisition + "] ~= ''\n" +
            "    or ARGV[" + leaseIssued + "] ~= '0'\n" +
            "    or ARGV[" + leaseExpires + "] ~= '0'\n" +
            "    or ARGV[" + COUNTER_ARGUMENT_INDEX + "] ~= '0' then\n" +
            "  return 'INVALID_REQUEST'\n" +
            "end\n" +
            "local server = redis.call('INFO', 'server')\n" +
            "local replication = redis.call('INFO', 'replication')\n" +
            "local cluster = redis.call('INFO', 'cluster')\n" +
            "local memory = redis.call('INFO', 'memory')\n" +
            "if type(server) ~= 'string' or type(replication) ~= 'string'\n" +
            "    or type(cluster) ~= 'string' or type(memory) ~= 'string'\n" +
            "    or #server == 0 or #server > MAX_INFO\n" +
            "    or #replication == 0 or #replication > MAX_INFO\n" +
            "    or #cluster == 0 or #cluster > MAX_INFO\n" +
            "    or #memory == 0 or #memory > MAX_INFO then\n" +
            "  return 'INFO_BOUNDS_INVALID'\n" +
            "end\n" +
            "local currentRunId = infoValue(server, 'run_id')\n" +
            "local role = infoValue(replication, 'role')\n" +
            "local connectedReplicas = infoValue(replication, 'connected_slaves')\n" +
            "local clusterEnabled = infoValue(cluster, 'cluster_enabled')\n" +
            "local maxmemoryPolicy = infoValue(memory, 'maxmemory_policy')\n" +
            "if currentRunId ~= ARGV[" + controlRunId + "] or role ~= 'master'\n" +
            "    or connectedReplicas ~= '0' or clusterEnabled ~= '0' then\n" +
            "  return 'TOPOLOGY_CHANGED'\n" +
            "end\n" +
            "if maxmemoryPolicy ~= 'noeviction' then\n" +
            "  return 'CONFIGURATION_INVALID'\n" +
            "end\n" +
            "local keyTypes = {redisKeyType(KEYS[1]), redisKeyType(KEYS[2]), " +
                "redisKeyType(KEYS[3])}\n" +
            "local keyTtls = {redis.call('PTTL', KEYS[1]), redis.call('PTTL', KEYS[2]), " +
                "redis.call('PTTL', KEYS[3])}\n" +
            "local expectedTypes = {'hash', 'hash', 'string'}\n" +
            "local typeFailures = {'CONTROL_TYPE_INVALID', 'LEASE_TYPE_INVALID', " +
                "'COUNTER_TYPE_INVALID'}\n" +
            "local ttlFailures = {'CONTROL_TTL_INVALID', 'LEASE_TTL_INVALID', " +
                "'COUNTER_TTL_INVALID'}\n" +
            "local present = 0\n" +
            "for index = 1, 3 do\n" +
            "  if keyTypes[index] == 'none' then\n" +
            "    if keyTtls[index] ~= -2 then return ttlFailures[index] end\n" +
            "  else\n" +
            "    if keyTypes[index] ~= expectedTypes[index] then return typeFailures[index] end\n" +
            "    if keyTtls[index] ~= -1 then return ttlFailures[index] end\n" +
            "    present = present + 1\n" +
            "  end\n" +
            "end\n" +
            "if present > 0 and present < 3 then return 'PARTIAL_METADATA' end\n" +
            "if present == 3 then\n" +
            "  if not exactBoundedHash(KEYS[1], controlNames, controlLimits) then\n" +
            "    return 'CONTROL_WIRE_INVALID'\n" +
            "  end\n" +
            "  if not exactBoundedHash(KEYS[2], leaseNames, leaseLimits) then\n" +
            "    return 'LEASE_WIRE_INVALID'\n" +
            "  end\n" +
            "  local counterLength = redis.call('STRLEN', KEYS[3])\n" +
            "  if counterLength == 0 or counterLength > 16\n" +
            "      or canonicalNonNegative(redis.call('GET', KEYS[3])) == nil then\n" +
            "    return 'COUNTER_WIRE_INVALID'\n" +
            "  end\n" +
            "  return 'PRESENT_REQUIRES_VALIDATION'\n" +
            "end\n" +
            "local counterWrite = redis.pcall('SETNX', KEYS[3], ARGV[" +
                COUNTER_ARGUMENT_INDEX + "])\n" +
            "if isErrorReply(counterWrite) or counterWrite ~= 1 then\n" +
            "  return rollbackCreatedKeys()\n" +
            "end\n" +
            "local leaseWrite = writeHash(KEYS[2], leaseNames, " +
                LEASE_ARGUMENT_OFFSET + ")\n" +
            "if isErrorReply(leaseWrite) or leaseWrite ~= #leaseNames then\n" +
            "  return rollbackCreatedKeys()\n" +
            "end\n" +
            "local controlWrite = writeHash(KEYS[1], controlNames, " +
                CONTROL_ARGUMENT_OFFSET + ")\n" +
            "if isErrorReply(controlWrite) or controlWrite ~= #controlNames then\n" +
            "  return rollbackCreatedKeys()\n" +
            "end\n" +
            "return 'INITIALIZED'\n";
        return script.getBytes(StandardCharsets.US_ASCII);
    }

    private static String luaHelpers() {
        return "local function decimal(value)\n" +
            "  return string.format('%.0f', value)\n" +
            "end\n" +
            "local function canonicalNonNegative(value)\n" +
            "  if type(value) ~= 'string' or #value == 0 or #value > 16 then return nil end\n" +
            "  if value ~= '0' and string.match(value, '^[1-9][0-9]*$') == nil then\n" +
            "    return nil\n" +
            "  end\n" +
            "  local parsed = tonumber(value)\n" +
            "  if parsed == nil or parsed < 0 or parsed > MAX_SAFE then return nil end\n" +
            "  if decimal(parsed) ~= value then return nil end\n" +
            "  return parsed\n" +
            "end\n" +
            "local function infoValue(section, name)\n" +
            "  local framed = '\\r\\n' .. section\n" +
            "  return string.match(framed, '\\r\\n' .. name .. ':([^\\r\\n]*)\\r\\n')\n" +
            "end\n" +
            "local function redisKeyType(key)\n" +
            "  local reply = redis.call('TYPE', key)\n" +
            "  if type(reply) == 'table' then return reply['ok'] end\n" +
            "  return reply\n" +
            "end\n" +
            "local function isErrorReply(reply)\n" +
            "  return type(reply) == 'table' and reply.err ~= nil\n" +
            "end\n" +
            "local function argumentsWithinLimits(names, limits, offset)\n" +
            "  for index = 1, #names do\n" +
            "    local value = ARGV[offset + index]\n" +
            "    if type(value) ~= 'string' or #value > limits[index] then return false end\n" +
            "  end\n" +
            "  return true\n" +
            "end\n" +
            "local function exactBoundedHash(key, names, limits)\n" +
            "  if redis.call('HLEN', key) ~= #names then return false end\n" +
            "  for index, name in ipairs(names) do\n" +
            "    if redis.call('HEXISTS', key, name) ~= 1\n" +
            "        or redis.call('HSTRLEN', key, name) > limits[index] then\n" +
            "      return false\n" +
            "    end\n" +
            "  end\n" +
            "  return true\n" +
            "end\n" +
            "local function writeHash(key, names, offset)\n" +
            "  local values = {}\n" +
            "  for index, name in ipairs(names) do\n" +
            "    values[#values + 1] = name\n" +
            "    values[#values + 1] = ARGV[offset + index]\n" +
            "  end\n" +
            "  return redis.pcall('HSET', key, unpack(values))\n" +
            "end\n" +
            "local function rollbackCreatedKeys()\n" +
            "  local cleanupSucceeded = true\n" +
            "  for index = 1, 3 do\n" +
            "    local deletion = redis.pcall('DEL', KEYS[index])\n" +
            "    if isErrorReply(deletion) then cleanupSucceeded = false end\n" +
            "  end\n" +
            "  for index = 1, 3 do\n" +
            "    local typeReply = redis.pcall('TYPE', KEYS[index])\n" +
            "    if isErrorReply(typeReply) then\n" +
            "      cleanupSucceeded = false\n" +
            "    else\n" +
            "      local keyType = typeReply\n" +
            "      if type(typeReply) == 'table' then keyType = typeReply['ok'] end\n" +
            "      if keyType ~= 'none' then cleanupSucceeded = false end\n" +
            "    end\n" +
            "  end\n" +
            "  if cleanupSucceeded then return 'WRITE_FAILED_ROLLED_BACK' end\n" +
            "  return 'ROLLBACK_FAILED'\n" +
            "end\n";
    }

    private static int argumentIndex(
        int argumentOffset,
        List<String> fields,
        String field
    ) {
        int fieldIndex = fields.indexOf(field);
        if (fieldIndex < 0) {
            throw new IllegalStateException("required Redis protocol field is missing");
        }
        return argumentOffset + fieldIndex + 1;
    }

    private static String luaStringTable(List<String> values) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (String value : values) {
            joiner.add("'" + value + "'");
        }
        return joiner.toString();
    }

    private static String luaLimitTable(List<String> fields, boolean control) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (String field : fields) {
            int limit = control
                ? RedisControlEnvelopeCodec.maxUtf8Bytes(field)
                : RedisCoordinatorLeaseCodec.maxUtf8Bytes(field);
            joiner.add(Integer.toString(limit));
        }
        return joiner.toString();
    }

    private static boolean matches(Object result, byte[] expected) {
        return result instanceof byte[] bytes && Arrays.equals(bytes, expected);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String value) {
        return Objects.requireNonNull(value, "value must not be null")
            .getBytes(StandardCharsets.UTF_8);
    }

    enum Outcome {
        INITIALIZED,
        /** Existing metadata was not changed and must be probed and classified by the caller. */
        PRESENT_REQUIRES_VALIDATION
    }

    private record Request(byte[][] keys, byte[][] arguments) {
    }
}
