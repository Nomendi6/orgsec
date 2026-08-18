package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.fence.SecurityDatasetFence;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisScriptingCommands;
import org.springframework.data.redis.connection.ReturnType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * One synchronous, owner-thread staging session for a library-owned snapshot UUID.
 *
 * <p>Only immutable family hashes, their score-zero indexes and the final manifest are written.
 * This T1 component never writes control state, READY/UPDATING, leases or GC metadata.
 * A transport failure poisons the session because the result may be ambiguous.</p>
 */
final class RedisStagingSession implements AutoCloseable {

    private static final byte[] APPEND_STAGING_BATCH = buildAppendStagingBatchScript();
    private static final byte[] WRITE_MANIFEST_IF_COMPLETE =
        buildWriteManifestIfCompleteScript();

    private static final byte[] APPEND_OK = ascii("APPEND_OK");
    private static final byte[] CONTROL_CHANGED = ascii("CONTROL_CHANGED");
    private static final byte[] LEASE_CHANGED = ascii("LEASE_CHANGED");
    private static final byte[] MANIFEST_PRESENT = ascii("MANIFEST_PRESENT");
    private static final byte[] ENTRY_HALF_PAIR = ascii("ENTRY_HALF_PAIR");
    private static final byte[] ENTRY_PAYLOAD_MISMATCH =
        ascii("ENTRY_PAYLOAD_MISMATCH");
    private static final byte[] ENTRY_SCORE_NOT_ZERO = ascii("ENTRY_SCORE_NOT_ZERO");
    private static final byte[] BATCH_DUPLICATE = ascii("BATCH_DUPLICATE");
    private static final byte[] MANIFEST_WRITTEN = ascii("MANIFEST_WRITTEN");
    private static final byte[] MANIFEST_IDENTICAL = ascii("MANIFEST_IDENTICAL");
    private static final byte[] FAMILY_COUNT_MISMATCH =
        ascii("FAMILY_COUNT_MISMATCH");
    private static final byte[] MANIFEST_MISMATCH = ascii("MANIFEST_MISMATCH");
    private static final byte[] WRITE_FAILED_ROLLED_BACK =
        ascii("WRITE_FAILED_ROLLED_BACK");
    private static final byte[] ROLLBACK_FAILED = ascii("ROLLBACK_FAILED");

    private static final List<RedisSnapshotFamily> FAMILIES = List.of(
        RedisSnapshotFamily.PERSONS,
        RedisSnapshotFamily.ORGANIZATIONS,
        RedisSnapshotFamily.PARTY_ROLES,
        RedisSnapshotFamily.POSITION_ROLES,
        RedisSnapshotFamily.ROLES,
        RedisSnapshotFamily.PRIVILEGES
    );
    private static final Set<RedisSnapshotFamily> REQUIRED_FAMILIES =
        Collections.unmodifiableSet(EnumSet.copyOf(FAMILIES));

    private final RedisConnection connection;
    private final ScriptEvaluator evaluator;
    private final RedisDatasetKeyspace keyspace;
    private final RedisSnapshotWriteLimits limits;
    private final GenerationGuard guard;
    private final RedisSnapshotManifest.PendingPublication publication;
    private final RedisSnapshotManifestCodec manifestCodec;
    private final EntryVerifier entryVerifier;
    private final Thread ownerThread;
    private final Set<RedisSnapshotFamily> completedFamilies =
        EnumSet.noneOf(RedisSnapshotFamily.class);

    private State state = State.OPEN;
    private boolean connectionClosed;
    private RedisSnapshotContentDigest sealedContent;
    private RedisSnapshotManifest.Verified sealedManifest;
    private List<byte[]> sealedManifestValues;

