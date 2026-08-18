package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Tokenized, bounded reader for an immutable protocol-v1 Redis snapshot.
 *
 * <p>Every operation owns one connection and executes one write-routed {@code EVAL}. Opening and
 * finishing use the byte-identical {@code READ_MANIFEST_IF_CURRENT} script. Pages atomically guard
 * the exact generation and every manifest value before reading a bounded sorted-index range.
 * No method makes storage READY.</p>
 */
final class StrictRedisSnapshotReader {

    private static final int MAX_INFO_SECTION_BYTES = 64 * 1024;
    private static final byte[] READ_MANIFEST_IF_CURRENT = buildReadManifestScript();
    private static final byte[] READ_PAGE_IF_CURRENT = buildReadPageScript();

    private final RedisConnectionFactory connectionFactory;
    private final RedisSnapshotReadLimits limits;
    private final RedisControlEnvelopeCodec controlCodec;
    private final RedisSnapshotManifestCodec manifestCodec;
    private final RedisSnapshotReadResponseCodec responseCodec;
    private final RedisCanonicalSnapshotEntryVerifier entryVerifier;

    StrictRedisSnapshotReader(
        RedisConnectionFactory connectionFactory,
        RedisSnapshotReadLimits limits
    ) {
        this(
            connectionFactory,
            limits,
            new RedisControlEnvelopeCodec(),
            new RedisSnapshotManifestCodec(),
            new RedisSnapshotReadResponseCodec(),
            new RedisCanonicalSnapshotEntryVerifier()
        );
    }

    StrictRedisSnapshotReader(
        RedisConnectionFactory connectionFactory,
        RedisSnapshotReadLimits limits,
        RedisControlEnvelopeCodec controlCodec,
        RedisSnapshotManifestCodec manifestCodec,
        RedisSnapshotReadResponseCodec responseCodec
    ) {
        this(
            connectionFactory,
            limits,
            controlCodec,
            manifestCodec,
            responseCodec,
            new RedisCanonicalSnapshotEntryVerifier()
        );
    }

    StrictRedisSnapshotReader(
        RedisConnectionFactory connectionFactory,
        RedisSnapshotReadLimits limits,
        RedisControlEnvelopeCodec controlCodec,
        RedisSnapshotManifestCodec manifestCodec,
        RedisSnapshotReadResponseCodec responseCodec,
        RedisCanonicalSnapshotEntryVerifier entryVerifier
    ) {
        this.connectionFactory = Objects.requireNonNull(
            connectionFactory,
            "connectionFactory must not be null"
        );
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.controlCodec = Objects.requireNonNull(
            controlCodec,
            "controlCodec must not be null"
        );
        this.manifestCodec = Objects.requireNonNull(
            manifestCodec,
            "manifestCodec must not be null"
        );
        this.responseCodec = Objects.requireNonNull(
            responseCodec,
            "responseCodec must not be null"
        );
        this.entryVerifier = Objects.requireNonNull(
            entryVerifier,
            "entryVerifier must not be null"
        );
    }

