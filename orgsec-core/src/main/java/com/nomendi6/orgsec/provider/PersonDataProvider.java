package com.nomendi6.orgsec.provider;

import java.util.Optional;
import com.nomendi6.orgsec.dto.PersonData;

/**
 * Provider interface for accessing person-related data.
 * This interface abstracts the data access layer, allowing the orgsec module
 * to be independent of specific repository implementations.
 *
 * Implementations of this interface should be provided by the main application
 * and configured during application startup.
 */
@FunctionalInterface
public interface PersonDataProvider {
    /**
     * Find a person by their ID.
     *
     * @param personId the ID of the person to find
     * @return an Optional containing the PersonData if found, empty otherwise
     */
    Optional<PersonData> findById(Long personId);

    /**
     * Find a person by their user ID.
     * Default implementation returns empty, override if needed.
     *
     * @param userId the user ID associated with the person
     * @return an Optional containing the PersonData if found, empty otherwise
     */
    default Optional<PersonData> findByUserId(String userId) {
        return Optional.empty();
    }

    /**
     * Check if a person exists with the given ID.
     * Default implementation uses findById.
     *
     * @param personId the ID to check
     * @return true if a person exists with this ID, false otherwise
     */
    default boolean existsById(Long personId) {
        return findById(personId).isPresent();
    }

    /**
     * Find a person by their related user login.
     * Default implementation returns empty, override if needed.
     *
     * @param login the login of the related user
     * @return an Optional containing the PersonData if found, empty otherwise
     */
    default Optional<PersonData> findByRelatedUserLogin(String login) {
        return Optional.empty();
    }
}
