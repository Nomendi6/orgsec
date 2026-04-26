package com.nomendi6.orgsec.provider;

import java.util.Optional;

/**
 * Provider interface for accessing security context information.
 * This interface abstracts the security context layer, allowing the orgsec module
 * to be independent of specific security framework implementations (Spring Security, etc.).
 *
 * Implementations of this interface should be provided by the main application
 * and configured during application startup.
 */
@FunctionalInterface
public interface SecurityContextProvider {
    /**
     * Get the current authenticated user's login/username from the security context.
     *
     * @return an Optional containing the current user's login if authenticated, empty otherwise
     */
    Optional<String> getCurrentUserLogin();

    /**
     * Check if there is an authenticated user in the current security context.
     * Default implementation checks if getCurrentUserLogin returns a value.
     *
     * @return true if a user is authenticated, false otherwise
     */
    default boolean isAuthenticated() {
        return getCurrentUserLogin().isPresent();
    }

    /**
     * Check if the current user has a specific role or authority.
     * Default implementation returns false - override to provide actual role checking.
     *
     * @param role the role or authority to check
     * @return true if the current user has the specified role, false otherwise
     */
    default boolean hasRole(String role) {
        return false;
    }

    /**
     * Get the authentication principal object.
     * Default implementation returns empty - override to provide actual principal.
     *
     * @return an Optional containing the principal object if available, empty otherwise
     */
    default Optional<Object> getPrincipal() {
        return Optional.empty();
    }
}
