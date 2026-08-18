package com.nomendi6.orgsec.storage.redis.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCanonicalEntryTest {

    @Test
    void ownsDefensiveCopiesOfCanonicalKeyAndPayloadBytes() {
        byte[] key = bytes("key");
        byte[] payload = bytes("payload");

        RedisCanonicalEntry entry = new RedisCanonicalEntry(key, payload);
        key[0] = 'X';
        payload[0] = 'X';

        assertThat(entry.canonicalKey()).isEqualTo(bytes("key"));
        assertThat(entry.canonicalPayload()).isEqualTo(bytes("payload"));
        assertThat(entry.canonicalKeyLength()).isEqualTo(3);
        assertThat(entry.canonicalPayloadLength()).isEqualTo(7);

        byte[] returnedKey = entry.canonicalKey();
        byte[] returnedPayload = entry.canonicalPayload();
        returnedKey[0] = 'Y';
        returnedPayload[0] = 'Y';

        assertThat(entry.canonicalKey()).isEqualTo(bytes("key"));
        assertThat(entry.canonicalPayload()).isEqualTo(bytes("payload"));
    }

    @Test
    void comparesByOwnedByteContentAndRejectsNullArrays() {
        RedisCanonicalEntry baseline = entry("key", "payload");
        RedisCanonicalEntry equal = entry("key", "payload");

        assertThat(baseline).isEqualTo(equal).hasSameHashCodeAs(equal);
        assertThat(baseline).isNotEqualTo(entry("key", "other"));
        assertThat(baseline).isNotEqualTo(entry("other", "payload"));
        assertThat(baseline).isNotEqualTo(null).isNotEqualTo("entry");
        assertThatThrownBy(() -> new RedisCanonicalEntry(null, bytes("payload")))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("canonicalKey");
        assertThatThrownBy(() -> new RedisCanonicalEntry(bytes("key"), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("canonicalPayload");
    }

    @Test
    void canonicalizesArbitraryBatchOrderByUnsignedKeyBytes() {
        RedisCanonicalEntry zero = new RedisCanonicalEntry(new byte[]{0x00}, new byte[]{1});
        RedisCanonicalEntry ascii = new RedisCanonicalEntry(new byte[]{0x7f}, new byte[]{1});
        RedisCanonicalEntry high = new RedisCanonicalEntry(new byte[]{(byte) 0x80}, new byte[]{1});
        RedisCanonicalEntry highest =
            new RedisCanonicalEntry(new byte[]{(byte) 0xff}, new byte[]{1});

        List<RedisCanonicalEntry> sorted = RedisCanonicalEntryOrder.sortedCopy(
            List.of(highest, high, zero, ascii)
        );

        assertThat(sorted).containsExactly(zero, ascii, high, highest);
        assertThatThrownBy(() -> sorted.add(entry("later", "value")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sorterRejectsDuplicateKeysEvenWhenPayloadsDiffer() {
        assertThatThrownBy(() -> RedisCanonicalEntryOrder.sortedCopy(List.of(
            entry("same", "one"),
            entry("same", "two")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unique");
        assertThatThrownBy(() -> RedisCanonicalEntryOrder.sortedCopy(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("entries");
        assertThatThrownBy(() -> RedisCanonicalEntryOrder.sortedCopy(
            java.util.Arrays.asList(entry("key", "value"), null)
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("null");
    }

    @Test
    void rejectsEmptyCanonicalKeysAndPayloads() {
        assertThatThrownBy(() -> new RedisCanonicalEntry(new byte[0], bytes("payload")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("canonicalKey");
        assertThatThrownBy(() -> new RedisCanonicalEntry(bytes("key"), new byte[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("canonicalPayload");
    }

    private static RedisCanonicalEntry entry(String key, String payload) {
        return new RedisCanonicalEntry(bytes(key), bytes(payload));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
