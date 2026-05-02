package com.nomendi6.orgsec.storage.jwt;

import com.nomendi6.orgsec.model.PersonDef;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtClaimsParserTest {

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

        PersonDef person = parser.parsePersonFromToken("fixture-token");
        List<Long> positionRoleIds = parser.getPositionRoleIds("fixture-token", 101L);

        assertThat(person).isNotNull();
        assertThat(person.personId).isEqualTo(42L);
        assertThat(person.personName).isEqualTo("Alice OrgSec");
        assertThat(person.relatedUserId).isEqualTo("keycloak-user-42");
        assertThat(person.relatedUserLogin).isEqualTo("alice");
        assertThat(person.defaultCompanyId).isEqualTo(10L);
        assertThat(person.defaultOrgunitId).isEqualTo(101L);
        assertThat(person.organizationsMap).containsKeys(101L, 102L);
        assertThat(person.organizationsMap.get(101L).companyId).isEqualTo(10L);
        assertThat(person.organizationsMap.get(101L).pathId).isEqualTo("|10|101|");
        assertThat(positionRoleIds).containsExactly(1001L, 1002L);
        assertThat(parser.getPositionRoleIds("fixture-token", 102L)).isEmpty();
    }
}
