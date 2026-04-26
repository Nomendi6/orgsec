package com.nomendi6.orgsec.storage.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that extracts JWT token from request and sets it in JwtTokenContextHolder.
 * This allows JwtSecurityDataStorage to access the token without passing it through
 * the entire call chain.
 */
public class JwtTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenFilter.class);

    private static final String DEFAULT_TOKEN_HEADER = "Authorization";
    private static final String DEFAULT_TOKEN_PREFIX = "Bearer ";

    private final JwtTokenContextHolder tokenContextHolder;
    private final String tokenHeader;
    private final String tokenPrefix;

    public JwtTokenFilter(JwtTokenContextHolder tokenContextHolder) {
        this(tokenContextHolder, DEFAULT_TOKEN_HEADER, DEFAULT_TOKEN_PREFIX);
    }

    public JwtTokenFilter(JwtTokenContextHolder tokenContextHolder, String tokenHeader, String tokenPrefix) {
        this.tokenContextHolder = tokenContextHolder;
        this.tokenHeader = tokenHeader;
        this.tokenPrefix = tokenPrefix;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (token != null) {
                tokenContextHolder.setToken(token);
                log.debug("JWT token set in context for request: {}", request.getRequestURI());
            }

            filterChain.doFilter(request, response);

        } finally {
            // Always clear the token context to prevent leaks
            tokenContextHolder.clear();
        }
    }

    /**
     * Extract JWT token from request header or OAuth2 security context.
     */
    private String extractToken(HttpServletRequest request) {
        // First, try to extract from Authorization header (for API clients)
        String header = request.getHeader(tokenHeader);
        if (header != null && header.startsWith(tokenPrefix)) {
            return header.substring(tokenPrefix.length());
        }

        // For OAuth2/OIDC with session-based auth, extract from security context
        return extractTokenFromSecurityContext();
    }

    /**
     * Extract JWT token from Spring Security OAuth2 context.
     * This is used when the application uses OAuth2 with session-based authentication
     * (e.g., Keycloak with Authorization Code flow).
     */
    private String extractTokenFromSecurityContext() {
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

        return null;
    }
}
