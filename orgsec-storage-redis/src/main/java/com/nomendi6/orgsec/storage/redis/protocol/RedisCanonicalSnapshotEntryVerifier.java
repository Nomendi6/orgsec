package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.ENTRY_MISSING;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.KEY_INVALID;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.KEY_PAYLOAD_IDENTITY_MISMATCH;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.PAYLOAD_IDENTITY_INVALID;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.PAYLOAD_INVALID;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.UNSUPPORTED_FAMILY;

/**
 * Fail-closed identity verifier for one canonical entry in an exact snapshot family.
 *
 * <p>Payload decoding delegates to {@link RedisCanonicalSnapshotPayloadCodec}, which enforces
 * the complete bounded schema and byte-for-byte canonical re-encoding. This boundary additionally
 * binds the Redis hash/index key to the decoded root identity before an entry may be staged or
 * accumulated by a reader.</p>
 */
final class RedisCanonicalSnapshotEntryVerifier {

    private static final int MAX_CANONICAL_LONG_KEY_BYTES = 20;

    private final RedisCanonicalSnapshotPayloadCodec payloadCodec =
        new RedisCanonicalSnapshotPayloadCodec();

    void verify(RedisSnapshotFamily family, RedisCanonicalEntry entry) {
        if (family == null) {
            throw corrupt(UNSUPPORTED_FAMILY);
        }
        if (entry == null) {
            throw corrupt(ENTRY_MISSING);
        }

        switch (family) {
            case PERSONS -> verifyPerson(entry);
            case ORGANIZATIONS -> verifyOrganization(entry);
            case PARTY_ROLES, POSITION_ROLES, ROLES -> verifyRole(entry);
            case PRIVILEGES -> verifyPrivilege(entry);
            default -> throw corrupt(UNSUPPORTED_FAMILY);
        }
    }

    private void verifyPerson(RedisCanonicalEntry entry) {
        requireEntryBounds(
            entry,
            MAX_CANONICAL_LONG_KEY_BYTES,
            RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES
        );
        long key = decodeCanonicalLongKey(entry.canonicalKey());
        PersonDef person = decodePayload(entry.canonicalPayload(), payloadCodec::decodePerson);
        requireMatchingIdentity(key, person.personId);
    }

    private void verifyOrganization(RedisCanonicalEntry entry) {
        requireEntryBounds(
            entry,
            MAX_CANONICAL_LONG_KEY_BYTES,
            RedisCanonicalSnapshotPayloadCodec.MAX_ORGANIZATION_PAYLOAD_BYTES
        );
        long key = decodeCanonicalLongKey(entry.canonicalKey());
        OrganizationDef organization = decodePayload(
            entry.canonicalPayload(),
            payloadCodec::decodeOrganization
        );
        requireMatchingIdentity(key, organization.organizationId);
    }

    private void verifyRole(RedisCanonicalEntry entry) {
        requireEntryBounds(
            entry,
            MAX_CANONICAL_LONG_KEY_BYTES,
            RedisCanonicalSnapshotPayloadCodec.MAX_ROLE_PAYLOAD_BYTES
        );
        long key = decodeCanonicalLongKey(entry.canonicalKey());
        RoleDef role = decodePayload(entry.canonicalPayload(), payloadCodec::decodeRole);
        requireMatchingIdentity(key, role.roleId);
    }

    private void verifyPrivilege(RedisCanonicalEntry entry) {
        requireEntryBounds(
            entry,
            RedisCanonicalSnapshotPayloadCodec.MAX_STRING_UTF8_BYTES,
            RedisCanonicalSnapshotPayloadCodec.MAX_PRIVILEGE_PAYLOAD_BYTES
        );
        String key = decodeCanonicalPrivilegeKey(entry.canonicalKey());
        PrivilegeDef privilege = decodePayload(entry.canonicalPayload(), payloadCodec::decode);
        if (privilege.name == null || privilege.name.isBlank()) {
            throw corrupt(PAYLOAD_IDENTITY_INVALID);
        }
        if (!key.equals(privilege.name)) {
            throw corrupt(KEY_PAYLOAD_IDENTITY_MISMATCH);
        }
    }

    private static void requireEntryBounds(
        RedisCanonicalEntry entry,
        int maxKeyBytes,
        int maxPayloadBytes
    ) {
        if (entry.canonicalKeyLength() > maxKeyBytes) {
            throw corrupt(KEY_INVALID);
        }
        if (entry.canonicalPayloadLength() > maxPayloadBytes) {
            throw corrupt(PAYLOAD_INVALID);
        }
    }

    private static long decodeCanonicalLongKey(byte[] key) {
        if (key.length == 0 || key.length > MAX_CANONICAL_LONG_KEY_BYTES) {
            throw corrupt(KEY_INVALID);
        }
        for (int index = 0; index < key.length; index++) {
            int value = key[index] & 0xff;
            if (index == 0 && value == '-') {
                if (key.length == 1) {
                    throw corrupt(KEY_INVALID);
                }
                continue;
            }
            if (value < '0' || value > '9') {
                throw corrupt(KEY_INVALID);
            }
        }

        final long parsed;
        try {
            parsed = Long.parseLong(new String(key, StandardCharsets.US_ASCII));
        } catch (NumberFormatException exception) {
            throw corrupt(KEY_INVALID);
        }
        if (!Arrays.equals(key, Long.toString(parsed).getBytes(StandardCharsets.US_ASCII))) {
            throw corrupt(KEY_INVALID);
        }
        return parsed;
    }

    private static String decodeCanonicalPrivilegeKey(byte[] key) {
        if (key.length == 0 ||
            key.length > RedisCanonicalSnapshotPayloadCodec.MAX_STRING_UTF8_BYTES) {
            throw corrupt(KEY_INVALID);
        }

        final String decoded;
        try {
            CharBuffer characters = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(key));
            decoded = characters.toString();
        } catch (CharacterCodingException exception) {
            throw corrupt(KEY_INVALID);
        }
        if (decoded.length() > RedisCanonicalSnapshotPayloadCodec.MAX_STRING_UTF16_UNITS ||
            decoded.isBlank()) {
            throw corrupt(KEY_INVALID);
        }
        return decoded;
    }

    private static void requireMatchingIdentity(long key, Long payloadIdentity) {
        if (payloadIdentity == null) {
            throw corrupt(PAYLOAD_IDENTITY_INVALID);
        }
        if (payloadIdentity.longValue() != key) {
            throw corrupt(KEY_PAYLOAD_IDENTITY_MISMATCH);
        }
    }

    private static <T> T decodePayload(byte[] payload, PayloadDecoder<T> decoder) {
        try {
            return decoder.decode(payload);
        } catch (RuntimeException exception) {
            throw corrupt(PAYLOAD_INVALID);
        }
    }

    private static RedisSnapshotEntryCorruptionException corrupt(
        RedisSnapshotEntryCorruptionException.Reason reason
    ) {
        return new RedisSnapshotEntryCorruptionException(reason);
    }

    @FunctionalInterface
    private interface PayloadDecoder<T> {
        T decode(byte[] payload);
    }
}
