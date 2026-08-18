package com.nomendi6.orgsec.storage.redis.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

/** Redis 6 Lua transitions for the explicitly non-replicated standalone lease profile. */
final class RedisStandaloneCoordinatorLeaseScripts {

    static final long MAX_LEASE_DURATION_MILLIS = 300_000L;
    static final int MAX_INFO_SECTION_BYTES = 64 * 1024;

    private static final int CONTROL_ARGUMENT_OFFSET = 2;
    private static final int LEASE_ARGUMENT_OFFSET =
        CONTROL_ARGUMENT_OFFSET + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;

    private static final byte[] ACQUIRE = ascii(buildAcquireScript());
    private static final byte[] RENEW = ascii(buildRenewScript());
    private static final byte[] RELEASE = ascii(buildReleaseScript());
    private static final byte[] VERIFY = ascii(buildVerifyScript());

    private RedisStandaloneCoordinatorLeaseScripts() {
    }

    static byte[] acquire() {
        return ACQUIRE.clone();
    }

    static byte[] renew() {
        return RENEW.clone();
    }

    static byte[] release() {
        return RELEASE.clone();
    }

    static byte[] verify() {
        return VERIFY.clone();
    }

    private static String buildAcquireScript() {
        int argumentCount = LEASE_ARGUMENT_OFFSET + 3;
        int durationIndex = LEASE_ARGUMENT_OFFSET + 1;
        int ownerIndex = LEASE_ARGUMENT_OFFSET + 2;
        int acquisitionIndex = LEASE_ARGUMENT_OFFSET + 3;
        return commonPrelude(argumentCount) +
            "local duration = canonicalPositive(ARGV[" + durationIndex + "])\n" +
            "local ownerSessionId = ARGV[" + ownerIndex + "]\n" +
            "local acquisitionId = ARGV[" + acquisitionIndex + "]\n" +
            "if duration == nil or duration > MAX_DURATION " +
                "or not canonicalUuidV4(ownerSessionId) " +
                "or not canonicalUuidV4(acquisitionId) then\n" +
            "  return {'INVALID_REQUEST'}\n" +
            "end\n" +
            "if lease.state == 'ACTIVE' and now < lease.expires then\n" +
            "  if now < lease.issued then return {'CLOCK_REGRESSED'} end\n" +
            "  if counter ~= lease.sequence then return {'LEASE_CORRUPT'} end\n" +
            "  return {'BUSY'}\n" +
            "end\n" +
            "if lease.revision == MAX_SAFE then return {'REVISION_EXHAUSTED'} end\n" +
            "if counter == MAX_SAFE then return {'COUNTER_EXHAUSTED'} end\n" +
            "if duration > MAX_SAFE - now then return {'CLOCK_REGRESSED'} end\n" +
            "local newRevision = lease.revision + 1\n" +
            "local newSequence = counter + 1\n" +
            "local newExpiry = now + duration\n" +
            "local newRevisionText = decimal(newRevision)\n" +
            "local newSequenceText = decimal(newSequence)\n" +
            "local nowText = decimal(now)\n" +
            "local newExpiryText = decimal(newExpiry)\n" +
            "redis.call('INCR', KEYS[3])\n" +
            "redis.call('HSET', KEYS[2],\n" +
            "  'state', 'ACTIVE',\n" +
            "  'revision', newRevisionText,\n" +
            "  'fencingSequence', newSequenceText,\n" +
            "  'ownerSessionId', ownerSessionId,\n" +
            "  'acquisitionId', acquisitionId,\n" +
            "  'issuedAtRedisMillis', nowText,\n" +
            "  'expiresAtRedisMillis', newExpiryText)\n" +
            "return {'ACQUIRED', nowText, newRevisionText, newSequenceText, newExpiryText}\n";
    }

    private static String buildRenewScript() {
        int argumentCount = LEASE_ARGUMENT_OFFSET +
            RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT + 1;
        int durationIndex = argumentCount;
        return commonPrelude(argumentCount) +
            exactExpectedLease() +
            "if lease.state ~= 'ACTIVE' then return {'LEASE_LOST'} end\n" +
            "if counter ~= lease.sequence then return {'LEASE_CORRUPT'} end\n" +
            "if now < lease.issued then return {'CLOCK_REGRESSED'} end\n" +
            "if now >= lease.expires then return {'LEASE_LOST'} end\n" +
            "local duration = canonicalPositive(ARGV[" + durationIndex + "])\n" +
            "if duration == nil or duration > MAX_DURATION then\n" +
            "  return {'INVALID_REQUEST'}\n" +
            "end\n" +
            "if lease.revision == MAX_SAFE then return {'REVISION_EXHAUSTED'} end\n" +
            "if duration > MAX_SAFE - now then return {'CLOCK_REGRESSED'} end\n" +
            "local newExpiry = now + duration\n" +
            "if newExpiry < lease.expires then return {'CLOCK_REGRESSED'} end\n" +
            "local newRevision = lease.revision + 1\n" +
            "local newRevisionText = decimal(newRevision)\n" +
            "local nowText = decimal(now)\n" +
            "local newExpiryText = decimal(newExpiry)\n" +
            "redis.call('HSET', KEYS[2],\n" +
            "  'revision', newRevisionText,\n" +
            "  'expiresAtRedisMillis', newExpiryText)\n" +
            "return {'RENEWED', nowText, newRevisionText, newExpiryText}\n";
    }

