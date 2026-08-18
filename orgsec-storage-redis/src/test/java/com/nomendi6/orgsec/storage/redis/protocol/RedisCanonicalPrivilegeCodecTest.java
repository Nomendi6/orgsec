package com.nomendi6.orgsec.storage.redis.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.PrivilegeDef;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCanonicalPrivilegeCodecTest {

    private static final String NULLABLE_CANONICAL =
        "{\"name\":null,\"resourceName\":null,\"operation\":\"NONE\",\"all\":false," +
        "\"company\":\"NONE\",\"org\":\"NONE\",\"person\":false}";

    private final RedisCanonicalSnapshotPayloadCodec codec =
        new RedisCanonicalSnapshotPayloadCodec();

    @Test
    void hasStableGoldenBytesForAFullPrivilegeAndRoundTripsIt() {
        PrivilegeDef privilege = new PrivilegeDef("račun-\"čitaj\"\n", "račun/東京")
            .allowOperation(PrivilegeOperation.EXECUTE)
            .allowAll(true)
            .allowOrg(
                PrivilegeDirection.HIERARCHY_DOWN,
                PrivilegeDirection.HIERARCHY_UP,
                true
            );
        String golden =
            "{\"name\":\"račun-\\\"čitaj\\\"\\n\",\"resourceName\":\"račun/東京\"," +
            "\"operation\":\"EXECUTE\",\"all\":true,\"company\":\"HIERARCHY_DOWN\"," +
            "\"org\":\"HIERARCHY_UP\",\"person\":true}";

        byte[] encoded = codec.encode(privilege);

        assertThat(encoded).containsExactly(golden.getBytes(StandardCharsets.UTF_8));
        assertThat(codec.decode(encoded)).isEqualTo(privilege);
        assertThat(codec.decode(encoded).getClass()).isEqualTo(PrivilegeDef.class);
    }

    @Test
    void encodesExplicitNullsAndEveryCurrentEnumToken() {
        PrivilegeDef nullable = new PrivilegeDef();
        assertThat(codec.encode(nullable))
            .containsExactly(NULLABLE_CANONICAL.getBytes(StandardCharsets.UTF_8));
        assertThat(codec.decode(bytes(NULLABLE_CANONICAL))).isEqualTo(nullable);

        for (PrivilegeOperation operation : PrivilegeOperation.values()) {
            PrivilegeDef privilege = new PrivilegeDef("p", "r").allowOperation(operation);
            byte[] encoded = codec.encode(privilege);
            assertThat(text(encoded)).contains("\"operation\":\"" + operation.name() + "\"");
            assertThat(codec.decode(encoded).operation).isEqualTo(operation);
        }

        for (PrivilegeDirection direction : PrivilegeDirection.values()) {
            PrivilegeDef privilege = new PrivilegeDef("p", "r")
                .allowOrg(direction, direction, false);
            byte[] encoded = codec.encode(privilege);
            assertThat(text(encoded))
                .contains("\"company\":\"" + direction.name() + "\"")
                .contains("\"org\":\"" + direction.name() + "\"");
            PrivilegeDef decoded = codec.decode(encoded);
            assertThat(decoded.company).isEqualTo(direction);
            assertThat(decoded.org).isEqualTo(direction);
        }
    }

    @Test
    void preservesUnicodeWithoutConflatingNfcAndNfd() {
        String nfc = "é";
        String nfd = "e\u0301";
        PrivilegeDef composed = new PrivilegeDef(nfc, "東京/🛡");
        PrivilegeDef decomposed = new PrivilegeDef(nfd, "東京/🛡");

        byte[] composedBytes = codec.encode(composed);
        byte[] decomposedBytes = codec.encode(decomposed);

        assertThat(composedBytes).isNotEqualTo(decomposedBytes);
        assertThat(text(composedBytes)).contains("\"name\":\"é\"");
        assertThat(text(decomposedBytes)).contains("\"name\":\"é\"");
        assertThat(codec.decode(composedBytes).name).isEqualTo(nfc);
        assertThat(codec.decode(decomposedBytes).name).isEqualTo(nfd);
    }

    @Test
    void standaloneCodecOwnsCanonicalGeneratorConfiguration() throws Exception {
        PrivilegeDef privilege = new PrivilegeDef("é", "folder/path")
            .allowOperation(PrivilegeOperation.READ);
        byte[] canonical = codec.encode(privilege);
        JsonFactory externallyConfiguredFactory = JsonFactory.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
            .enable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
            .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (JsonGenerator generator = externallyConfiguredFactory.createGenerator(output)) {
            generator.useDefaultPrettyPrinter();
            generator.writeStartObject();
            generator.writeStringField("name", privilege.name);
            generator.writeStringField("resourceName", privilege.resourceName);
            generator.writeStringField("operation", privilege.operation.name());
            generator.writeBooleanField("all", privilege.all);
            generator.writeStringField("company", privilege.company.name());
            generator.writeStringField("org", privilege.org.name());
            generator.writeBooleanField("person", privilege.person);
            generator.writeEndObject();
        }

        byte[] externallyRendered = output.toByteArray();
        assertThat(text(canonical)).isEqualTo(
            "{\"name\":\"é\",\"resourceName\":\"folder/path\",\"operation\":\"READ\"," +
            "\"all\":false,\"company\":\"NONE\",\"org\":\"NONE\",\"person\":false}"
        );
        assertThat(text(externallyRendered)).contains("\\u00E9", "folder\\/path", "\n");
        assertThat(externallyRendered).isNotEqualTo(canonical);
        assertCorrupt(externallyRendered, "exact canonical encoding");
        assertThat(codec.encode(privilege)).containsExactly(canonical);
    }

    @Test
    void rejectsNullSubtypeInvalidEnumAndInvalidUnicodeOnEncode() {
        assertEncodeFailure(null, "privilege");
        assertEncodeFailure(new DerivedPrivilegeDef(), "exact PrivilegeDef class");

        PrivilegeDef nullOperation = new PrivilegeDef();
        nullOperation.operation = null;
        assertEncodeFailure(nullOperation, "operation");
        PrivilegeDef nullCompany = new PrivilegeDef();
        nullCompany.company = null;
        assertEncodeFailure(nullCompany, "company");
        PrivilegeDef nullOrg = new PrivilegeDef();
        nullOrg.org = null;
        assertEncodeFailure(nullOrg, "org");

        assertEncodeFailure(new PrivilegeDef("a".repeat(256), "r"), "name", "255");
        assertEncodeFailure(new PrivilegeDef("bad-\ud800", "r"), "name", "well-formed Unicode");

        String largestThreeByteName = "界".repeat(255);
        PrivilegeDef largest = new PrivilegeDef(largestThreeByteName, largestThreeByteName);
        assertThat(codec.decode(codec.encode(largest))).isEqualTo(largest);
    }

    @Test
    void rejectsNullEmptyOversizedAndMalformedUtf8Input() {
        assertCorrupt((byte[]) null, "payload is null");
        assertCorrupt(new byte[0], "payload is empty");
        assertCorrupt(
            new byte[RedisCanonicalSnapshotPayloadCodec.MAX_PAYLOAD_BYTES + 1],
            "exceeds",
            Integer.toString(RedisCanonicalSnapshotPayloadCodec.MAX_PAYLOAD_BYTES)
        );
        assertCorrupt(new byte[] { '{', '"', 'n', 'a', 'm', 'e', '"', ':', '"',
            (byte) 0xc3, (byte) 0x28 }, "strict JSON");
    }

    @Test
    void rejectsMissingUnknownDuplicateOutOfOrderAndTrailingFields() {
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace(",\"person\":false", "")),
            "missing required field person"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("{\"name\":null", "{\"future\":null")),
            "unknown field"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace(
                "\"name\":null,",
                "\"name\":null,\"name\":null,"
            )),
            "strict JSON"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace(
                "\"name\":null,\"resourceName\":null",
                "\"resourceName\":null,\"name\":null"
            )),
            "field order",
            "name"
        );
        assertCorrupt(bytes(NULLABLE_CANONICAL + "{}"), "trailing JSON content");
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.substring(0, NULLABLE_CANONICAL.length() - 1) +
                ",\"future\":false}"),
            "unknown field"
        );
    }

    @Test
    void rejectsNonCanonicalJsonSpellingEvenWhenItsMeaningMatches() {
        assertCorrupt(bytes(" " + NULLABLE_CANONICAL), "exact canonical encoding");
        assertCorrupt(bytes(NULLABLE_CANONICAL + "\n"), "exact canonical encoding");
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("\"name\"", "\"n\\u0061me\"")),
            "exact canonical encoding"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("\"name\":null", "\"name\":\"\\u00e9\"")),
            "exact canonical encoding"
        );
    }

    @Test
    void rejectsWrongValueKindsUnknownEnumsAndMalformedUnicode() {
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("\"operation\":\"NONE\"", "\"operation\":null")),
            "operation",
            "non-null string"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("\"operation\":\"NONE\"", "\"operation\":\"read\"")),
            "operation",
            "enum token"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("\"company\":\"NONE\"", "\"company\":\"SIDEWAYS\"")),
            "direction",
            "enum token"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("\"all\":false", "\"all\":\"false\"")),
            "all",
            "JSON boolean"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("\"name\":null", "\"name\":{}")),
            "name must be a string or explicit null"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace("\"name\":null", "\"name\":\"\\ud800\"")),
            "name",
            "well-formed Unicode"
        );
        assertCorrupt(
            bytes(NULLABLE_CANONICAL.replace(
                "\"name\":null",
                "\"name\":\"" + "a".repeat(256) + "\""
            )),
            "strict JSON"
        );
        assertCorrupt(bytes("[]"), "root object");
        assertCorrupt(bytes("null"), "root object");
        assertCorrupt(bytes("{"), "strict JSON");
    }

    @Test
    void corruptionFailureHasAStableNonPayloadDiagnosticCode() {
        assertThatThrownBy(() -> codec.decode(bytes("broken")))
            .isInstanceOf(
                RedisCanonicalSnapshotPayloadCodec.CorruptPrivilegePayloadException.class
            )
            .hasMessageStartingWith(
                RedisCanonicalSnapshotPayloadCodec.CorruptPrivilegePayloadException
                    .DIAGNOSTIC_CODE
            )
            .hasMessageNotContaining("broken");
    }

    private void assertEncodeFailure(PrivilegeDef privilege, String... messageParts) {
        assertThatThrownBy(() -> codec.encode(privilege))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContainingAll(messageParts);
    }

    private void assertCorrupt(String json, String... messageParts) {
        assertCorrupt(bytes(json), messageParts);
    }

    private void assertCorrupt(byte[] payload, String... messageParts) {
        assertThatThrownBy(() -> codec.decode(payload))
            .isInstanceOf(
                RedisCanonicalSnapshotPayloadCodec.CorruptPrivilegePayloadException.class
            )
            .hasMessageStartingWith(
                RedisCanonicalSnapshotPayloadCodec.CorruptPrivilegePayloadException
                    .DIAGNOSTIC_CODE
            )
            .hasMessageContainingAll(messageParts);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new AssertionError("codec produced malformed UTF-8", exception);
        }
    }

    private static final class DerivedPrivilegeDef extends PrivilegeDef {}
}
