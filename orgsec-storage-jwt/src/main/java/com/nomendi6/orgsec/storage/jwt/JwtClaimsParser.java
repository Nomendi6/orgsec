package com.nomendi6.orgsec.storage.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomendi6.orgsec.exceptions.OrgsecSecurityException;
import com.nomendi6.orgsec.helper.PathSanitizer;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.jwt.dto.MembershipClaimDTO;
import com.nomendi6.orgsec.storage.jwt.dto.OrgSecClaimsDTO;
import com.nomendi6.orgsec.storage.jwt.dto.PersonClaimDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for OrgSec claims from JWT token.
 */
@Component
public class JwtClaimsParser {

    private static final Logger log = LoggerFactory.getLogger(JwtClaimsParser.class);

    private static final String DEFAULT_CLAIM_NAME = "orgsec";
    private static final String DEFAULT_CLAIM_VERSION = "1.0";

    private final ObjectMapper objectMapper;
    private final JwtDecoder jwtDecoder;
    private final String claimName;
    private final String claimVersion;

    public JwtClaimsParser(ObjectMapper objectMapper, JwtDecoder jwtDecoder) {
        this(objectMapper, jwtDecoder, DEFAULT_CLAIM_NAME, DEFAULT_CLAIM_VERSION);
    }

    public JwtClaimsParser(ObjectMapper objectMapper, JwtDecoder jwtDecoder, String claimName) {
        this(objectMapper, jwtDecoder, claimName, DEFAULT_CLAIM_VERSION);
    }

    public JwtClaimsParser(ObjectMapper objectMapper, JwtDecoder jwtDecoder, String claimName, String claimVersion) {
        this.objectMapper = objectMapper;
        this.jwtDecoder = jwtDecoder;
        this.claimName = claimName;
        this.claimVersion = claimVersion;
    }

    /**
     * Parse PersonDef from JWT token string.
     *
     * <p>Fail-closed and total: any problem with the claim - a rejected token, a missing or
     * unsupported claim, a malformed {@code pathId} - yields {@code null}, never an exception. A
     * claim OrgSec cannot fully understand is not partially trusted; the whole principal is
     * discarded, and the caller treats a {@code null} person as "not authorized". Letting an
     * exception out would surface a malformed token as HTTP 500 instead of 401/403, and would put
     * the parser's internals in a response body.
     *
     * @param jwtToken the JWT token string (without Bearer prefix)
     * @return PersonDef, or null if the claim is absent, unsupported or not fully valid
     */
    public PersonDef parsePersonFromToken(String jwtToken) {
        ParsedPrincipal principal = parsePrincipalFromToken(jwtToken);
        return principal != null ? principal.person() : null;
    }

    /**
     * A principal and the position-role ids that came with it, in one indivisible value.
     *
     * <p>The two used to be read from the token separately - the person here, the role ids again
     * later, per organization. Reading twice means the pairing can differ between the two reads, and
     * the second read had no idea which organizations the first one had accepted. Parsing once and
     * carrying the result together removes the question.
     *
     * @param person the principal, with one entry in {@code organizationsMap} per accepted membership
     * @param positionRoleIdsByOrganization role ids per organization id, matching that map exactly
     */
    public record ParsedPrincipal(PersonDef person, Map<Long, List<Long>> positionRoleIdsByOrganization) {}

