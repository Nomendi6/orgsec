package com.nomendi6.orgsec.storage.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomendi6.orgsec.model.PersonDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtClaimsParserTest {

    private static final String TOKEN = "validated-token";

    @Test
    void shouldDecodeTokenBeforeReadingOrgSecClaims() {
        JwtClaimsParser parser = parserFor(claim(membership("22")));

        PersonDef person = parser.parsePersonFromToken(TOKEN);

        assertThat(person).isNotNull();
        assertThat(person.personId).isEqualTo(42L);
    }

    @Test
    void shouldReturnNullWhenJwtValidationFails() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("forged-token")).thenThrow(new JwtException("bad signature"));

        JwtClaimsParser parser = new JwtClaimsParser(new ObjectMapper(), decoder);

        assertThat(parser.parsePersonFromToken("forged-token")).isNull();
    }

    // ---------------------------------------------------------------- pathId dual-read

    @Test
    void shouldAcceptTheCanonicalLocalSegment() {
        PersonDef person = parserFor(claim(membership("ow"))).parsePersonFromToken(TOKEN);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap.get(22L).pathId).isEqualTo("ow");
    }

    @Test
    void shouldNormalizeALegacyFullPathToItsLastSegment() {
        // Tokens minted by older mapper builds carry the whole path. They stay valid.
        PersonDef person = parserFor(claim(membership("|root|ow|"))).parsePersonFromToken(TOKEN);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap.get(22L).pathId).isEqualTo("ow");
    }

    @Test
    void shouldAcceptANumericLocalSegment() {
        PersonDef person = parserFor(claim(membership("101"))).parsePersonFromToken(TOKEN);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap.get(22L).pathId).isEqualTo("101");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",              // blank
        "   ",           // whitespace only
        "|",             // bare separator - well-formed, but every path starts with it
        "|A|B",          // missing the closing separator
        "|A||",          // empty inner segment
        "|A|B|C",        // missing the closing separator, deeper
        "ow/../root",    // illegal characters in a local segment
        "|ow$|",         // illegal character inside a path
        "0123456789012345678901234567890123456789" // longer than the 30-char segment limit
    })
    void shouldRejectTheWholeClaimForAMalformedPathId(String pathId) {
        assertThat(parserFor(claim(membership(pathId))).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldRejectTheWholeClaimWhenPathIdIsAbsent() {
        Map<String, Object> membership = new HashMap<>();
        membership.put("organizationId", 22L);
        membership.put("companyId", 1L);
        membership.put("positionRoleIds", List.of(101L));

        assertThat(parserFor(claim(membership)).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldRejectTheWholeClaimWhenPathIdIsNull() {
        Map<String, Object> membership = new HashMap<>();
        membership.put("organizationId", 22L);
        membership.put("companyId", 1L);
        membership.put("pathId", null);
        membership.put("positionRoleIds", List.of(101L));

        assertThat(parserFor(claim(membership)).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldNotInventHierarchyAnchorsFromTheClaim() {
        // A pathId names the organization's own segment; it says nothing about its ancestry.
        // Deriving parentPath from it would be an authorization decision made on invented data.
        PersonDef person = parserFor(claim(membership("|root|ow|"))).parsePersonFromToken(TOKEN);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap.get(22L).parentPath).isNull();
        assertThat(person.organizationsMap.get(22L).companyParentPath).isNull();
    }

    // ---------------------------------------------------------------- total, fail-closed

    @Test
    void shouldReturnNullRatherThanThrowOnAnUnsupportedVersion() {
        Map<String, Object> orgsec = new HashMap<>(claim(membership("ow")));
        orgsec.put("version", "9.9");

        assertThat(parserFor(orgsec).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldReturnNullWhenPersonDataIsMissing() {
        Map<String, Object> orgsec = new HashMap<>();
        orgsec.put("version", "1.0");
        orgsec.put("memberships", List.of(membership("ow")));

        assertThat(parserFor(orgsec).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldNeverLetAnExceptionEscapeThePublicApi() {
        // The caller turns a null person into 401/403. An escaping exception would surface a
        // malformed token as HTTP 500 and put parser internals in the response body.
        for (String pathId : List.of("|", "|A|B", "ow$", "|A||")) {
            JwtClaimsParser parser = parserFor(claim(membership(pathId)));
            assertThatCode(() -> parser.parsePersonFromToken(TOKEN)).doesNotThrowAnyException();
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static JwtClaimsParser parserFor(Map<String, Object> orgsecClaim) {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            TOKEN,
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("orgsec", orgsecClaim)
        );
        when(decoder.decode(TOKEN)).thenReturn(jwt);
        return new JwtClaimsParser(new ObjectMapper(), decoder);
    }

    private static Map<String, Object> claim(Map<String, Object> membership) {
        Map<String, Object> orgsec = new HashMap<>();
        orgsec.put("version", "1.0");
        orgsec.put("person", Map.of("id", 42L, "name", "Alice"));
        orgsec.put("memberships", List.of(membership));
        return orgsec;
    }

    private static Map<String, Object> membership(String pathId) {
        Map<String, Object> membership = new HashMap<>();
        membership.put("organizationId", 22L);
        membership.put("companyId", 1L);
        membership.put("pathId", pathId);
        membership.put("positionRoleIds", List.of(101L));
        return membership;
    }
}
