package com.nomendi6.orgsec.storage.redis.protocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Protocol-defined logical-byte measurement for a HASH plus sorted-index layout.
 *
 * <p>For a non-empty family, the fixed cost is the UTF-8 length of its distinct non-empty data and
 * index key names. Each entry adds the field key twice (once as the HASH field and once as the
 * index member) and its canonical payload once. An empty family costs zero because Redis does not
 * retain empty HASH or ZSET keys. The measurement is carried separately in the manifest as a
 * safety total and does not contribute to the canonical content digest. It is not Redis allocator
 * or quota accounting. Control, manifest and publication metadata overhead is outside this
 * unit.</p>
 */
final class RedisSnapshotLogicalBytes {

    private RedisSnapshotLogicalBytes() {
    }

    static long familyBase(String dataKey, String indexKey) {
        Objects.requireNonNull(dataKey, "dataKey must not be null");
        Objects.requireNonNull(indexKey, "indexKey must not be null");
        if (dataKey.isEmpty()) {
            throw new IllegalArgumentException("dataKey must not be empty");
        }
        if (indexKey.isEmpty()) {
            throw new IllegalArgumentException("indexKey must not be empty");
        }
        if (dataKey.equals(indexKey)) {
            throw new IllegalArgumentException("dataKey and indexKey must be distinct");
        }
        return addExact(utf8Length(dataKey, "dataKey"), utf8Length(indexKey, "indexKey"));
    }

    static long entry(RedisCanonicalEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        long duplicatedFieldKeyBytes = Math.multiplyExact(
            (long) entry.canonicalKeyLength(),
            2L
        );
        return addExact(duplicatedFieldKeyBytes, entry.canonicalPayloadLength());
    }

    static long addExact(long current, long increment) {
        if (current < 0 || increment < 0) {
            throw new IllegalArgumentException("logical byte counts must not be negative");
        }
        return Math.addExact(current, increment);
    }

    private static int utf8Length(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            return encoded.remaining();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                name + " must be a well-formed Unicode string",
                exception
            );
        }
    }
}