    /**
     * Opens the exact active snapshot selected by a previously strict primary observation.
     *
     * @param generation immutable READY generation token
     * @param expectedSourceFence exact local/source fence expected at final verification
     * @return owner-thread session retaining only an unverified manifest candidate
     */
    RedisSnapshotReadSession openActive(
        RedisSnapshotGeneration generation,
        SecurityDatasetFence expectedSourceFence
    ) {
        Objects.requireNonNull(generation, "generation must not be null");
        Objects.requireNonNull(
            expectedSourceFence,
            "expectedSourceFence must not be null"
        );
        RedisWireProtocol.requireVersionOne(expectedSourceFence);
        if (!generation.identity().equals(expectedSourceFence.getIdentity())) {
            throw new IllegalArgumentException(
                "expectedSourceFence identity must exactly match generation identity"
            );
        }

        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
            generation.identity().getSecurityDatasetId()
        );
        RedisSnapshotReadResponseCodec.ManifestRead manifestRead = readManifest(
            generation,
            keyspace
        );
        enforceSnapshotLimits(manifestRead.candidate());
        RedisSnapshotReadHandle handle = new RedisSnapshotReadHandle(
            manifestRead.generation(),
            expectedSourceFence,
            generation.activeSnapshotId(),
            manifestRead.wireFields(),
            manifestRead.candidate()
        );
        return new RedisSnapshotReadSession(
            handle,
            entryVerifier,
            this::readPage,
            this::finish,
            limits.maxAccountedBytes()
        );
    }

    /**
     * Reads one exact ordinal page for the owner session's internal cursor.
     *
     * @param handle immutable opened read handle
     * @param family requested snapshot family
     * @param offset exact zero-based ordinal offset owned by the session
     * @return one bounded page
     */
    private RedisSnapshotPage readPage(
        RedisSnapshotReadHandle handle,
        RedisSnapshotFamily family,
        long offset
    ) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(family, "family must not be null");
        long familyCount = familyCount(handle.manifestCandidate(), family);
        if (offset < 0 || offset > familyCount) {
            throw new IllegalArgumentException("offset must be within the manifest family range");
        }
        if (familyCount > limits.maxEntriesPerFamily()) {
            throw failure(RedisSnapshotReadException.Reason.SNAPSHOT_LIMIT_EXCEEDED);
        }

        RedisSnapshotGeneration generation = handle.generation();
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
            generation.identity().getSecurityDatasetId()
        );
        List<byte[]> keysAndArguments = new ArrayList<>();
        keysAndArguments.add(utf8(keyspace.controlKey()));
        keysAndArguments.add(utf8(keyspace.manifestKey(handle.requestedSnapshotId())));
        keysAndArguments.add(utf8(keyspace.familyKey(handle.requestedSnapshotId(), family)));
        keysAndArguments.add(utf8(
            keyspace.familyIndexKey(handle.requestedSnapshotId(), family)
        ));
        appendControlValues(keysAndArguments, generation.controlEnvelope());
        appendManifestValues(keysAndArguments, handle.manifestWireFields());
        keysAndArguments.add(ascii(familyCount));
        keysAndArguments.add(ascii(offset));
        keysAndArguments.add(ascii(limits.pageEntries()));
        keysAndArguments.add(ascii(limits.pageKeyBytes()));
        keysAndArguments.add(ascii(limits.pagePayloadBytes()));
        keysAndArguments.add(ascii(limits.pageTotalBytes()));

        Object rawResult = execute(
            READ_PAGE_IF_CURRENT,
            4,
            keysAndArguments,
            "snapshot page"
        );
        return responseCodec.decodePage(
            rawResult,
            generation,
            family,
            offset,
            familyCount,
            limits
        );
    }

    /**
     * Atomically rechecks the generation and manifest, then crosses the contextual trust boundary.
     *
     * @param handle exact private session handle
     * @param recomputedContent canonical result built only by the session accumulators
     * @return verified generation-bound snapshot view
     */
    private RedisVerifiedSnapshotView finish(
        RedisSnapshotReadHandle handle,
        RedisSnapshotContentDigest recomputedContent
    ) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(recomputedContent, "recomputedContent must not be null");
        RedisSnapshotGeneration generation = handle.generation();
        RedisDatasetKeyspace keyspace = new RedisDatasetKeyspace(
            generation.identity().getSecurityDatasetId()
        );

        RedisSnapshotReadResponseCodec.ManifestRead finalRead = readManifest(
            generation,
            keyspace
        );
        if (!handle.manifestWireFields().equals(finalRead.wireFields())) {
            throw failure(RedisSnapshotReadException.Reason.MANIFEST_CHANGED);
        }
        enforceSnapshotLimits(finalRead.candidate());
        return RedisVerifiedSnapshotView.adopt(
            handle,
            finalRead.generation(),
            finalRead.wireFields(),
            recomputedContent
        );
    }

    private RedisSnapshotReadResponseCodec.ManifestRead readManifest(
        RedisSnapshotGeneration generation,
        RedisDatasetKeyspace keyspace
    ) {
        List<byte[]> keysAndArguments = new ArrayList<>();
        keysAndArguments.add(utf8(keyspace.controlKey()));
        keysAndArguments.add(utf8(keyspace.manifestKey(generation.activeSnapshotId())));
        appendControlValues(keysAndArguments, generation.controlEnvelope());
        Object rawResult = execute(
            READ_MANIFEST_IF_CURRENT,
            2,
            keysAndArguments,
            "snapshot manifest"
        );
        return responseCodec.decodeManifest(rawResult, generation);
    }

    private void appendControlValues(
        List<byte[]> target,
        RedisControlEnvelope controlEnvelope
    ) {
        Map<String, String> values = controlCodec.encode(controlEnvelope);
        for (String field : RedisControlEnvelopeCodec.orderedFields()) {
            target.add(utf8(values.get(field)));
        }
    }

    private void appendManifestValues(List<byte[]> target, Map<String, String> values) {
        for (String field : RedisSnapshotManifestCodec.orderedFields()) {
            String value = values.get(field);
            if (value == null) {
                throw failure(RedisSnapshotReadException.Reason.MANIFEST_CORRUPT);
            }
            try {
                target.add(RedisSnapshotManifestCodec.encodeBoundedUtf8Value(field, value));
            } catch (RedisSnapshotManifestEncodingException exception) {
                throw failure(RedisSnapshotReadException.Reason.MANIFEST_CORRUPT);
            }
        }
    }

    private void enforceSnapshotLimits(RedisSnapshotManifest.Unverified manifest) {
        long[] counts = {
            manifest.personsCount(),
            manifest.organizationsCount(),
            manifest.partyRolesCount(),
            manifest.positionRolesCount(),
            manifest.rolesCount(),
            manifest.privilegesCount()
        };
        long total = 0;
        for (long count : counts) {
            if (count > limits.maxEntriesPerFamily()) {
                throw failure(RedisSnapshotReadException.Reason.SNAPSHOT_LIMIT_EXCEEDED);
            }
            try {
                total = Math.addExact(total, count);
            } catch (ArithmeticException exception) {
                throw failure(RedisSnapshotReadException.Reason.SNAPSHOT_LIMIT_EXCEEDED);
            }
        }
        if (total > limits.maxTotalEntries()
            || manifest.accountedBytes() > limits.maxAccountedBytes()) {
            throw failure(RedisSnapshotReadException.Reason.SNAPSHOT_LIMIT_EXCEEDED);
        }
    }

    private Object execute(
        byte[] script,
        int keyCount,
        List<byte[]> keysAndArguments,
        String operation
    ) {
        RedisConnection connection = acquireConnection();
        Throwable pendingFailure = null;
        try {
            RedisScriptingCommands commands = connection.scriptingCommands();
            if (commands == null) {
                throw new RedisProtocolUnavailableException(
                    "RedisConnection returned no scripting commands."
                );
            }
            Object rawResult = commands.eval(
                script,
                ReturnType.MULTI,
                keyCount,
                keysAndArguments.toArray(byte[][]::new)
            );
            if (rawResult == null) {
                throw new RedisProtocolUnavailableException(
                    "Strict Redis " + operation + " operation returned no result."
                );
            }
            return rawResult;
        } catch (RedisProtocolException failure) {
            pendingFailure = failure;
            throw failure;
        } catch (RuntimeException failure) {
            RedisProtocolUnavailableException unavailable =
                new RedisProtocolUnavailableException(
                    "Strict Redis " + operation + " operation failed.",
                    failure
                );
            pendingFailure = unavailable;
            throw unavailable;
        } catch (Error failure) {
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

    private static long familyCount(
        RedisSnapshotManifest.Unverified manifest,
        RedisSnapshotFamily family
    ) {
        return switch (family) {
            case PERSONS -> manifest.personsCount();
            case ORGANIZATIONS -> manifest.organizationsCount();
            case PARTY_ROLES -> manifest.partyRolesCount();
            case POSITION_ROLES -> manifest.positionRolesCount();
            case ROLES -> manifest.rolesCount();
            case PRIVILEGES -> manifest.privilegesCount();
        };
    }

    private static byte[] buildReadManifestScript() {
        String controlNames = luaStringTable(RedisControlEnvelopeCodec.orderedFields());
        String controlLimits = luaLimitTable(
            RedisControlEnvelopeCodec.orderedFields(),
            true
        );
        String manifestNames = luaStringTable(RedisSnapshotManifestCodec.orderedFields());
        String manifestLimits = luaLimitTable(
            RedisSnapshotManifestCodec.orderedFields(),
            false
        );
        int primaryRunIdArgument = RedisControlEnvelopeCodec.orderedFields().indexOf(
            RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID
        ) + 1;
        String script = "-- READ_MANIFEST_IF_CURRENT\n" +
            "local controlNames = " + controlNames + "\n" +
            "local controlLimits = " + controlLimits + "\n" +
            "local manifestNames = " + manifestNames + "\n" +
            "local manifestLimits = " + manifestLimits + "\n" +
            exactHashFunction() +
            boundedHashFunction() +
            primaryValidationFunction() +
            "local server = redis.call('INFO', 'server')\n" +
            "local replication = redis.call('INFO', 'replication')\n" +
            "local cluster = redis.call('INFO', 'cluster')\n" +
            "local memory = redis.call('INFO', 'memory')\n" +
            "if string.len(server) > " + MAX_INFO_SECTION_BYTES +
                " or string.len(replication) > " + MAX_INFO_SECTION_BYTES +
                " or string.len(cluster) > " + MAX_INFO_SECTION_BYTES +
                " or string.len(memory) > " + MAX_INFO_SECTION_BYTES + " then\n" +
            "  return {'RESPONSE_CORRUPT'}\n" +
            "end\n" +
            "local control = readExactHash(KEYS[1], controlNames, controlLimits, 1, " +
                "'CONTROL_CORRUPT', 'GENERATION_CHANGED')\n" +
            "if control[1] ~= 'OK' then return {control[1]} end\n" +
            "local primaryStatus = validatePrimary(server, replication, cluster, memory, " +
                "ARGV[" + primaryRunIdArgument + "])\n" +
            "if primaryStatus ~= 'OK' then return {primaryStatus} end\n" +
            "local manifest = readBoundedHash(KEYS[2], manifestNames, manifestLimits, " +
                "'MANIFEST_CORRUPT')\n" +
            "if manifest[1] ~= 'OK' then return {manifest[1]} end\n" +
            "return {'OK', server, replication, cluster, memory, " +
                "control[4], control[2], control[3], manifest[4], manifest[2], manifest[3]}\n";
        return script.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildReadPageScript() {
        List<String> controlFields = RedisControlEnvelopeCodec.orderedFields();
        List<String> manifestFields = RedisSnapshotManifestCodec.orderedFields();
        int manifestArgumentOffset = controlFields.size() + 1;
        int numericArgumentOffset = controlFields.size() + manifestFields.size() + 1;
        int primaryRunIdArgument = controlFields.indexOf(
            RedisControlEnvelopeCodec.FIELD_PRIMARY_RUN_ID
        ) + 1;
        String script = "-- READ_PAGE_IF_CURRENT\n" +
            "local controlNames = " + luaStringTable(controlFields) + "\n" +
            "local controlLimits = " + luaLimitTable(controlFields, true) + "\n" +
            "local manifestNames = " + luaStringTable(manifestFields) + "\n" +
            "local manifestLimits = " + luaLimitTable(manifestFields, false) + "\n" +
            exactHashFunction() +
            primaryValidationFunction() +
            "local server = redis.call('INFO', 'server')\n" +
            "local replication = redis.call('INFO', 'replication')\n" +
            "local cluster = redis.call('INFO', 'cluster')\n" +
            "local memory = redis.call('INFO', 'memory')\n" +
            "if string.len(server) > " + MAX_INFO_SECTION_BYTES +
                " or string.len(replication) > " + MAX_INFO_SECTION_BYTES +
                " or string.len(cluster) > " + MAX_INFO_SECTION_BYTES +
                " or string.len(memory) > " + MAX_INFO_SECTION_BYTES + " then\n" +
            "  return {'RESPONSE_CORRUPT'}\n" +
            "end\n" +
            "local control = readExactHash(KEYS[1], controlNames, controlLimits, 1, " +
                "'CONTROL_CORRUPT', 'GENERATION_CHANGED')\n" +
            "if control[1] ~= 'OK' then return {control[1]} end\n" +
            "local primaryStatus = validatePrimary(server, replication, cluster, memory, " +
                "ARGV[" + primaryRunIdArgument + "])\n" +
            "if primaryStatus ~= 'OK' then return {primaryStatus} end\n" +
            "local manifest = readExactHash(KEYS[2], manifestNames, manifestLimits, " +
                manifestArgumentOffset + ", 'MANIFEST_CORRUPT', 'MANIFEST_CHANGED')\n" +
            "if manifest[1] ~= 'OK' then return {manifest[1]} end\n" +
            "local expectedCount = tonumber(ARGV[" + numericArgumentOffset + "])\n" +
            "local offset = tonumber(ARGV[" + (numericArgumentOffset + 1) + "])\n" +
            "local pageEntries = tonumber(ARGV[" + (numericArgumentOffset + 2) + "])\n" +
            "local keyLimit = tonumber(ARGV[" + (numericArgumentOffset + 3) + "])\n" +
            "local payloadLimit = tonumber(ARGV[" + (numericArgumentOffset + 4) + "])\n" +
            "local totalLimit = tonumber(ARGV[" + (numericArgumentOffset + 5) + "])\n" +
            "if not expectedCount or not offset or not pageEntries or not keyLimit or " +
                "not payloadLimit or not totalLimit then return {'RESPONSE_CORRUPT'} end\n" +
            "if expectedCount < 0 or offset < 0 or offset > expectedCount or pageEntries < 1 " +
                "or keyLimit < 1 or payloadLimit < 1 or totalLimit < 1 then " +
                "return {'RESPONSE_CORRUPT'} end\n" +
            "if expectedCount % 1 ~= 0 or offset % 1 ~= 0 or pageEntries % 1 ~= 0 or " +
                "keyLimit % 1 ~= 0 or payloadLimit % 1 ~= 0 or totalLimit % 1 ~= 0 then " +
                "return {'RESPONSE_CORRUPT'} end\n" +
            "local hashCount = redis.call('HLEN', KEYS[3])\n" +
            "local indexCount = redis.call('ZCARD', KEYS[4])\n" +
            "if hashCount ~= expectedCount or indexCount ~= expectedCount then " +
                "return {'FAMILY_CARDINALITY_MISMATCH'} end\n" +
            "local expectedPageEntries = math.min(pageEntries, expectedCount - offset)\n" +
            "local indexed = {}\n" +
            "if expectedPageEntries > 0 then\n" +
            "  indexed = redis.call('ZRANGE', KEYS[4], offset, " +
                "offset + expectedPageEntries - 1, 'WITHSCORES')\n" +
            "end\n" +
            "if #indexed ~= expectedPageEntries * 2 then " +
                "return {'FAMILY_CARDINALITY_MISMATCH'} end\n" +
            "local keys = {}\n" +
            "local payloadLengths = {}\n" +
            "local cumulativeBytes = 0\n" +
            "for index = 1, #indexed, 2 do\n" +
            "  local key = indexed[index]\n" +
            "  local score = indexed[index + 1]\n" +
            "  local keyLength = string.len(key)\n" +
            "  if score ~= '0' or keyLength < 1 or keyLength > keyLimit then " +
                "return {'INDEX_ENTRY_INVALID'} end\n" +
            "  local payloadLength = redis.call('HSTRLEN', KEYS[3], key)\n" +
            "  if payloadLength < 1 or payloadLength > payloadLimit then " +
                "return {'PAGE_ENTRY_INVALID'} end\n" +
            "  cumulativeBytes = cumulativeBytes + keyLength + payloadLength\n" +
            "  if cumulativeBytes > totalLimit then return {'PAGE_LIMIT_EXCEEDED'} end\n" +
            "  keys[#keys + 1] = key\n" +
            "  payloadLengths[#payloadLengths + 1] = payloadLength\n" +
            "end\n" +
            "local payloads = {}\n" +
            "if #keys > 0 then payloads = redis.call('HMGET', KEYS[3], unpack(keys)) end\n" +
            "if #payloads ~= #keys then return {'PAGE_ENTRY_INVALID'} end\n" +
            "for index, payload in ipairs(payloads) do\n" +
            "  if not payload or string.len(payload) ~= payloadLengths[index] then " +
                "return {'PAGE_ENTRY_INVALID'} end\n" +
            "end\n" +
            "return {'OK', server, replication, cluster, memory, control[4], control[2], " +
                "control[3], offset, expectedCount, keys, payloadLengths, payloads}\n";
        return script.getBytes(StandardCharsets.UTF_8);
    }

    private static String exactHashFunction() {
        return "local function readExactHash(key, names, limits, argumentOffset, " +
            "corruptStatus, changedStatus)\n" +
            "  local count = redis.call('HLEN', key)\n" +
            "  if count ~= #names then return {corruptStatus} end\n" +
            "  local lengths = {}\n" +
            "  for index, name in ipairs(names) do\n" +
            "    local length = redis.call('HSTRLEN', key, name)\n" +
            "    lengths[index] = length\n" +
            "    if length > limits[index] then return {corruptStatus} end\n" +
            "  end\n" +
            "  local values = redis.call('HMGET', key, unpack(names))\n" +
            "  if #values ~= #names then return {corruptStatus} end\n" +
            "  for index, value in ipairs(values) do\n" +
            "    if not value or string.len(value) ~= lengths[index] then " +
                "return {corruptStatus} end\n" +
            "    if value ~= ARGV[argumentOffset + index - 1] then " +
                "return {changedStatus} end\n" +
            "  end\n" +
            "  return {'OK', lengths, values, count}\n" +
            "end\n";
    }

    private static String boundedHashFunction() {
        return "local function readBoundedHash(key, names, limits, corruptStatus)\n" +
            "  local count = redis.call('HLEN', key)\n" +
            "  if count ~= #names then return {corruptStatus} end\n" +
            "  local lengths = {}\n" +
            "  for index, name in ipairs(names) do\n" +
            "    local length = redis.call('HSTRLEN', key, name)\n" +
            "    lengths[index] = length\n" +
            "    if length > limits[index] then return {corruptStatus} end\n" +
            "  end\n" +
            "  local values = redis.call('HMGET', key, unpack(names))\n" +
            "  if #values ~= #names then return {corruptStatus} end\n" +
            "  for index, value in ipairs(values) do\n" +
            "    if not value or string.len(value) ~= lengths[index] then " +
                "return {corruptStatus} end\n" +
            "  end\n" +
            "  return {'OK', lengths, values, count}\n" +
            "end\n";
    }

    private static String primaryValidationFunction() {
        return "local function validatePrimary(server, replication, cluster, memory, " +
            "expectedRunId)\n" +
            "  local runId = string.match(server, " +
                "'\\r\\nrun_id:([^\\r\\n]*)\\r\\n')\n" +
            "  local role = string.match(replication, " +
                "'\\r\\nrole:([^\\r\\n]*)\\r\\n')\n" +
            "  local clusterEnabled = string.match(cluster, " +
                "'\\r\\ncluster_enabled:([^\\r\\n]*)\\r\\n')\n" +
            "  local maxmemoryPolicy = string.match(memory, " +
                "'\\r\\nmaxmemory_policy:([^\\r\\n]*)\\r\\n')\n" +
            "  if not runId or not role or runId ~= expectedRunId or role ~= 'master' then\n" +
            "    return 'GENERATION_CHANGED'\n" +
            "  end\n" +
            "  if clusterEnabled ~= '0' or maxmemoryPolicy ~= 'noeviction' then\n" +
            "    return 'PRIMARY_CONFIGURATION_INVALID'\n" +
            "  end\n" +
            "  return 'OK'\n" +
            "end\n";
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
                : RedisSnapshotManifestCodec.maxUtf8Bytes(field);
            joiner.add(Integer.toString(limit));
        }
        return joiner.toString();
    }

    private static byte[] utf8(String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw failure(RedisSnapshotReadException.Reason.RESPONSE_CORRUPT);
        }
    }

    private static byte[] ascii(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }

    private static RedisSnapshotReadException failure(
        RedisSnapshotReadException.Reason reason
    ) {
        return new RedisSnapshotReadException(reason);
    }
}
