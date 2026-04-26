package com.nomendi6.orgsec.storage.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nomendi6.orgsec.model.OrganizationDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.storage.jwt.dto.MembershipClaimDTO;
import com.nomendi6.orgsec.storage.jwt.dto.OrgSecClaimsDTO;
import com.nomendi6.orgsec.storage.jwt.dto.PersonClaimDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Parser for OrgSec claims from JWT token.
 */
@Component
public class JwtClaimsParser {

    private static final Logger log = LoggerFactory.getLogger(JwtClaimsParser.class);

    private static final String DEFAULT_CLAIM_NAME = "orgsec";
    private static final String SUPPORTED_VERSION = "1.0";

    private final ObjectMapper objectMapper;
    private final String claimName;

    public JwtClaimsParser(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_CLAIM_NAME);
    }

    public JwtClaimsParser(ObjectMapper objectMapper, String claimName) {
        this.objectMapper = objectMapper;
        this.claimName = claimName;
    }

    /**
     * Parse PersonDef from JWT token string.
     *
     * @param jwtToken the JWT token string (without Bearer prefix)
     * @return PersonDef or null if claims not found
     */
    public PersonDef parsePersonFromToken(String jwtToken) {
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
                log.error("Unsupported OrgSec claims version: {}", claimsDTO.getVersion());
                throw new IllegalArgumentException("Unsupported OrgSec claims version: " + claimsDTO.getVersion());
            }

            // Map to PersonDef
            return mapToPersonDef(claimsDTO);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse OrgSec claims from token", e);
            return null;
        }
    }

    /**
     * Extract payload from JWT token.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPayload(String jwtToken) {
        try {
            String[] parts = jwtToken.split("\\.");
            if (parts.length != 3) {
                log.error("Invalid JWT token format - expected 3 parts, got {}", parts.length);
                return null;
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
            return objectMapper.readValue(payloadJson, Map.class);

        } catch (Exception e) {
            log.error("Failed to extract JWT payload", e);
            return null;
        }
    }

    /**
     * Check if claims version is supported.
     */
    private boolean isVersionSupported(String version) {
        return SUPPORTED_VERSION.equals(version);
    }

    /**
     * Map OrgSecClaimsDTO to PersonDef.
     */
    private PersonDef mapToPersonDef(OrgSecClaimsDTO claimsDTO) {
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

        // Map memberships to organizations
        List<MembershipClaimDTO> memberships = claimsDTO.getMemberships();
        if (memberships != null) {
            for (MembershipClaimDTO membership : memberships) {
                OrganizationDef orgDef = mapMembershipToOrganization(membership);
                personDef.organizationsMap.put(orgDef.organizationId, orgDef);
            }
        }

        log.debug("Parsed PersonDef from JWT: personId={}, login={}",
                personDef.personId, personDef.relatedUserLogin);

        return personDef;
    }

    /**
     * Map MembershipClaimDTO to OrganizationDef.
     */
    private OrganizationDef mapMembershipToOrganization(MembershipClaimDTO membership) {
        OrganizationDef orgDef = new OrganizationDef();
        orgDef.organizationId = membership.getOrganizationId();
        orgDef.companyId = membership.getCompanyId();
        orgDef.pathId = membership.getPathId();

        // Calculate parentPath from pathId
        if (membership.getPathId() != null) {
            orgDef.parentPath = calculateParentPath(membership.getPathId());
        }

        // Note: positionRoleIds are stored for later resolution with delegate storage
        // The actual RoleDef objects will be populated by JwtSecurityDataStorage
        // using the delegate storage

        return orgDef;
    }

    /**
     * Calculate parent path from full path.
     * Example: /1/10/15/ -> /1/10/
     */
    private String calculateParentPath(String pathId) {
        if (pathId == null || pathId.length() <= 1) {
            return "/";
        }

        // Remove trailing slash if present
        String path = pathId.endsWith("/") ? pathId.substring(0, pathId.length() - 1) : pathId;

        // Find last slash
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }

        return path.substring(0, lastSlash + 1);
    }

    /**
     * Get position role IDs from membership.
     * Used by JwtSecurityDataStorage to resolve roles from delegate storage.
     */
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
