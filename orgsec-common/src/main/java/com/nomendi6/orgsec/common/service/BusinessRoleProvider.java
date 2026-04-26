package com.nomendi6.orgsec.common.service;

import java.util.Map;
import com.nomendi6.orgsec.model.BusinessRoleDefinition;

/**
 * Interface for providing business role definitions to the security system.
 *
 * This allows applications to define their own business roles or override default ones
 * without modifying the core orgsec configuration. Providers are processed in priority
 * order, with higher priority providers overriding lower priority ones.
 *
 * @since 1.0.0
 */
public interface BusinessRoleProvider {
    /**
     * Provides business role definitions for the application.
     *
     * @return Map of role name to BusinessRoleDefinition, never null
     */
    Map<String, BusinessRoleDefinition> getBusinessRoleDefinitions();

    /**
     * Priority of this provider. Providers with higher priority values
     * will override roles from lower priority providers.
     *
     * Default providers should use priority 0.
     * Application-specific providers should use priority 100 or higher.
     * Test providers should use the highest priority values.
     *
     * @return provider priority, default is 0
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Optional name for this provider, used for logging and debugging.
     *
     * @return provider name, defaults to the class simple name
     */
    default String getProviderName() {
        return this.getClass().getSimpleName();
    }
}