    RedisStagingSession(
        RedisConnection connection,
        ScriptEvaluator evaluator,
        RedisDatasetKeyspace keyspace,
        RedisSnapshotWriteLimits limits,
        GenerationGuard guard,
        RedisSnapshotManifest.PendingPublication publication,
        RedisSnapshotManifestCodec manifestCodec,
        EntryVerifier entryVerifier
    ) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace must not be null");
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.guard = Objects.requireNonNull(guard, "guard must not be null");
        this.publication = Objects.requireNonNull(
            publication,
            "publication must not be null"
        );
        this.manifestCodec = Objects.requireNonNull(
            manifestCodec,
            "manifestCodec must not be null"
        );
        this.entryVerifier = Objects.requireNonNull(
            entryVerifier,
            "entryVerifier must not be null"
        );
        this.ownerThread = Thread.currentThread();
    }

    UUID snapshotId() {
        requireOwnerThread();
        return publication.snapshotId();
    }

    /**
     * Atomically appends one pre-sorted bounded batch to one immutable family.
     *
     * <p>Batches may be submitted in arbitrary global order. Every individual batch must be
     * strictly increasing by unsigned canonical-key bytes. The Redis index establishes the final
     * whole-family order.</p>
     */
    void append(RedisSnapshotFamily family, List<RedisCanonicalEntry> entries) {
        requireOpen("append");
        Objects.requireNonNull(family, "family must not be null");
        if (!REQUIRED_FAMILIES.contains(family)) {
            throw new RedisSnapshotEntryCorruptionException(
                RedisSnapshotEntryCorruptionException.Reason.UNSUPPORTED_FAMILY
            );
        }
        if (completedFamilies.contains(family)) {
            throw new IllegalStateException("cannot append to a completed snapshot family");
        }
        ValidatedBatch batch = validateBatch(family, entries);

        String manifestKey = keyspace.manifestKey(publication.snapshotId());
        byte[][] keys = encodeSameSlotKeys(
            keyspace.controlKey(),
            manifestKey,
            keyspace.familyKey(publication.snapshotId(), family),
            keyspace.familyIndexKey(publication.snapshotId(), family),
            keyspace.leaseKey()
        );
        List<byte[]> arguments = guard.arguments();
        arguments.add(ascii(Integer.toString(batch.size())));
        for (int index = 0; index < batch.size(); index++) {
            arguments.add(batch.key(index));
            arguments.add(batch.payload(index));
        }

        Object result = evaluate(
            APPEND_STAGING_BATCH,
            keys,
            arguments.toArray(byte[][]::new),
            "append staging batch"
        );
        if (matches(result, APPEND_OK)) {
            return;
        }
        if (matches(result, CONTROL_CHANGED)) {
            failPoisoned(new RedisStagingFenceException(
                RedisStagingFenceException.Reason.GENERATION_CHANGED
            ));
        }
        if (matches(result, LEASE_CHANGED)) {
            failPoisoned(new RedisStagingFenceException(
                RedisStagingFenceException.Reason.LEASE_CHANGED
            ));
        }
        if (matches(result, MANIFEST_PRESENT)) {
            failCorrupt(
                RedisStagingCorruptionException.Reason.MANIFEST_PRESENT_DURING_APPEND
            );
        }
        if (matches(result, ENTRY_HALF_PAIR)) {
            failCorrupt(RedisStagingCorruptionException.Reason.ENTRY_HALF_PAIR);
        }
        if (matches(result, ENTRY_PAYLOAD_MISMATCH)) {
            failCorrupt(RedisStagingCorruptionException.Reason.ENTRY_PAYLOAD_MISMATCH);
        }
        if (matches(result, ENTRY_SCORE_NOT_ZERO)) {
            failCorrupt(RedisStagingCorruptionException.Reason.ENTRY_SCORE_NOT_ZERO);
        }
        if (matches(result, BATCH_DUPLICATE)) {
            failCorrupt(RedisStagingCorruptionException.Reason.DUPLICATE_BATCH_KEY);
        }
        if (matches(result, WRITE_FAILED_ROLLED_BACK)) {
            failPoisoned(new RedisStagingWriteException());
        }
        if (matches(result, ROLLBACK_FAILED)) {
            failCorrupt(RedisStagingCorruptionException.Reason.ROLLBACK_FAILED);
        }
        failCorrupt(RedisStagingCorruptionException.Reason.INVALID_SCRIPT_RESPONSE);
    }

    /** Marks one locally complete family. No Redis state is changed. */
    void complete(RedisSnapshotFamily family) {
        requireOpen("complete family");
        Objects.requireNonNull(family, "family must not be null");
        if (!REQUIRED_FAMILIES.contains(family)) {
            throw new IllegalArgumentException("family is not part of protocol-v1 snapshot");
        }
        if (!completedFamilies.add(family)) {
            throw new IllegalStateException("snapshot family is already complete");
        }
    }

    /**
     * Seals all six completed families and atomically writes the exact protocol-v1 manifest.
     *
     * <p>A repeated call with the exact same content is an idempotent manifest retry. A different
     * content result is rejected locally. No control field is changed by this operation.</p>
     */
    RedisSnapshotManifest.Verified sealAndWriteManifest(
        RedisSnapshotContentDigest content
    ) {
        requireOwnerThread();
        Objects.requireNonNull(content, "content must not be null");
        if (state == State.OPEN) {
            if (!completedFamilies.equals(REQUIRED_FAMILIES)) {
                throw new IllegalStateException(
                    "all six snapshot families must be complete before sealing"
                );
            }
            sealedManifest = publication.seal(content);
            sealedContent = content;
            sealedManifestValues = encodeManifestValues(sealedManifest);
            state = State.SEALED;
        } else if (state == State.SEALED) {
            if (!content.equals(sealedContent)) {
                throw new IllegalArgumentException(
                    "sealed manifest retry must use the exact same content"
                );
            }
        } else {
            throw unusable("seal manifest");
        }

        List<String> keyNames = new ArrayList<>(3 + FAMILIES.size() * 2);
        keyNames.add(keyspace.controlKey());
        keyNames.add(keyspace.manifestKey(publication.snapshotId()));
        for (RedisSnapshotFamily family : FAMILIES) {
            keyNames.add(keyspace.familyKey(publication.snapshotId(), family));
            keyNames.add(keyspace.familyIndexKey(publication.snapshotId(), family));
        }
        keyNames.add(keyspace.leaseKey());
        byte[][] keys = encodeSameSlotKeys(keyNames.toArray(String[]::new));
        List<byte[]> arguments = guard.arguments();
        for (byte[] value : sealedManifestValues) {
            arguments.add(value.clone());
        }

        Object result = evaluate(
            WRITE_MANIFEST_IF_COMPLETE,
            keys,
            arguments.toArray(byte[][]::new),
            "write staging manifest"
        );
        if (matches(result, MANIFEST_WRITTEN) || matches(result, MANIFEST_IDENTICAL)) {
            return sealedManifest;
        }
        if (matches(result, CONTROL_CHANGED)) {
            failPoisoned(new RedisStagingFenceException(
                RedisStagingFenceException.Reason.GENERATION_CHANGED
            ));
        }
        if (matches(result, LEASE_CHANGED)) {
            failPoisoned(new RedisStagingFenceException(
                RedisStagingFenceException.Reason.LEASE_CHANGED
            ));
        }
        if (matches(result, FAMILY_COUNT_MISMATCH)) {
            failCorrupt(RedisStagingCorruptionException.Reason.FAMILY_COUNT_MISMATCH);
        }
        if (matches(result, MANIFEST_MISMATCH)) {
            failCorrupt(RedisStagingCorruptionException.Reason.MANIFEST_MISMATCH);
        }
        if (matches(result, WRITE_FAILED_ROLLED_BACK)) {
            failPoisoned(new RedisStagingWriteException());
        }
        if (matches(result, ROLLBACK_FAILED)) {
            failCorrupt(RedisStagingCorruptionException.Reason.ROLLBACK_FAILED);
        }
        failCorrupt(RedisStagingCorruptionException.Reason.INVALID_SCRIPT_RESPONSE);
        throw new AssertionError("unreachable");
    }

    /** Poisons and closes an unfinished session without deleting staging keys. */
    void abort() {
        requireOwnerThread();
        if (state == State.CLOSED || state == State.POISONED) {
            throw unusable("abort");
        }
        state = State.POISONED;
        closeOwnedConnection(null);
    }

    @Override
    public void close() {
        requireOwnerThread();
        if (state == State.CLOSED) {
            return;
        }
        if (state == State.OPEN) {
            state = State.POISONED;
        }
        closeOwnedConnection(null);
        if (state != State.POISONED) {
            state = State.CLOSED;
        }
    }

    private ValidatedBatch validateBatch(
        RedisSnapshotFamily family,
        List<RedisCanonicalEntry> entries
    ) {
        Objects.requireNonNull(entries, "entries must not be null");
        int entryCount = entries.size();
        if (entryCount == 0) {
            throw new IllegalArgumentException("entries must not be empty");
        }
        if (entryCount > limits.maxEntries()) {
            throw new IllegalArgumentException("batch entry count exceeds maxEntries");
        }

        byte[][] keys = new byte[entryCount][];
        byte[][] payloads = new byte[entryCount][];
        long cumulativeBytes = 0;
        byte[] previousKey = null;
        for (int index = 0; index < entryCount; index++) {
            RedisCanonicalEntry entry = entries.get(index);
            if (entry == null) {
                throw new RedisSnapshotEntryCorruptionException(
                    RedisSnapshotEntryCorruptionException.Reason.ENTRY_MISSING
                );
            }
            if (entry.canonicalKeyLength() > limits.maxKeyBytes()) {
                throw new IllegalArgumentException("canonical key exceeds maxKeyBytes");
            }
            if (entry.canonicalPayloadLength() > limits.maxPayloadBytes()) {
                throw new IllegalArgumentException("canonical payload exceeds maxPayloadBytes");
            }
            entryVerifier.verify(family, entry);
            try {
                cumulativeBytes = Math.addExact(
                    cumulativeBytes,
                    entry.canonicalKeyLength()
                );
                cumulativeBytes = Math.addExact(
                    cumulativeBytes,
                    entry.canonicalPayloadLength()
                );
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("batch byte count overflow", overflow);
            }
            if (cumulativeBytes > limits.maxBatchBytes()) {
                throw new IllegalArgumentException("batch bytes exceed maxBatchBytes");
            }

            byte[] key = entry.canonicalKey();
            if (previousKey != null && Arrays.compareUnsigned(previousKey, key) >= 0) {
                throw new IllegalArgumentException(
                    "batch canonical keys must be strictly increasing"
                );
            }
            keys[index] = key;
            payloads[index] = entry.canonicalPayload();
            previousKey = key;
        }
        return new ValidatedBatch(keys, payloads);
    }

    private List<byte[]> encodeManifestValues(RedisSnapshotManifest.Verified manifest) {
        Map<String, String> fields = manifestCodec.encode(manifest);
        List<byte[]> values = new ArrayList<>(RedisSnapshotManifestCodec.REQUIRED_FIELD_COUNT);
        for (String field : RedisSnapshotManifestCodec.orderedFields()) {
            values.add(RedisSnapshotManifestCodec.encodeBoundedUtf8Value(
                field,
                fields.get(field)
            ));
        }
        return List.copyOf(values);
    }

    private Object evaluate(
        byte[] script,
        byte[][] keys,
        byte[][] arguments,
        String operation
    ) {
        try {
            Object result = evaluator.eval(connection, script, keys, arguments);
            if (result == null) {
                RedisProtocolUnavailableException failure =
                    new RedisProtocolUnavailableException(
                        "Redis " + operation + " script returned no result."
                    );
                poisonAndClose(failure);
                throw failure;
            }
            return result;
        } catch (RedisProtocolUnavailableException failure) {
            if (state != State.POISONED) {
                poisonAndClose(failure);
            }
            throw failure;
        } catch (RuntimeException failure) {
            RedisProtocolUnavailableException unavailable =
                new RedisProtocolUnavailableException(
                    "Redis " + operation + " script failed.",
                    failure
                );
            poisonAndClose(unavailable);
            throw unavailable;
        } catch (Error failure) {
            poisonAndClose(failure);
            throw failure;
        }
    }

    private void failCorrupt(RedisStagingCorruptionException.Reason reason) {
        failPoisoned(new RedisStagingCorruptionException(reason));
    }

    private void failPoisoned(RedisProtocolException failure) {
        poisonAndClose(failure);
        throw failure;
    }

    private void poisonAndClose(Throwable pendingFailure) {
        state = State.POISONED;
        closeOwnedConnection(pendingFailure);
    }

    private void closeOwnedConnection(Throwable pendingFailure) {
        if (connectionClosed) {
            return;
        }
        connectionClosed = true;
        try {
            connection.close();
        } catch (RuntimeException closeFailure) {
            RedisProtocolUnavailableException unavailable =
                new RedisProtocolUnavailableException(
                    "Failed to close the Redis staging-session connection.",
                    closeFailure
                );
            state = State.POISONED;
            if (pendingFailure != null) {
                pendingFailure.addSuppressed(unavailable);
            } else {
                throw unavailable;
            }
        }
    }

    private void requireOpen(String operation) {
        requireOwnerThread();
        if (state != State.OPEN) {
            throw unusable(operation);
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                "Redis staging session may only be used by its owner thread"
            );
        }
    }

    private IllegalStateException unusable(String operation) {
        return new IllegalStateException(
            "cannot " + operation + " when Redis staging session is " + state
        );
    }

    private static byte[][] encodeSameSlotKeys(String... keys) {
        if (keys.length == 0) {
            throw new IllegalArgumentException("at least one Redis key is required");
        }
        String expectedHashTag = hashTag(keys[0]);
        byte[][] encoded = new byte[keys.length][];
        for (int index = 0; index < keys.length; index++) {
            String key = Objects.requireNonNull(keys[index], "Redis key must not be null");
            if (!expectedHashTag.equals(hashTag(key))) {
                throw new IllegalArgumentException("all staging keys must share one Redis slot");
            }
            encoded[index] = key.getBytes(StandardCharsets.UTF_8);
        }
        return encoded;
    }

    private static String hashTag(String key) {
        Objects.requireNonNull(key, "Redis key must not be null");
        int opening = key.indexOf('{');
        int closing = opening < 0 ? -1 : key.indexOf('}', opening + 1);
        if (opening < 0 || closing <= opening + 1) {
            throw new IllegalArgumentException("Redis key must contain a non-empty hash tag");
        }
        return key.substring(opening + 1, closing);
    }

    private static boolean matches(Object result, byte[] expected) {
        return result instanceof byte[] bytes && Arrays.equals(bytes, expected);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    static Object evaluateOnConnection(
        RedisConnection connection,
        byte[] script,
        byte[][] keys,
        byte[][] arguments
    ) {
        RedisScriptingCommands commands = connection.scriptingCommands();
        if (commands == null) {
            throw new IllegalStateException("Redis connection returned no scripting commands");
        }
        byte[][] keysAndArguments = new byte[keys.length + arguments.length][];
        System.arraycopy(keys, 0, keysAndArguments, 0, keys.length);
        System.arraycopy(arguments, 0, keysAndArguments, keys.length, arguments.length);
        return commands.eval(
            script,
            ReturnType.VALUE,
            keys.length,
            keysAndArguments
        );
    }

    private static byte[] buildAppendStagingBatchScript() {
        int generationArgumentCount = generationArgumentCount();
        int entryCountArgument = generationArgumentCount + 1;
        int firstEntryKeyArgument = entryCountArgument + 1;
        int firstEntryPayloadArgument = firstEntryKeyArgument + 1;
        String script = generationGuardLua() +
            "if redis.call('EXISTS', KEYS[2]) ~= 0 then\n" +
            "  return 'MANIFEST_PRESENT'\n" +
            "end\n" +
            "local entryCount = tonumber(ARGV[" + entryCountArgument + "])\n" +
            "if entryCount == nil or entryCount < 1 or entryCount % 1 ~= 0 " +
                "or #ARGV ~= " + entryCountArgument + " + (entryCount * 2) then\n" +
            "  return 'BATCH_INVALID'\n" +
            "end\n" +
            "local seen = {}\n" +
            "local existingEntries = {}\n" +
            "for entryIndex = 0, entryCount - 1 do\n" +
            "  local entryKey = ARGV[" + firstEntryKeyArgument +
                " + (entryIndex * 2)]\n" +
            "  local payload = ARGV[" + firstEntryPayloadArgument +
                " + (entryIndex * 2)]\n" +
            "  if seen[entryKey] then\n" +
            "    return 'BATCH_DUPLICATE'\n" +
            "  end\n" +
            "  seen[entryKey] = true\n" +
            "  local inHash = redis.call('HEXISTS', KEYS[3], entryKey)\n" +
            "  local score = redis.call('ZSCORE', KEYS[4], entryKey)\n" +
            "  local inIndex = score ~= false\n" +
            "  if (inHash == 1) ~= inIndex then\n" +
            "    return 'ENTRY_HALF_PAIR'\n" +
            "  end\n" +
            "  existingEntries[entryIndex + 1] = inHash\n" +
            "  if inHash == 1 then\n" +
            "    if redis.call('HSTRLEN', KEYS[3], entryKey) ~= #payload then\n" +
            "      return 'ENTRY_PAYLOAD_MISMATCH'\n" +
            "    end\n" +
            "    if tonumber(score) ~= 0 then\n" +
            "      return 'ENTRY_SCORE_NOT_ZERO'\n" +
            "    end\n" +
            "  end\n" +
            "end\n" +
            "for entryIndex = 0, entryCount - 1 do\n" +
            "  if existingEntries[entryIndex + 1] == 1 then\n" +
            "    local entryKey = ARGV[" + firstEntryKeyArgument +
                " + (entryIndex * 2)]\n" +
            "    if redis.call('HGET', KEYS[3], entryKey) " +
                "~= ARGV[" + firstEntryPayloadArgument +
                " + (entryIndex * 2)] then\n" +
            "      return 'ENTRY_PAYLOAD_MISMATCH'\n" +
            "    end\n" +
            "  end\n" +
            "end\n" +
            "local addedKeys = {}\n" +
            "local function isErrorReply(reply)\n" +
            "  return type(reply) == 'table' and reply.err ~= nil\n" +
            "end\n" +
            "local function rollbackAddedKeys()\n" +
            "  local rollbackSucceeded = true\n" +
            "  for addedIndex = #addedKeys, 1, -1 do\n" +
            "    local addedKey = addedKeys[addedIndex]\n" +
            "    local hashDelete = redis.pcall('HDEL', KEYS[3], addedKey)\n" +
            "    local indexDelete = redis.pcall('ZREM', KEYS[4], addedKey)\n" +
            "    local hashExists = redis.pcall('HEXISTS', KEYS[3], addedKey)\n" +
            "    local indexScore = redis.pcall('ZSCORE', KEYS[4], addedKey)\n" +
            "    if isErrorReply(hashDelete) or isErrorReply(indexDelete) " +
                "or isErrorReply(hashExists) or isErrorReply(indexScore) " +
                "or hashExists ~= 0 or indexScore ~= false then\n" +
            "      rollbackSucceeded = false\n" +
            "    end\n" +
            "  end\n" +
            "  return rollbackSucceeded\n" +
            "end\n" +
            "for entryIndex = 0, entryCount - 1 do\n" +
            "  local entryKey = ARGV[" + firstEntryKeyArgument +
                " + (entryIndex * 2)]\n" +
            "  if existingEntries[entryIndex + 1] == 0 then\n" +
            "    local hashWrite = redis.pcall('HSET', KEYS[3], entryKey, " +
                "ARGV[" + firstEntryPayloadArgument + " + (entryIndex * 2)])\n" +
            "    if isErrorReply(hashWrite) or hashWrite ~= 1 then\n" +
            "      if rollbackAddedKeys() then\n" +
            "        return 'WRITE_FAILED_ROLLED_BACK'\n" +
            "      end\n" +
            "      return 'ROLLBACK_FAILED'\n" +
            "    end\n" +
            "    addedKeys[#addedKeys + 1] = entryKey\n" +
            "    local indexWrite = redis.pcall('ZADD', KEYS[4], 'NX', 0, entryKey)\n" +
            "    if isErrorReply(indexWrite) or indexWrite ~= 1 then\n" +
            "      if rollbackAddedKeys() then\n" +
            "        return 'WRITE_FAILED_ROLLED_BACK'\n" +
            "      end\n" +
            "      return 'ROLLBACK_FAILED'\n" +
            "    end\n" +
            "  end\n" +
            "end\n" +
            "return 'APPEND_OK'\n";
        return script.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] buildWriteManifestIfCompleteScript() {
        List<String> manifestFields = RedisSnapshotManifestCodec.orderedFields();
        int generationArgumentCount = generationArgumentCount();
        int expectedArgumentCount = generationArgumentCount + manifestFields.size();
        StringJoiner manifestFieldNames = new StringJoiner(", ", "{", "}");
        StringJoiner manifestFieldLimits = new StringJoiner(", ", "{", "}");
        for (String field : manifestFields) {
            manifestFieldNames.add("'" + field + "'");
            manifestFieldLimits.add(Integer.toString(
                RedisSnapshotManifestCodec.maxUtf8Bytes(field)
            ));
        }
        StringJoiner familyCountArguments = new StringJoiner(", ", "{", "}");
        for (String field : List.of(
            RedisSnapshotManifestCodec.FIELD_PERSONS_COUNT,
            RedisSnapshotManifestCodec.FIELD_ORGANIZATIONS_COUNT,
            RedisSnapshotManifestCodec.FIELD_PARTY_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_POSITION_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_ROLES_COUNT,
            RedisSnapshotManifestCodec.FIELD_PRIVILEGES_COUNT
        )) {
            int fieldIndex = manifestFields.indexOf(field);
            if (fieldIndex < 0) {
                throw new IllegalStateException("manifest family count field is missing");
            }
            familyCountArguments.add(Integer.toString(
                generationArgumentCount + fieldIndex + 1
            ));
        }
        String script = generationGuardLua() +
            "local manifestFields = " + manifestFieldNames + "\n" +
            "local manifestFieldLimits = " + manifestFieldLimits + "\n" +
            "local familyCountArguments = " + familyCountArguments + "\n" +
            "if #ARGV ~= " + expectedArgumentCount + " then\n" +
            "  return 'MANIFEST_MISMATCH'\n" +
            "end\n" +
            "for familyIndex = 1, #familyCountArguments do\n" +
            "  local expectedCount = tonumber(ARGV[familyCountArguments[familyIndex]])\n" +
            "  local dataKeyIndex = 1 + (familyIndex * 2)\n" +
            "  if expectedCount == nil or expectedCount < 0 " +
                "or expectedCount % 1 ~= 0 then\n" +
            "    return 'MANIFEST_MISMATCH'\n" +
            "  end\n" +
            "  if redis.call('HLEN', KEYS[dataKeyIndex]) ~= expectedCount " +
                "or redis.call('ZCARD', KEYS[dataKeyIndex + 1]) ~= expectedCount then\n" +
            "    return 'FAMILY_COUNT_MISMATCH'\n" +
            "  end\n" +
            "end\n" +
            "if redis.call('EXISTS', KEYS[2]) ~= 0 then\n" +
            "  if redis.call('HLEN', KEYS[2]) ~= " + manifestFields.size() + " then\n" +
            "    return 'MANIFEST_MISMATCH'\n" +
            "  end\n" +
            "  for fieldIndex, fieldName in ipairs(manifestFields) do\n" +
            "    local fieldLength = redis.call('HSTRLEN', KEYS[2], fieldName)\n" +
            "    if fieldLength > manifestFieldLimits[fieldIndex] " +
                "or fieldLength ~= #ARGV[" + generationArgumentCount +
                " + fieldIndex] then\n" +
            "      return 'MANIFEST_MISMATCH'\n" +
            "    end\n" +
            "  end\n" +
            "  for fieldIndex, fieldName in ipairs(manifestFields) do\n" +
            "    if redis.call('HGET', KEYS[2], fieldName) ~= ARGV[" +
                generationArgumentCount + " + fieldIndex] then\n" +
            "      return 'MANIFEST_MISMATCH'\n" +
            "    end\n" +
            "  end\n" +
            "  return 'MANIFEST_IDENTICAL'\n" +
            "end\n" +
            "local manifestArguments = {}\n" +
            "for fieldIndex, fieldName in ipairs(manifestFields) do\n" +
            "  manifestArguments[#manifestArguments + 1] = fieldName\n" +
            "  manifestArguments[#manifestArguments + 1] = ARGV[" +
                generationArgumentCount + " + fieldIndex]\n" +
            "end\n" +
            "local manifestWrite = redis.pcall('HSET', KEYS[2], " +
                "unpack(manifestArguments))\n" +
            "if (type(manifestWrite) == 'table' and manifestWrite.err ~= nil) " +
                "or manifestWrite ~= " + manifestFields.size() + " then\n" +
            "  local cleanup = redis.pcall('DEL', KEYS[2])\n" +
            "  local manifestExists = redis.pcall('EXISTS', KEYS[2])\n" +
            "  if (type(cleanup) == 'table' and cleanup.err ~= nil) " +
                "or (type(manifestExists) == 'table' and manifestExists.err ~= nil) " +
                "or manifestExists ~= 0 then\n" +
            "    return 'ROLLBACK_FAILED'\n" +
            "  end\n" +
            "  return 'WRITE_FAILED_ROLLED_BACK'\n" +
            "end\n" +
            "return 'MANIFEST_WRITTEN'\n";
        return script.getBytes(StandardCharsets.UTF_8);
    }

    private static int generationArgumentCount() {
        return 1
            + RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
            + RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT;
    }

    private static String generationGuardLua() {
        int controlFieldCount = RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT;
        int leaseFieldCount = RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT;
        int leaseArgumentOffset = 1 + controlFieldCount;
        int issuedArgumentIndex = leaseArgumentOffset
            + RedisCoordinatorLeaseCodec.orderedFields().indexOf(
                RedisCoordinatorLeaseCodec.FIELD_ISSUED_AT_REDIS_MILLIS
            )
            + 1;
        int expiresArgumentIndex = leaseArgumentOffset
            + RedisCoordinatorLeaseCodec.orderedFields().indexOf(
                RedisCoordinatorLeaseCodec.FIELD_EXPIRES_AT_REDIS_MILLIS
            )
            + 1;
        StringJoiner controlFieldNames = new StringJoiner(", ", "{", "}");
        StringJoiner controlFieldLimits = new StringJoiner(", ", "{", "}");
        for (String field : RedisControlEnvelopeCodec.orderedFields()) {
            controlFieldNames.add("'" + field + "'");
            controlFieldLimits.add(Integer.toString(
                RedisControlEnvelopeCodec.maxUtf8Bytes(field)
            ));
        }
        StringJoiner leaseFieldNames = new StringJoiner(", ", "{", "}");
        StringJoiner leaseFieldLimits = new StringJoiner(", ", "{", "}");
        for (String field : RedisCoordinatorLeaseCodec.orderedFields()) {
            leaseFieldNames.add("'" + field + "'");
            leaseFieldLimits.add(Integer.toString(
                RedisCoordinatorLeaseCodec.maxUtf8Bytes(field)
            ));
        }
        return "local controlFields = " + controlFieldNames + "\n" +
            "local controlFieldLimits = " + controlFieldLimits + "\n" +
            "local leaseFields = " + leaseFieldNames + "\n" +
            "local leaseFieldLimits = " + leaseFieldLimits + "\n" +
            "local leaseKey = KEYS[#KEYS]\n" +
            "local serverInfo = redis.call('INFO', 'server')\n" +
            "local replicationInfo = redis.call('INFO', 'replication')\n" +
            "local clusterInfo = redis.call('INFO', 'cluster')\n" +
            "local memoryInfo = redis.call('INFO', 'memory')\n" +
            "if #serverInfo > 16384 or #replicationInfo > 16384 " +
                "or #clusterInfo > 4096 or #memoryInfo > 32768 then\n" +
            "  return 'CONTROL_CHANGED'\n" +
            "end\n" +
            "local currentRunId = string.match(serverInfo, " +
                "'\\r\\nrun_id:([0-9a-f]+)\\r\\n')\n" +
            "local currentRole = string.match(replicationInfo, " +
                "'\\r\\nrole:([^\\r\\n]+)\\r\\n')\n" +
            "local clusterEnabled = string.match(clusterInfo, " +
                "'\\r\\ncluster_enabled:([^\\r\\n]+)\\r\\n')\n" +
            "local maxmemoryPolicy = string.match(memoryInfo, " +
                "'\\r\\nmaxmemory_policy:([^\\r\\n]+)\\r\\n')\n" +
            "if currentRunId == nil or currentRunId ~= ARGV[1] " +
                "or currentRole ~= 'master' or clusterEnabled ~= '0' " +
                "or maxmemoryPolicy ~= 'noeviction' then\n" +
            "  return 'CONTROL_CHANGED'\n" +
            "end\n" +
            "if redis.call('HLEN', KEYS[1]) ~= " + controlFieldCount + " then\n" +
            "  return 'CONTROL_CHANGED'\n" +
            "end\n" +
            "for fieldIndex, fieldName in ipairs(controlFields) do\n" +
            "  local fieldLength = redis.call('HSTRLEN', KEYS[1], fieldName)\n" +
            "  if fieldLength > controlFieldLimits[fieldIndex] " +
                "or fieldLength ~= #ARGV[1 + fieldIndex] then\n" +
            "    return 'CONTROL_CHANGED'\n" +
            "  end\n" +
            "end\n" +
            "for fieldIndex, fieldName in ipairs(controlFields) do\n" +
            "  local actualValue = redis.call('HGET', KEYS[1], fieldName)\n" +
            "  if actualValue == false or actualValue ~= ARGV[1 + fieldIndex] then\n" +
            "    return 'CONTROL_CHANGED'\n" +
            "  end\n" +
            "end\n" +
            "if redis.call('HLEN', leaseKey) ~= " + leaseFieldCount + " then\n" +
            "  return 'LEASE_CHANGED'\n" +
            "end\n" +
            "for fieldIndex, fieldName in ipairs(leaseFields) do\n" +
            "  local fieldLength = redis.call('HSTRLEN', leaseKey, fieldName)\n" +
            "  if fieldLength > leaseFieldLimits[fieldIndex] " +
                "or fieldLength ~= #ARGV[" + leaseArgumentOffset +
                " + fieldIndex] then\n" +
            "    return 'LEASE_CHANGED'\n" +
            "  end\n" +
            "end\n" +
            "for fieldIndex, fieldName in ipairs(leaseFields) do\n" +
            "  local actualValue = redis.call('HGET', leaseKey, fieldName)\n" +
            "  if actualValue == false or actualValue ~= ARGV[" +
                leaseArgumentOffset + " + fieldIndex] then\n" +
            "    return 'LEASE_CHANGED'\n" +
            "  end\n" +
            "end\n" +
            "local redisTime = redis.call('TIME')\n" +
            "if type(redisTime) ~= 'table' or #redisTime < 2 then\n" +
            "  return 'LEASE_CHANGED'\n" +
            "end\n" +
            "local now = tonumber(redisTime[1]) * 1000 " +
                "+ math.floor(tonumber(redisTime[2]) / 1000)\n" +
            "local issued = tonumber(ARGV[" + issuedArgumentIndex + "])\n" +
            "local expires = tonumber(ARGV[" + expiresArgumentIndex + "])\n" +
            "if now == nil or issued == nil or expires == nil " +
                "or now < issued or now >= expires then\n" +
            "  return 'LEASE_CHANGED'\n" +
            "end\n";
    }

    @FunctionalInterface
    interface ScriptEvaluator {
        Object eval(
            RedisConnection connection,
            byte[] script,
            byte[][] keys,
            byte[][] arguments
        );
    }

    @FunctionalInterface
    interface EntryVerifier {
        void verify(RedisSnapshotFamily family, RedisCanonicalEntry entry);
    }

    static final class GenerationGuard {

        private final byte[] runId;
        private final List<byte[]> controlValues;

        private GenerationGuard(byte[] runId, List<byte[]> controlValues) {
            this.runId = runId.clone();
            this.controlValues = copyBytes(controlValues);
        }

        static GenerationGuard create(
            RedisPrimarySnapshot expectedPrimary,
            SecurityDatasetFence sourceFence,
            RedisCoordinatorLease.Verified activeLease,
            RedisControlEnvelopeCodec controlCodec,
            RedisCoordinatorLeaseCodec leaseCodec
        ) {
            RedisWireProtocol.requireVersionOne(sourceFence);
            Objects.requireNonNull(activeLease, "activeLease must not be null");
            RedisControlEnvelope control = expectedPrimary.controlEnvelope().orElseThrow(() ->
                new RedisStagingFenceException(
                    RedisStagingFenceException.Reason.EXPECTATION_INCONSISTENT
                )
            );
            String observedRunId = expectedPrimary.observation().runId();
            if (!observedRunId.equals(control.getIncarnation().getPrimaryRunId())
                || !sourceFence.getIdentity().equals(control.getIdentity())
                || activeLease.state() != RedisCoordinatorLease.State.ACTIVE
                || !activeLease.identity().equals(control.getIdentity())
                || !activeLease.incarnation().equals(control.getIncarnation())
                || activeLease.boundControlCounter() != control.getCounter()) {
                throw new RedisStagingFenceException(
                    RedisStagingFenceException.Reason.EXPECTATION_INCONSISTENT
                );
            }

            Map<String, String> encodedControl = controlCodec.encode(control);
            Map<String, String> encodedLease = leaseCodec.encode(activeLease);
            List<byte[]> values = new ArrayList<>(
                RedisControlEnvelopeCodec.REQUIRED_FIELD_COUNT
                    + RedisCoordinatorLeaseCodec.REQUIRED_FIELD_COUNT
            );
            for (String field : RedisControlEnvelopeCodec.orderedFields()) {
                values.add(encodedControl.get(field).getBytes(StandardCharsets.UTF_8));
            }
            for (String field : RedisCoordinatorLeaseCodec.orderedFields()) {
                values.add(encodedLease.get(field).getBytes(StandardCharsets.UTF_8));
            }
            return new GenerationGuard(ascii(observedRunId), values);
        }

        List<byte[]> arguments() {
            List<byte[]> arguments = new ArrayList<>(1 + controlValues.size());
            arguments.add(runId.clone());
            for (byte[] value : controlValues) {
                arguments.add(value.clone());
            }
            return arguments;
        }

        private static List<byte[]> copyBytes(List<byte[]> values) {
            List<byte[]> copy = new ArrayList<>(values.size());
            for (byte[] value : values) {
                copy.add(value.clone());
            }
            return List.copyOf(copy);
        }
    }

    private static final class ValidatedBatch {

        private final byte[][] keys;
        private final byte[][] payloads;

        private ValidatedBatch(byte[][] keys, byte[][] payloads) {
            this.keys = keys;
            this.payloads = payloads;
        }

        int size() {
            return keys.length;
        }

        byte[] key(int index) {
            return keys[index].clone();
        }

        byte[] payload(int index) {
            return payloads[index].clone();
        }
    }

    private enum State {
        OPEN,
        SEALED,
        POISONED,
        CLOSED
    }
}
