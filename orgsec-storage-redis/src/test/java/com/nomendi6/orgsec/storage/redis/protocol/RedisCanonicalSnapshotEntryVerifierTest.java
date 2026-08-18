package com.nomendi6.orgsec.storage.redis.protocol;

import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.RoleDef;
import com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily.ORGANIZATIONS;
import static com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily.PARTY_ROLES;
import static com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily.PERSONS;
import static com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily.POSITION_ROLES;
import static com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily.PRIVILEGES;
import static com.nomendi6.orgsec.storage.redis.bootstrap.RedisSnapshotFamily.ROLES;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.ENTRY_MISSING;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.KEY_INVALID;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.KEY_PAYLOAD_IDENTITY_MISMATCH;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.PAYLOAD_IDENTITY_INVALID;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.PAYLOAD_INVALID;
import static com.nomendi6.orgsec.storage.redis.protocol.RedisSnapshotEntryCorruptionException.Reason.UNSUPPORTED_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCanonicalSnapshotEntryVerifierTest {

    private final RedisCanonicalSnapshotPayloadCodec codec =
        new RedisCanonicalSnapshotPayloadCodec();
    private final RedisCanonicalSnapshotEntryVerifier verifier =
        new RedisCanonicalSnapshotEntryVerifier();

    @Test
    void acceptsCanonicalIdentityBoundEntriesForAllSixFamilies() {
        PersonDef person = new PersonDef(Long.MIN_VALUE, "person");
        OrganizationDef organization = new OrganizationDef().setOrganizationId(
            Long.MAX_VALUE
        );

        assertVerified(PERSONS, Long.toString(Long.MIN_VALUE), codec.encode(person));
        assertVerified(
            ORGANIZATIONS,
            Long.toString(Long.MAX_VALUE),
            codec.encode(organization)
        );
        assertVerified(PARTY_ROLES, "-8", codec.encode(new RoleDef(-8L, "party")));
        assertVerified(
            POSITION_ROLES,
            "0",
            codec.encode(new RoleDef(0L, "position"))
        );
        assertVerified(ROLES, "9", codec.encode(new RoleDef(9L, "legacy")));
        assertVerified(
            PRIVILEGES,
            "račun:čitaj",
            codec.encode(new PrivilegeDef("račun:čitaj", "račun"))
        );
    }

    @Test
    void rejectsCanonicalKeysThatDoNotMatchDecodedRootIdentities() {
        assertCorrupt(
            PERSONS,
            entry("2", codec.encode(new PersonDef(1L, "person"))),
            KEY_PAYLOAD_IDENTITY_MISMATCH
        );
        assertCorrupt(
            ORGANIZATIONS,
            entry("2", codec.encode(new OrganizationDef().setOrganizationId(1L))),
            KEY_PAYLOAD_IDENTITY_MISMATCH
        );
        for (RedisSnapshotFamily family : new RedisSnapshotFamily[] {
            PARTY_ROLES,
            POSITION_ROLES,
            ROLES
        }) {
            assertCorrupt(
                family,
                entry("2", codec.encode(new RoleDef(1L, "role"))),
                KEY_PAYLOAD_IDENTITY_MISMATCH
            );
        }
    }

    @Test
    void rejectsMalformedAndNonCanonicalDecimalIdentityKeys() {
        byte[] payload = codec.encode(new PersonDef(1L, "person"));
        String[] invalidKeys = {
            "+1",
            "01",
            "-0",
            "-",
            " 1",
            "1 ",
            "1.0",
            "9223372036854775808",
            "-9223372036854775809",
            "١",
            "123456789012345678901"
        };

        for (String invalidKey : invalidKeys) {
            assertCorrupt(PERSONS, entry(invalidKey, payload), KEY_INVALID);
        }
        assertCorrupt(
            PERSONS,
            new RedisCanonicalEntry(new byte[] { (byte) 0xff }, payload),
            KEY_INVALID
        );
    }

    @Test
    void rejectsPayloadForTheWrongFamilyAndNonCanonicalJson() {
        assertCorrupt(
            PERSONS,
            entry("1", codec.encode(new RoleDef(1L, "role"))),
            PAYLOAD_INVALID
        );

        byte[] canonical = codec.encode(new PersonDef(1L, "person"));
        byte[] withTrailingWhitespace = Arrays.copyOf(canonical, canonical.length + 1);
        withTrailingWhitespace[canonical.length] = '\n';
        assertCorrupt(PERSONS, entry("1", withTrailingWhitespace), PAYLOAD_INVALID);

        String futureSubtypeShape = new String(canonical, StandardCharsets.UTF_8)
            .replace("{\"personId\":1", "{\"futureType\":\"x\",\"personId\":1");
        assertCorrupt(
            PERSONS,
            entry("1", futureSubtypeShape.getBytes(StandardCharsets.UTF_8)),
            PAYLOAD_INVALID
        );
    }

    @Test
    void requiresAWellFormedBoundedNonBlankPrivilegeKeyAndRootName() {
        byte[] ordinaryPayload = codec.encode(new PrivilegeDef("read", "resource"));
        assertCorrupt(PRIVILEGES, entry(" ", ordinaryPayload), KEY_INVALID);
        assertCorrupt(
            PRIVILEGES,
            new RedisCanonicalEntry(new byte[] { (byte) 0xc3, (byte) 0x28 }, ordinaryPayload),
            KEY_INVALID
        );
        assertCorrupt(
            PRIVILEGES,
            entry("a".repeat(256), ordinaryPayload),
            KEY_INVALID
        );
        assertCorrupt(
            PRIVILEGES,
            entry("界".repeat(255) + "a", ordinaryPayload),
            KEY_INVALID
        );

        assertCorrupt(
            PRIVILEGES,
            entry("read", codec.encode(new PrivilegeDef(null, "resource"))),
            PAYLOAD_IDENTITY_INVALID
        );
        assertCorrupt(
            PRIVILEGES,
            entry("read", codec.encode(new PrivilegeDef("\t", "resource"))),
            PAYLOAD_IDENTITY_INVALID
        );
        assertCorrupt(
            PRIVILEGES,
            entry("read", codec.encode(new PrivilegeDef("write", "resource"))),
            KEY_PAYLOAD_IDENTITY_MISMATCH
        );
    }

    @Test
    void acceptsTheExactPrivilegeStringBoundsAndRejectsTheNextUnitOrByte() {
        String exactMaximum = "界".repeat(
            RedisCanonicalSnapshotPayloadCodec.MAX_STRING_UTF16_UNITS
        );
        assertThat(exactMaximum.getBytes(StandardCharsets.UTF_8)).hasSize(
            RedisCanonicalSnapshotPayloadCodec.MAX_STRING_UTF8_BYTES
        );
        assertVerified(
            PRIVILEGES,
            exactMaximum,
            codec.encode(new PrivilegeDef(exactMaximum, "resource"))
        );

        byte[] ordinaryPayload = codec.encode(new PrivilegeDef("read", "resource"));
        assertCorrupt(
            PRIVILEGES,
            entry("a".repeat(
                RedisCanonicalSnapshotPayloadCodec.MAX_STRING_UTF16_UNITS + 1
            ), ordinaryPayload),
            KEY_INVALID
        );
        assertCorrupt(
            PRIVILEGES,
            entry(exactMaximum + "a", ordinaryPayload),
            KEY_INVALID
        );
    }

    @Test
    void delegatesPayloadSizeBoundsToTheExactFamilyCodec() {
        byte[] oversized = new byte[
            RedisCanonicalSnapshotPayloadCodec.MAX_PERSON_PAYLOAD_BYTES + 1
        ];

        assertCorrupt(PERSONS, entry("1", oversized), PAYLOAD_INVALID);
    }

    @Test
    void rejectsMissingInputsAndKeepsAllDiagnosticsSanitized() {
        assertCorrupt(null, entry("1", codec.encode(new PersonDef(1L, "person"))),
            UNSUPPORTED_FAMILY);
        assertThatThrownBy(() -> verifier.verify(PERSONS, null))
            .isInstanceOfSatisfying(
                RedisSnapshotEntryCorruptionException.class,
                failure -> assertThat(failure.reason()).isEqualTo(ENTRY_MISSING)
            )
            .hasMessage(
                RedisSnapshotEntryCorruptionException.DIAGNOSTIC_CODE + ":ENTRY_MISSING"
            );

        String secret = "attacker-secret-key";
        RedisCanonicalEntry corrupt = entry(secret, "attacker-secret-payload".getBytes(
            StandardCharsets.UTF_8
        ));
        assertThatThrownBy(() -> verifier.verify(PRIVILEGES, corrupt))
            .isInstanceOfSatisfying(
                RedisSnapshotEntryCorruptionException.class,
                failure -> {
                    assertThat(failure.reason()).isEqualTo(PAYLOAD_INVALID);
                    assertThat(failure.getCause()).isNull();
                }
            )
            .hasMessage(
                RedisSnapshotEntryCorruptionException.DIAGNOSTIC_CODE +
                ":PAYLOAD_INVALID"
            )
            .hasMessageNotContaining(secret)
            .hasMessageNotContaining("attacker-secret-payload");
    }

    private void assertVerified(
        RedisSnapshotFamily family,
        String canonicalKey,
        byte[] canonicalPayload
    ) {
        assertThatCode(() -> verifier.verify(family, entry(canonicalKey, canonicalPayload)))
            .doesNotThrowAnyException();
    }

    private void assertCorrupt(
        RedisSnapshotFamily family,
        RedisCanonicalEntry entry,
        RedisSnapshotEntryCorruptionException.Reason expectedReason
    ) {
        assertThatThrownBy(() -> verifier.verify(family, entry))
            .isInstanceOfSatisfying(
                RedisSnapshotEntryCorruptionException.class,
                failure -> {
                    assertThat(failure.reason()).isEqualTo(expectedReason);
                    assertThat(failure.getCause()).isNull();
                }
            )
            .hasMessage(
                RedisSnapshotEntryCorruptionException.DIAGNOSTIC_CODE + ":" +
                expectedReason.name()
            );
    }

    private static RedisCanonicalEntry entry(String key, byte[] payload) {
        return new RedisCanonicalEntry(key.getBytes(StandardCharsets.UTF_8), payload);
    }
}
