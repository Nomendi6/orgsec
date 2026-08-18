package com.nomendi6.orgsec.storage.redis.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Binary digest framing primitives shared by the inert snapshot digest model.
 */
final class RedisSnapshotDigestSupport {

    private RedisSnapshotDigestSupport() {
    }

    static MessageDigest sha256(String domain) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
        updateFrame(digest, domain.getBytes(StandardCharsets.US_ASCII));
        return digest;
    }

    static void updateFamilyCode(MessageDigest digest, RedisSnapshotFamilyCode family) {
        updateFrame(digest, new byte[]{family.wireCode()});
    }

    static void updateFrame(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }
}