    private static String buildReleaseScript() {
        int argumentCount = LEASE_ARGUMENT_OFFSET +
            RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT;
        return commonPrelude(argumentCount) +
            exactExpectedLease() +
            "if lease.state ~= 'ACTIVE' then return {'LEASE_LOST'} end\n" +
            "if counter ~= lease.sequence then return {'LEASE_CORRUPT'} end\n" +
            "if lease.revision == MAX_SAFE then return {'REVISION_EXHAUSTED'} end\n" +
            "local newRevision = lease.revision + 1\n" +
            "local newRevisionText = decimal(newRevision)\n" +
            "local nowText = decimal(now)\n" +
            "redis.call('HSET', KEYS[2],\n" +
            "  'state', 'FREE',\n" +
            "  'revision', newRevisionText,\n" +
            "  'ownerSessionId', '',\n" +
            "  'acquisitionId', '',\n" +
            "  'issuedAtRedisMillis', '0',\n" +
            "  'expiresAtRedisMillis', '0')\n" +
            "return {'RELEASED', nowText, newRevisionText}\n";
    }

    private static String buildVerifyScript() {
        int argumentCount = LEASE_ARGUMENT_OFFSET +
            RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT;
        return commonPrelude(argumentCount) +
            exactExpectedLease() +
            "if counter ~= lease.sequence then return {'LEASE_CORRUPT'} end\n" +
            "if lease.state == 'ACTIVE' then\n" +
            "  if now < lease.issued then return {'CLOCK_REGRESSED'} end\n" +
            "  if now >= lease.expires then return {'LEASE_LOST'} end\n" +
            "end\n" +
            "return {'MATCH', decimal(now)}\n";
    }

    private static String exactExpectedLease() {
        return "for index = 1, #leaseNames do\n" +
            "  if lease.values[index] ~= ARGV[" + LEASE_ARGUMENT_OFFSET +
                " + index] then\n" +
            "    return {'LEASE_LOST'}\n" +
            "  end\n" +
            "end\n";
    }

    private static String commonPrelude(int expectedArgumentCount) {
        List<String> controlFields = RedisControlEnvelopeCodec.orderedFields();
        List<String> leaseFields = RedisCoordinatorLeaseCodec.orderedFields();
        int controlPrimaryRunIdIndex = luaIndex(
            controlFields,
            RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID
        );
        String controlNames = luaStringTable(controlFields);
        String controlLimits = luaLimitTable(controlFields, true);
        String leaseNames = luaStringTable(leaseFields);
        String leaseLimits = luaLimitTable(leaseFields, false);

        return "redis.replicate_commands()\n" +
            "local MAX_SAFE = 9007199254740991\n" +
            "local MAX_DURATION = " + MAX_LEASE_DURATION_MILLIS + "\n" +
            "local MAX_INFO = " + MAX_INFO_SECTION_BYTES + "\n" +
            "local controlNames = " + controlNames + "\n" +
            "local controlLimits = " + controlLimits + "\n" +
            "local leaseNames = " + leaseNames + "\n" +
            "local leaseLimits = " + leaseLimits + "\n" +
            "if #KEYS ~= 3 or #ARGV ~= " + expectedArgumentCount + " then\n" +
            "  return {'INVALID_REQUEST'}\n" +
            "end\n" +
            luaHelpers() +
            "if not topologyMatches(ARGV[1]) then return {'TOPOLOGY_CHANGED'} end\n" +
            "local controlValues = readExactHash(KEYS[1], controlNames, controlLimits)\n" +
            "if controlValues == nil then return {'CONTROL_CHANGED'} end\n" +
            "for index = 1, #controlNames do\n" +
            "  if controlValues[index] ~= ARGV[" + CONTROL_ARGUMENT_OFFSET +
                " + index] then\n" +
            "    return {'CONTROL_CHANGED'}\n" +
            "  end\n" +
            "end\n" +
            "if controlValues[" + controlPrimaryRunIdIndex +
                "] ~= ARGV[1] then return {'CONTROL_CHANGED'} end\n" +
            "local leaseValues = readExactHash(KEYS[2], leaseNames, leaseLimits)\n" +
            "if leaseValues == nil then return {'LEASE_CORRUPT'} end\n" +
            "local lease = validateLease(leaseValues, controlValues, ARGV[2])\n" +
            "if lease == nil then return {'LEASE_CORRUPT'} end\n" +
            "local counterText, counter = readCounter(KEYS[3])\n" +
            "if counter == nil or counter < lease.sequence then\n" +
            "  return {'LEASE_CORRUPT'}\n" +
            "end\n" +
            "local now = redisMillis()\n" +
            "if now == nil then return {'CLOCK_REGRESSED'} end\n";
    }

