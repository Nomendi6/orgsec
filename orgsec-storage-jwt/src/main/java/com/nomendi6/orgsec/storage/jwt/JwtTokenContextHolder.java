package com.nomendi6.orgsec.storage.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * ThreadLocal holder for current JWT token.
 * This allows the JwtSecurityDataStorage to access the token
 * without passing it through the entire call chain.
 *
 * For OAuth2/OIDC authentication, it can also extract the token
 * directly from Spring Security context.
 */
@Component
public class JwtTokenContextHolder {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenContextHolder.class);

    private static final ThreadLocal<String> tokenHolder = new ThreadLocal<>();

    /**
     * Set the JWT token for the current thread.
     *
     * @param token the JWT token (without Bearer prefix)
     */
    public void setToken(String token) {
        tokenHolder.set(token);
    }

    /**
     * Get the JWT token for the current thread.
     * First checks ThreadLocal, then tries to extract from OAuth2 security context.
     *
     * @return the JWT token or null if not available
     */
    public String getToken() {
        // First check ThreadLocal (set by filter for Bearer token auth)
        String token = tokenHolder.get();
        if (token != null) {
            return token;
        }

        // Try to extract from OAuth2/OIDC security context
        return extractTokenFromSecurityContext();
    }

    /**
     * Clear the JWT token for the current thread.
     * Should be called at the end of request processing.
     */
    public void clear() {
        tokenHolder.remove();
    }

    /**
     * Check if a token is available for the current thread.
     *
     * @return true if token is available
     */
    public boolean hasToken() {
        return getToken() != null;
    }

    /**
     * Extract JWT token from Spring Security OAuth2 context.
     * This is used when the application uses OAuth2 with session-based authentication
     * (e.g., Keycloak with Authorization Code flow).
     */
    private String extractTokenFromSecurityContext() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication instanceof OAuth2AuthenticationToken) {
                OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
                Object principal = oauthToken.getPrincipal();

                if (principal instanceof OidcUser) {
                    OidcUser oidcUser = (OidcUser) principal;
                    // Get the ID token which contains our custom claims
                    if (oidcUser.getIdToken() != null) {
                        String tokenValue = oidcUser.getIdToken().getTokenValue();
                        log.debug("Extracted JWT token from OIDC ID token");
                        return tokenValue;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not extract token from security context: {}", e.getMessage());
        }

        return null;
    }
}
