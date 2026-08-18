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
 * Read-only access to one atomic primary/control/lease/counter structural observation.
 *
 * <p>{@code EVAL} is intentionally used as write-routed transport so replica-read configuration
 * cannot split the observation. The script performs no mutation and returns only bounded metadata.
 * Its result conveys no bootstrap, restore, rebind or lease authority.</p>
 */
final class StrictRedisBootstrapTripletProbe {

    private static final byte[] TRIPLET_SCRIPT = buildTripletScript();

    private final RedisConnectionFactory connectionFactory;
    private final RedisBootstrapTripletCodec codec;

    StrictRedisBootstrapTripletProbe(RedisConnectionFactory connectionFactory) {
        this(connectionFactory, new RedisBootstrapTripletCodec());
    }

    StrictRedisBootstrapTripletProbe(
        RedisConnectionFactory connectionFactory,
        RedisBootstrapTripletCodec codec
    ) {
        this.connectionFactory = Objects.requireNonNull(
            connectionFactory,
            "connectionFactory must not be null"
        );
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    RedisBootstrapTripletObservation readTriplet(RedisDatasetKeyspace requestedKeyspace) {
        Objects.requireNonNull(requestedKeyspace, "requestedKeyspace must not be null");

        RedisConnection connection = acquireConnection();
        Throwable pendingFailure = null;
        try {
            Object rawResult = execute(connection, requestedKeyspace);
            return codec.decode(rawResult, requestedKeyspace);
        } catch (RuntimeException | Error failure) {
            pendingFailure = failure;
            throw failure;
        } finally {
            closeConnection(connection, pendingFailure);
        }
    }

    private RedisConnection acquireConnection() {
        final RedisConnection connection;
        try {
            connection = connectionFactory.getConnection();
        } catch (RuntimeException failure) {
            throw new RedisProtocolUnavailableException(
                "Failed to obtain an authoritative Redis connection for metadata observation.",
                failure
            );
        }
        if (connection == null) {
            throw new RedisProtocolUnavailableException(
                "RedisConnectionFactory returned no connection for metadata observation."
            );
        }
        return connection;
    }

    private static Object execute(
        RedisConnection connection,
        RedisDatasetKeyspace requestedKeyspace
    ) {
        try {
            RedisScriptingCommands scriptingCommands = connection.scriptingCommands();
            if (scriptingCommands == null) {
                throw new RedisProtocolUnavailableException(
                    "RedisConnection returned no scripting commands for metadata observation."
                );
            }
            Object rawResult = scriptingCommands.eval(
                TRIPLET_SCRIPT,
                ReturnType.MULTI,
                3,
                utf8(requestedKeyspace.controlKey()),
                utf8(requestedKeyspace.leaseKey()),
                utf8(requestedKeyspace.leaseCounterKey())
            );
            if (rawResult == null) {
                throw new RedisProtocolUnavailableException(
                    "Redis bootstrap triplet script returned no result."
                );
            }
            return rawResult;
        } catch (RedisProtocolException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RedisProtocolUnavailableException(
                "Redis bootstrap triplet script failed.",
                failure
            );
        }
    }

    private static byte[] buildTripletScript() {
        String controlNames = luaStrings(RedisControlEnvelopeCodec.orderedFields());
        String controlLimits = luaLimits(
            RedisControlEnvelopeCodec.orderedFields(),
            true
        );
        String leaseNames = luaStrings(RedisCoordinatorLeaseCodec.orderedFields());
        String leaseLimits = luaLimits(
            RedisCoordinatorLeaseCodec.orderedFields(),
            false
        );

        String script = "local MAX_INFO = " +
            RedisBootstrapTripletCodec.MAX_INFO_SECTION_BYTES + "\n" +
            "local controlNames = " + controlNames + "\n" +
            "local controlLimits = " + controlLimits + "\n" +
            "local leaseNames = " + leaseNames + "\n" +
            "local leaseLimits = " + leaseLimits + "\n" +
            "local function redisKeyType(key)\n" +
            "  local reply = redis.call('TYPE', key)\n" +
            "  if type(reply) == 'table' then return reply['ok'] end\n" +
            "  return reply\n" +
            "end\n" +
            "local function readHash(key, names, limits)\n" +
            "  local keyType = redisKeyType(key)\n" +
            "  local ttl = redis.call('PTTL', key)\n" +
            "  if keyType == 'none' then return {keyType, ttl, 0, {}, {}} end\n" +
            "  if keyType ~= 'hash' or ttl ~= -1 then\n" +
            "    return {keyType, ttl, -1, {}, {}}\n" +
            "  end\n" +
            "  local fieldCount = redis.call('HLEN', key)\n" +
            "  if fieldCount ~= #names then return {keyType, ttl, fieldCount, {}, {}} end\n" +
            "  local lengths = {}\n" +
            "  local withinLimits = true\n" +
            "  for index, name in ipairs(names) do\n" +
            "    local length = redis.call('HSTRLEN', key, name)\n" +
            "    lengths[index] = length\n" +
            "    if length > limits[index] then withinLimits = false end\n" +
            "  end\n" +
            "  local values = {}\n" +
            "  if withinLimits then values = redis.call('HMGET', key, unpack(names)) end\n" +
            "  return {keyType, ttl, fieldCount, lengths, values}\n" +
            "end\n" +
            "local function readCounter(key)\n" +
            "  local keyType = redisKeyType(key)\n" +
            "  local ttl = redis.call('PTTL', key)\n" +
            "  if keyType == 'none' then return {keyType, ttl, 0, false} end\n" +
            "  if keyType ~= 'string' or ttl ~= -1 then\n" +
            "    return {keyType, ttl, -1, false}\n" +
            "  end\n" +
            "  local length = redis.call('STRLEN', key)\n" +
            "  if length == 0 or length > 16 then return {keyType, ttl, length, false} end\n" +
            "  return {keyType, ttl, length, redis.call('GET', key)}\n" +
            "end\n" +
            "if #KEYS ~= 3 then return {'REQUEST_SHAPE_INVALID'} end\n" +
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
            "  return {'INFO_BOUNDS_INVALID'}\n" +
            "end\n" +
            "return {'OK', server, replication, cluster, memory,\n" +
            "  readHash(KEYS[1], controlNames, controlLimits),\n" +
            "  readHash(KEYS[2], leaseNames, leaseLimits),\n" +
            "  readCounter(KEYS[3])}\n";
        return script.getBytes(StandardCharsets.US_ASCII);
    }

    private static String luaStrings(List<String> values) {
        StringJoiner result = new StringJoiner(", ", "{", "}");
        for (String value : values) {
            result.add("'" + value + "'");
        }
        return result.toString();
    }

    private static String luaLimits(List<String> fields, boolean control) {
        StringJoiner result = new StringJoiner(", ", "{", "}");
        for (String field : fields) {
            int maximum = control
                ? RedisControlEnvelopeCodec.maxUtf8Bytes(field)
                : RedisCoordinatorLeaseCodec.maxUtf8Bytes(field);
            result.add(Integer.toString(maximum));
        }
        return result.toString();
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void closeConnection(RedisConnection connection, Throwable pendingFailure) {
        try {
            connection.close();
        } catch (RuntimeException closeFailure) {
            RedisProtocolUnavailableException unavailable = new RedisProtocolUnavailableException(
                "Failed to close the authoritative Redis metadata connection.",
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
