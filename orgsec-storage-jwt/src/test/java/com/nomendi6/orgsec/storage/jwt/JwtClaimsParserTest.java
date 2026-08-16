package com.nomendi6.orgsec.storage.jwt;

import com.nomendi6.orgsec.model.PersonDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtClaimsParserTest {

    private static final String TOKEN = "validated-token";
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Test
    void shouldDecodeTokenBeforeReadingOrgSecClaims() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            "validated-token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("orgsec", Map.of(
                "version", "1.0",
                "person", Map.of("id", 42L, "name", "Alice"),
                "memberships", java.util.List.of()
            ))
        );
        when(decoder.decode("validated-token")).thenReturn(jwt);

        JwtClaimsParser parser = new JwtClaimsParser(OBJECT_MAPPER, decoder);

        PersonDef person = parser.parsePersonFromToken("validated-token");

        assertThat(person).isNotNull();
        assertThat(person.personId).isEqualTo(42L);
    }

    @Test
    void shouldReturnNullWhenJwtValidationFails() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("forged-token")).thenThrow(new JwtException("bad signature"));

        JwtClaimsParser parser = new JwtClaimsParser(OBJECT_MAPPER, decoder);

        assertThat(parser.parsePersonFromToken("forged-token")).isNull();
    }

    @Test
    void shouldReturnNullForValidatedTokenWithoutOrgSecClaim() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            TOKEN,
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("sub", "42")
        );
        when(decoder.decode(TOKEN)).thenReturn(jwt);

        JwtClaimsParser parser = new JwtClaimsParser(OBJECT_MAPPER, decoder);

        assertThat(parser.parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldReturnNullForMalformedOrgSecClaimShape() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            TOKEN,
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("orgsec", "not-an-object")
        );
        when(decoder.decode(TOKEN)).thenReturn(jwt);

        JwtClaimsParser parser = new JwtClaimsParser(OBJECT_MAPPER, decoder);

        assertThat(parser.parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldParseMapperCompatibleOrgSecClaimFixture() throws Exception {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Map<String, Object> orgsecClaim;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/orgsec-claim.json")) {
            assertThat(input).isNotNull();
            orgsecClaim = OBJECT_MAPPER.readValue(input, MAP_TYPE);
        }
        Jwt jwt = new Jwt(
            "fixture-token",
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("orgsec", orgsecClaim)
        );
        when(decoder.decode("fixture-token")).thenReturn(jwt);

        JwtClaimsParser parser = new JwtClaimsParser(OBJECT_MAPPER, decoder);

        JwtClaimsParser.ParsedPrincipal principal = parser.parsePrincipalFromToken("fixture-token");
        assertThat(principal).isNotNull();
        PersonDef person = principal.person();

        assertThat(person).isNotNull();
        assertThat(person.personId).isEqualTo(42L);
        assertThat(person.personName).isEqualTo("Alice OrgSec");
        assertThat(person.relatedUserId).isEqualTo("keycloak-user-42");
        assertThat(person.relatedUserLogin).isEqualTo("alice");
        assertThat(person.defaultCompanyId).isEqualTo(10L);
        assertThat(person.defaultOrgunitId).isEqualTo(101L);
        assertThat(person.organizationsMap).containsKeys(101L, 102L);
        assertThat(person.organizationsMap.get(101L).companyId).isEqualTo(10L);
        assertThat(person.organizationsMap.get(101L).pathId).isEqualTo("101");
        assertThat(principal.positionRoleIdsByOrganization().get(101L)).containsExactly(1001L, 1002L);
        assertThat(principal.positionRoleIdsByOrganization().get(102L)).isEmpty();
    }

    @Test
    void acceptsCanonicalLocalPathId() {
        PersonDef person = parserForClaim(claim(membership(22L, "ow"))).parsePersonFromToken(TOKEN);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap.get(22L).pathId).isEqualTo("ow");
        assertThat(person.organizationsMap.get(22L).parentPath).isNull();
    }

    @Test
    void acceptsAndNormalizesLegacyFullPathId() {
        PersonDef person = parserForClaim(claim(membership(22L, "|root|ow|"))).parsePersonFromToken(TOKEN);

        assertThat(person).isNotNull();
        assertThat(person.organizationsMap.get(22L).pathId).isEqualTo("ow");
        assertThat(person.organizationsMap.get(22L).parentPath).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "|", "|A|B", "|A||", "ow/../root", "|ow$|"})
    void rejectsWholeClaimForMalformedPathIdWithoutThrowing(String pathId) {
        assertThat(parserForClaim(claim(membership(22L, pathId))).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void rejectsUnsupportedClaimVersionWithoutThrowing() {
        Map<String, Object> claim = new HashMap<>(claim(membership(22L, "ow")));
        claim.put("version", "9.9");

        assertThat(parserForClaim(claim).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void rejectsDuplicateOrganizationMemberships() {
        Map<String, Object> claim = new HashMap<>();
        claim.put("version", "1.0");
        claim.put("person", Map.of("id", 42L, "name", "Alice"));
        claim.put("memberships", List.of(membership(22L, "ow"), membership(22L, "ow")));

        assertThat(parserForClaim(claim).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void rejectsMembershipWithoutOrganizationId() {
        assertThat(parserForClaim(claim(membership(null, "ow"))).parsePersonFromToken(TOKEN)).isNull();
    }

    private static JwtClaimsParser parserForClaim(Map<String, Object> orgsecClaim) {
        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            TOKEN,
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("orgsec", orgsecClaim)
        );
        when(decoder.decode(TOKEN)).thenReturn(jwt);
        return new JwtClaimsParser(OBJECT_MAPPER, decoder);
    }

    private static Map<String, Object> claim(Map<String, Object> membership) {
        Map<String, Object> claim = new HashMap<>();
        claim.put("version", "1.0");
        claim.put("person", Map.of("id", 42L, "name", "Alice"));
        claim.put("memberships", List.of(membership));
        return claim;
    }

    private static Map<String, Object> membership(Long organizationId, String pathId) {
        Map<String, Object> membership = new HashMap<>();
        membership.put("organizationId", organizationId);
        membership.put("companyId", 1L);
        membership.put("pathId", pathId);
        membership.put("positionRoleIds", List.of(101L));
        return membership;
    }
}