    /**
     * Parse the principal and its position-role ids from a JWT token string.
     *
     * <p>Same fail-closed contract as {@link #parsePersonFromToken(String)}: any problem yields
     * {@code null} rather than an exception or a partially trusted claim.
     *
     * @param jwtToken the JWT token string (without Bearer prefix)
     * @return the parsed principal, or null if the claim is absent, unsupported or not fully valid
     */
    public ParsedPrincipal parsePrincipalFromToken(String jwtToken) {
        if (jwtToken == null || jwtToken.isEmpty()) {
            log.debug("JWT token is null or empty");
            return null;
        }

        try {
            // Extract payload from JWT
            Map<String, Object> payload = extractPayload(jwtToken);
            if (payload == null) {
                return null;
            }

            // Get OrgSec claims
            Object orgSecClaim = payload.get(claimName);
            if (orgSecClaim == null) {
                log.warn("Missing OrgSec claims in token");
                return null;
            }

            // Parse claims DTO
            OrgSecClaimsDTO claimsDTO = objectMapper.convertValue(orgSecClaim, OrgSecClaimsDTO.class);

            // Validate version
            if (!isVersionSupported(claimsDTO.getVersion())) {
                log.warn("Unsupported OrgSec claims version: {}", claimsDTO.getVersion());
                return null;
            }

            // Map to PersonDef
            return mapToPrincipal(claimsDTO);

        } catch (RuntimeException e) {
            log.warn("Rejecting OrgSec claim: {}", e.getMessage());
            log.debug("Failed to parse OrgSec claims from token", e);
            return null;
        }
    }

    /**
     * Extract payload from JWT token.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPayload(String jwtToken) {
        try {
            Jwt jwt = jwtDecoder.decode(jwtToken);
            return jwt.getClaims();
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Failed to validate or extract JWT payload", e);
            return null;
        }
    }

    /**
     * Check if claims version is supported.
     */
    private boolean isVersionSupported(String version) {
        return claimVersion.equals(version);
    }

    /**
     * Map OrgSecClaimsDTO to a principal plus its position-role ids.
     */
    private ParsedPrincipal mapToPrincipal(OrgSecClaimsDTO claimsDTO) {
        PersonClaimDTO personClaim = claimsDTO.getPerson();
        if (personClaim == null || personClaim.getId() == null) {
            throw new IllegalArgumentException("Missing required person data in claims");
        }

        PersonDef personDef = new PersonDef(personClaim.getId(), personClaim.getName());
        personDef.setRelatedUserId(personClaim.getRelatedUserId());
        personDef.setRelatedUserLogin(personClaim.getRelatedUserLogin());
        personDef.setDefaultCompanyId(personClaim.getDefaultCompanyId());
        personDef.setDefaultOrgunitId(personClaim.getDefaultOrgunitId());

        Map<Long, List<Long>> positionRoleIds = new HashMap<>();

        // Map memberships to organizations
        List<MembershipClaimDTO> memberships = claimsDTO.getMemberships();
        if (memberships != null) {
            for (MembershipClaimDTO membership : memberships) {
                OrganizationDef orgDef = mapMembershipToOrganization(membership);

                if (orgDef.organizationId == null) {
                    throw new IllegalArgumentException("Membership without an organizationId");
                }
                // Two entries for one organization are not mergeable: they can disagree on
                // companyId and on which roles apply, and whichever wins is decided by claim
                // ordering. A token shaped that way is either a mapper bug or an attempt to smuggle
                // a privileged first entry past a plausible second one, so the whole claim goes.
                if (personDef.organizationsMap.containsKey(orgDef.organizationId)) {
                    throw new IllegalArgumentException(
                        "Duplicate organizationId " + orgDef.organizationId + " in claim memberships"
                    );
                }

                personDef.organizationsMap.put(orgDef.organizationId, orgDef);
                positionRoleIds.put(
                    orgDef.organizationId,
                    membership.getPositionRoleIds() != null ? List.copyOf(membership.getPositionRoleIds()) : List.of()
                );
            }
        }

        log.debug("Parsed PersonDef from JWT: personId={}, login={}",
                personDef.personId, personDef.relatedUserLogin);

        return new ParsedPrincipal(personDef, Map.copyOf(positionRoleIds));
    }

