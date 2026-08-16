package com.nomendi6.orgsec.storage.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two claim entries for the same organization reject the whole claim.
 *
 * <p>Memberships are keyed by {@code organizationId}, so a second entry silently overwrote the
 * first. Which one survived was decided by claim ordering, and the two can disagree on exactly the
 * fields authorization is decided from - the company the organization belongs to, and which position
 * roles apply there. That makes a duplicate a way to get a privileged entry evaluated while a
 * plausible-looking one is what an inspector sees, so the claim is not repaired, it is refused.
 */
class JwtDuplicateMembershipTest {

    private static final String TOKEN = "duplicate-membership-token";

    @Test
    void shouldRejectTheWholeClaimWhenOneOrganizationAppearsTwice() {
        // First entry: a company the person is not in, carrying the privileged roles.
        // Second entry: the same organization, honestly described, without those roles.
        Map<String, Object> privileged = membership(22L, 999L, List.of(101L, 205L));
        Map<String, Object> honest = membership(22L, 1L, List.of());

        assertThat(parserFor(privileged, honest).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldRejectRegardlessOfWhichEntryComesFirst() {
        Map<String, Object> honest = membership(22L, 1L, List.of());
        Map<String, Object> privileged = membership(22L, 999L, List.of(101L, 205L));

        assertThat(parserFor(honest, privileged).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldRejectTheWholeClaimWhenAMembershipHasNoOrganizationId() {
        Map<String, Object> anonymous = membership(null, 1L, List.of(101L));

        assertThat(parserFor(anonymous).parsePersonFromToken(TOKEN)).isNull();
    }

    @Test
    void shouldAcceptDistinctOrganizations() {
        assertThat(parserFor(membership(22L, 1L, List.of(101L)), membership(23L, 1L, List.of(205L)))
            .parsePersonFromToken(TOKEN))
            .isNotNull();
    }

    @Test
    void shouldPairEachOrganizationWithItsOwnRoleIds() {
        JwtClaimsParser.ParsedPrincipal principal = parserFor(
            membership(22L, 1L, List.of(101L)),
            membership(23L, 1L, List.of(205L, 307L))
        ).parsePrincipalFromToken(TOKEN);

        assertThat(principal).isNotNull();
        assertThat(principal.positionRoleIdsByOrganization())
            .containsExactlyInAnyOrderEntriesOf(Map.of(22L, List.of(101L), 23L, List.of(205L, 307L)));
        assertThat(principal.person().organizationsMap.keySet())
            .as("the role-id map and the membership map must describe the same organizations")
            .isEqualTo(principal.positionRoleIdsByOrganization().keySet());
    }

    // --- fixture ------------------------------------------------------------------------------

    @SafeVarargs
    private static JwtClaimsParser parserFor(Map<String, Object>... memberships) {
        Map<String, Object> orgsec = new HashMap<>();
        orgsec.put("version", "1.0");
        orgsec.put("person", Map.of("id", 42L, "name", "Alice"));
        orgsec.put("memberships", List.of(memberships));

        JwtDecoder decoder = mock(JwtDecoder.class);
        Jwt jwt = new Jwt(
            TOKEN,
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of("orgsec", orgsec)
        );
        when(decoder.decode(TOKEN)).thenReturn(jwt);
        return new JwtClaimsParser(new ObjectMapper(), decoder);
    }

    private static Map<String, Object> membership(Long organizationId, Long companyId, List<Long> positionRoleIds) {
        Map<String, Object> membership = new HashMap<>();
        membership.put("organizationId", organizationId);
        membership.put("companyId", companyId);
        membership.put("pathId", organizationId == null ? "22" : String.valueOf(organizationId));
        membership.put("positionRoleIds", positionRoleIds);
        return membership;
    }
}
