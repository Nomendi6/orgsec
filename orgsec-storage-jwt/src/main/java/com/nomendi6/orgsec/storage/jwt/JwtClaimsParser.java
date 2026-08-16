package com.nomendi6.orgsec.storage.jwt;

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
import tools.jackson.databind.ObjectMapper;

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
     * <p>This method is fail-closed and total: an invalid signature, missing claim, unsupported
     * version or malformed claim returns {@code null}. No partially parsed principal is returned
     * and claim-shape errors never escape as application HTTP 500 responses.
     *
     * @param jwtToken the JWT token string (without Bearer prefix)
     * @return PersonDef or null if the token or complete claim is not trusted
     */
    public PersonDef parsePersonFromToken(String jwtToken) {
        ParsedPrincipal principal = parsePrincipalFromToken(jwtToken);
        return principal != null ? principal.person() : null;
    }

    /**
     * One validated principal together with the position-role ids from the same parse.
     */
    public record ParsedPrincipal(PersonDef person, Map<Long, List<Long>> positionRoleIdsByOrganization) {
    }

    /**
     * Parses a token once and keeps membership and role-id data indivisible.
     *
     * @return a complete validated claim, or {@code null} on any token/claim error
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
     * Map OrgSecClaimsDTO to PersonDef.
     */
    private ParsedPrincipal mapToPrincipal(OrgSecClaimsDTO claimsDTO) {
        PersonClaimDTO personClaim = claimsDTO.getPerson();
        if (personClaim == null || personClaim.getId() == null) {
            log.error("Missing required person data in claims");
            throw new IllegalArgumentException("Missing required person data in claims");
        }

        PersonDef personDef = new PersonDef(personClaim.getId(), personClaim.getName());
        personDef.setRelatedUserId(personClaim.getRelatedUserId());
        personDef.setRelatedUserLogin(personClaim.getRelatedUserLogin());
        personDef.setDefaultCompanyId(personClaim.getDefaultCompanyId());
        personDef.setDefaultOrgunitId(personClaim.getDefaultOrgunitId());

        Map<Long, List<Long>> positionRoleIdsByOrganization = new HashMap<>();

        // Map memberships to organizations
        List<MembershipClaimDTO> memberships = claimsDTO.getMemberships();
        if (memberships != null) {
            for (MembershipClaimDTO membership : memberships) {
                OrganizationDef orgDef = mapMembershipToOrganization(membership);
                if (orgDef.organizationId == null) {
                    throw new IllegalArgumentException("Membership without an organizationId");
                }
                if (personDef.organizationsMap.containsKey(orgDef.organizationId)) {
                    throw new IllegalArgumentException(
                        "Duplicate organizationId " + orgDef.organizationId + " in claim memberships"
                    );
                }
                personDef.organizationsMap.put(orgDef.organizationId, orgDef);
                positionRoleIdsByOrganization.put(
                    orgDef.organizationId,
                    membership.getPositionRoleIds() != null
                        ? List.copyOf(membership.getPositionRoleIds())
                        : List.of()
                );
            }
        }

        log.debug("Parsed PersonDef from JWT: personId={}, login={}",
                personDef.personId, personDef.relatedUserLogin);

        return new ParsedPrincipal(personDef, Map.copyOf(positionRoleIdsByOrganization));
    }

    /**
     * Map MembershipClaimDTO to OrganizationDef.
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
     * Accepts the canonical local segment and the legacy full-path shape. Both normalize to the
     * local segment used by {@link OrganizationDef#pathId}. Hierarchy anchors are intentionally
     * not inferred from the token; the delegate storage supplies them during enrichment.
     */
    private String resolvePathId(String rawPathId) {
        if (rawPathId == null || rawPathId.trim().isEmpty()) {
            throw new OrgsecSecurityException("Membership pathId is missing");
        }
        String raw = rawPathId.trim();
        try {
            return PathSanitizer.validatePathId(raw);
        } catch (OrgsecSecurityException notALocalSegment) {
            return PathSanitizer.lastSegment(raw);
        }
    }

    /**
     * Get position role IDs from membership.
     * Used by JwtSecurityDataStorage to resolve roles from delegate storage.
     */
    public List<Long> getPositionRoleIds(String jwtToken, Long organizationId) {
        if (jwtToken == null || organizationId == null) {
            return List.of();
        }
        ParsedPrincipal principal = parsePrincipalFromToken(jwtToken);
        return principal != null
            ? principal.positionRoleIdsByOrganization().getOrDefault(organizationId, List.of())
            : List.of();
    }
}