    /**
     * Map MembershipClaimDTO to OrganizationDef.
     *
     * <p>Hierarchy anchors ({@code parentPath}, {@code companyParentPath}) are deliberately left
     * unset. They cannot be derived from what the claim carries - a {@code pathId} names the
     * organization's own segment, not its ancestry - and a guessed anchor is an authorization
     * decision made on invented data. {@code JwtSecurityDataStorage} fills them in from the
     * delegate storage, which is the authority for the hierarchy.
     */
    private OrganizationDef mapMembershipToOrganization(MembershipClaimDTO membership) {
        OrganizationDef orgDef = new OrganizationDef();
        orgDef.organizationId = membership.getOrganizationId();
        orgDef.companyId = membership.getCompanyId();
        orgDef.pathId = resolvePathId(membership.getPathId());

        // Note: positionRoleIds are stored for later resolution with delegate storage
        // The actual RoleDef objects will be populated by JwtSecurityDataStorage
        // using the delegate storage

        return orgDef;
    }

    /**
     * Reads a membership {@code pathId} in either the canonical or the legacy shape.
     *
     * <p>The canonical claim carries the organization's own segment - {@code "ow"}, {@code "22"} -
     * which is what {@code OrganizationDef.pathId} means everywhere else in OrgSec. Tokens minted
     * by older mapper builds carry the full path instead ({@code "|root|ow|"}); those are accepted
     * and normalized to their last segment, so a realm can be migrated without invalidating tokens
     * already in flight.
     *
     * <p>Anything else - absent, blank, a bare {@code "|"}, an unterminated {@code "|A|B"}, an empty
     * inner segment, an illegal character, or an over-long segment - fails the whole claim rather
     * than degrading to a partial membership. A membership whose organization cannot be identified
     * cannot be checked against the delegate either, and silently dropping just that entry would
     * leave the principal looking legitimately smaller than it is.
     *
     * @param rawPathId the claim value, possibly null
     * @return the local segment
     * @throws com.nomendi6.orgsec.exceptions.OrgsecSecurityException if the value is neither shape
     */
    private String resolvePathId(String rawPathId) {
        if (rawPathId == null || rawPathId.trim().isEmpty()) {
            throw new OrgsecSecurityException("Membership pathId is missing");
        }

        String raw = rawPathId.trim();
        try {
            return PathSanitizer.validatePathId(raw);
        } catch (OrgsecSecurityException notALocalSegment) {
            // Legacy full path. lastSegment re-validates, so "|", "|A|B" and "|A||" still fail.
            return PathSanitizer.lastSegment(raw);
        }
    }

    /**
     * Get position role IDs from membership.
     *
     * @deprecated Since 1.0.5, and removed in 2.0.0. Re-reading the token per organization decouples
     *     the role ids from the memberships they belong to: this method answers for an organization
     *     the caller has not checked, from a second independent parse, and swallows every failure as
     *     an empty list - so a claim rejected by {@link #parsePrincipalFromToken(String)} could still
     *     yield roles here. Use {@link #parsePrincipalFromToken(String)}, which returns the
     *     memberships and their role ids as one value. Kept only so the 1.0.5 patch removes no public
     *     method.
     */
    @Deprecated(since = "1.0.5", forRemoval = true)
    public List<Long> getPositionRoleIds(String jwtToken, Long organizationId) {
        if (jwtToken == null || organizationId == null) {
            return List.of();
        }

        try {
            Map<String, Object> payload = extractPayload(jwtToken);
            if (payload == null) {
                return List.of();
            }

            Object orgSecClaim = payload.get(claimName);
            if (orgSecClaim == null) {
                return List.of();
            }

            OrgSecClaimsDTO claimsDTO = objectMapper.convertValue(orgSecClaim, OrgSecClaimsDTO.class);
            List<MembershipClaimDTO> memberships = claimsDTO.getMemberships();

            if (memberships != null) {
                for (MembershipClaimDTO membership : memberships) {
                    if (organizationId.equals(membership.getOrganizationId())) {
                        return membership.getPositionRoleIds() != null
                                ? membership.getPositionRoleIds()
                                : List.of();
                    }
                }
            }

            return List.of();

        } catch (Exception e) {
            log.error("Failed to get position role IDs from token", e);
            return List.of();
        }
    }
}
