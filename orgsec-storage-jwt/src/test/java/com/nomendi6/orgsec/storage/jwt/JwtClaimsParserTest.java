package com.nomendi6.orgsec.storage.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomendi6.orgsec.model.PersonDef;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtClaimsParserTest {

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

        JwtClaimsParser parser = new JwtClaimsParser(new ObjectMapper(), decoder);

        PersonDef person = parser.parsePersonFromToken("validated-token");

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
}