    private static String luaHelpers() {
        List<String> controlFields = RedisControlEnvelopeCodec.orderedFields();
        List<String> leaseFields = RedisCoordinatorLeaseCodec.orderedFields();
        int leaseSchemaIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_SCHEMA_VERSION
        );
        int leaseDatasetIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_DATASET_ID
        );
        int leaseDatasetHashIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_DATASET_HASH
        );
        int leaseProtocolIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_PROTOCOL_VERSION
        );
        int leasePrimaryRunIdIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_PRIMARY_RUN_ID
        );
        int leaseStorageUuidIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_STORAGE_UUID
        );
        int leaseBoundCounterIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_BOUND_CONTROL_COUNTER
        );
        int leaseStateIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_STATE
        );
        int leaseRevisionIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_REVISION
        );
        int leaseSequenceIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_FENCING_SEQUENCE
        );
        int leaseOwnerIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_OWNER_SESSION_ID
        );
        int leaseAcquisitionIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_ACQUISITION_ID
        );
        int leaseIssuedIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS
        );
        int leaseExpiresIndex = luaIndex(
            leaseFields,
            RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS
        );
        int controlDatasetIndex = luaIndex(
            controlFields,
            RedisControlEnvelopeCodec.FIELD_DATASET_ID
        );
        int controlProtocolIndex = luaIndex(
            controlFields,
            RedisControlEnvelopeCodec.FIELD_PROTOCOL_VERSION
        );
        int controlPrimaryRunIdIndex = luaIndex(
            controlFields,
            RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID
        );
        int controlStorageUuidIndex = luaIndex(
            controlFields,
            RedisControlEnvelopeCodec.FIELD_STORAGE_UUID
        );
        int controlCounterIndex = luaIndex(
            controlFields,
            RedisControlEnvelopeCodec.FIELD_COUNTER
        );

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
            "local function canonicalPositive(value)\n" +
            "  local parsed = canonicalNonNegative(value)\n" +
            "  if parsed == nil or parsed == 0 then return nil end\n" +
            "  return parsed\n" +
            "end\n" +
            "local function canonicalUuidV4(value)\n" +
            "  if type(value) ~= 'string' or #value ~= 36 then return false end\n" +
            "  if string.sub(value, 9, 9) ~= '-' or string.sub(value, 14, 14) ~= '-'\n" +
            "      or string.sub(value, 19, 19) ~= '-' or string.sub(value, 24, 24) ~= '-' then\n" +
            "    return false\n" +
            "  end\n" +
            "  if string.sub(value, 15, 15) ~= '4'\n" +
            "      or string.match(string.sub(value, 20, 20), '^[89ab]$') == nil then\n" +
            "    return false\n" +
            "  end\n" +
            "  local compact = string.gsub(value, '-', '')\n" +
            "  return #compact == 32 and string.match(compact, '^[0-9a-f]+$') ~= nil\n" +
            "end\n" +
            "local function redisKeyType(key)\n" +
            "  local reply = redis.call('TYPE', key)\n" +
            "  if type(reply) == 'table' then return reply['ok'] end\n" +
            "  return reply\n" +
            "end\n" +
            "local function readExactHash(key, names, limits)\n" +
            "  if redisKeyType(key) ~= 'hash' or redis.call('PTTL', key) ~= -1 then\n" +
            "    return nil\n" +
            "  end\n" +
            "  if redis.call('HLEN', key) ~= #names then return nil end\n" +
            "  for index, name in ipairs(names) do\n" +
            "    local length = redis.call('HSTRLEN', key, name)\n" +
            "    if length > limits[index] then return nil end\n" +
            "  end\n" +
            "  local values = redis.call('HMGET', key, unpack(names))\n" +
            "  for index = 1, #names do\n" +
            "    if values[index] == false then return nil end\n" +
            "  end\n" +
            "  return values\n" +
            "end\n" +
            "local function readCounter(key)\n" +
            "  if redisKeyType(key) ~= 'string' or redis.call('PTTL', key) ~= -1 then\n" +
            "    return nil, nil\n" +
            "  end\n" +
            "  local length = redis.call('STRLEN', key)\n" +
            "  if length == 0 or length > 16 then return nil, nil end\n" +
            "  local value = redis.call('GET', key)\n" +
            "  local parsed = canonicalNonNegative(value)\n" +
            "  if parsed == nil then return nil, nil end\n" +
            "  return value, parsed\n" +
            "end\n" +
            "local function infoValue(section, name)\n" +
            "  local framed = '\\r\\n' .. section\n" +
            "  return string.match(framed, '\\r\\n' .. name .. ':([^\\r\\n]*)\\r\\n')\n" +
            "end\n" +
            "local function topologyMatches(expectedRunId)\n" +
            "  local server = redis.call('INFO', 'server')\n" +
            "  local replication = redis.call('INFO', 'replication')\n" +
            "  local cluster = redis.call('INFO', 'cluster')\n" +
            "  local memory = redis.call('INFO', 'memory')\n" +
            "  if type(server) ~= 'string' or type(replication) ~= 'string'\n" +
            "      or type(cluster) ~= 'string' or type(memory) ~= 'string' then\n" +
            "    return false\n" +
            "  end\n" +
            "  if #server > MAX_INFO or #replication > MAX_INFO\n" +
            "      or #cluster > MAX_INFO or #memory > MAX_INFO then\n" +
            "    return false\n" +
            "  end\n" +
            "  return infoValue(server, 'run_id') == expectedRunId\n" +
            "    and infoValue(replication, 'role') == 'master'\n" +
            "    and infoValue(replication, 'connected_slaves') == '0'\n" +
            "    and infoValue(cluster, 'cluster_enabled') == '0'\n" +
            "    and infoValue(memory, 'maxmemory_policy') == 'noeviction'\n" +
            "end\n" +
            "local function redisMillis()\n" +
            "  local value = redis.call('TIME')\n" +
            "  if type(value) ~= 'table' or #value ~= 2 then return nil end\n" +
            "  local seconds = canonicalNonNegative(value[1])\n" +
            "  local micros = canonicalNonNegative(value[2])\n" +
            "  if seconds == nil or micros == nil or micros > 999999 then return nil end\n" +
            "  local millisPart = math.floor(micros / 1000)\n" +
            "  if seconds > (MAX_SAFE - millisPart) / 1000 then return nil end\n" +
            "  return seconds * 1000 + millisPart\n" +
            "end\n" +
            "local function validateLease(values, control, expectedDatasetHash)\n" +
            "  if values[" + leaseSchemaIndex + "] ~= '1'\n" +
            "      or values[" + leaseDatasetIndex + "] ~= control[" +
                controlDatasetIndex + "]\n" +
            "      or values[" + leaseDatasetHashIndex + "] ~= expectedDatasetHash\n" +
            "      or values[" + leaseProtocolIndex + "] ~= control[" +
                controlProtocolIndex + "]\n" +
            "      or values[" + leasePrimaryRunIdIndex + "] ~= control[" +
                controlPrimaryRunIdIndex + "]\n" +
            "      or values[" + leaseStorageUuidIndex + "] ~= control[" +
                controlStorageUuidIndex + "]\n" +
            "      or values[" + leaseBoundCounterIndex + "] ~= control[" +
                controlCounterIndex + "] then\n" +
            "    return nil\n" +
            "  end\n" +
            "  local revision = canonicalNonNegative(values[" + leaseRevisionIndex + "])\n" +
            "  local sequence = canonicalNonNegative(values[" + leaseSequenceIndex + "])\n" +
            "  local issued = canonicalNonNegative(values[" + leaseIssuedIndex + "])\n" +
            "  local expires = canonicalNonNegative(values[" + leaseExpiresIndex + "])\n" +
            "  if revision == nil or sequence == nil or issued == nil or expires == nil then\n" +
            "    return nil\n" +
            "  end\n" +
            "  if values[" + leaseStateIndex + "] == 'FREE' then\n" +
            "    if values[" + leaseOwnerIndex + "] ~= '' or values[" +
                leaseAcquisitionIndex + "] ~= '' or issued ~= 0 or expires ~= 0 then\n" +
            "      return nil\n" +
            "    end\n" +
            "  elseif values[" + leaseStateIndex + "] == 'ACTIVE' then\n" +
            "    if sequence == 0 or issued == 0 or expires <= issued\n" +
            "        or not canonicalUuidV4(values[" + leaseOwnerIndex + "])\n" +
            "        or not canonicalUuidV4(values[" + leaseAcquisitionIndex + "]) then\n" +
            "      return nil\n" +
            "    end\n" +
            "  else\n" +
            "    return nil\n" +
            "  end\n" +
            "  return {values = values, state = values[" + leaseStateIndex +
                "], revision = revision,\n" +
            "    sequence = sequence, issued = issued, expires = expires}\n" +
            "end\n";
    }

    private static int luaIndex(List<String> fields, String field) {
        int index = fields.indexOf(field);
        if (index < 0) {
            throw new IllegalStateException("required Redis protocol field is missing");
        }
        return index + 1;
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

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
