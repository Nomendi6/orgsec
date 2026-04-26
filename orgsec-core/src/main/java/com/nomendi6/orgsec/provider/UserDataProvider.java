package com.nomendi6.orgsec.provider;

import java.util.Optional;
import com.nomendi6.orgsec.dto.UserData;

/**
 * Provider interface for accessing user-related data.
 * This interface abstracts the user data access layer, allowing the orgsec module
 * to be independent of specific repository or security implementations.
 *
 * Implementations of this interface should be provided by the main application
 * and configured during application startup.
 */
@FunctionalInterface
public interface UserDataProvider {
    /**
     * Find a user by their login.
     *
     * @param login the login identifier of the user
     * @return an Optional containing the UserData if found, empty otherwise
     */
    Optional<UserData> findByLogin(String login);

    /**
     * Get the current authenticated user's login.
     * Default implementation returns empty, override to integrate with security context.
     *
     * @return an Optional containing the current user's login if authenticated, empty otherwise
     */
    default Optional<String> getCurrentUserLogin() {
        return Optional.empty();
    }

    /**
     * Check if a user exists with the given login.
     * Default implementation uses findByLogin.
     *
     * @param login the login to check
     * @return true if a user exists with this login, false otherwise
     */
    default boolean existsByLogin(String login) {
        return findByLogin(login).isPresent();
    }

    /**
     * Get the current authenticated user's data.
     * Default implementation uses getCurrentUserLogin and findByLogin.
     *
     * @return an Optional containing the current user's data if authenticated, empty otherwise
     */
    default Optional<UserData> getCurrentUser() {
        return getCurrentUserLogin().flatMap(this::findByLogin);
    }
}
