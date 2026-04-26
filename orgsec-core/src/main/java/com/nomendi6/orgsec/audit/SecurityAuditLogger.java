package com.nomendi6.orgsec.audit;

/**
 * Interface for logging security-related audit events.
 * <p>
 * Implementations should log security events such as:
 * <ul>
 *   <li>Privilege checks (success/failure)</li>
 *   <li>Cache access patterns</li>
 *   <li>Security configuration changes</li>
 *   <li>Authentication/authorization events</li>
 * </ul>
 * </p>
 * <p>
 * This interface allows for different audit logging implementations:
 * file-based, database, external SIEM systems, etc.
 * </p>
 */
public interface SecurityAuditLogger {

    /**
     * Log a privilege check event.
     *
     * @param event the privilege check event details
     */
    void logPrivilegeCheck(PrivilegeCheckEvent event);

    /**
     * Log a cache access event.
     *
     * @param event the cache access event details
     */
    void logCacheAccess(CacheAccessEvent event);

    /**
     * Log a security configuration change.
     *
     * @param event the configuration change event details
     */
    void logConfigurationChange(ConfigurationChangeEvent event);

    /**
     * Log a security-related error or warning.
     *
     * @param event the security event details
     */
    void logSecurityEvent(SecurityEvent event);

    /**
     * Check if audit logging is enabled.
     *
     * @return true if audit logging is active
     */
    boolean isEnabled();

    /**
     * Event representing a privilege check operation.
     */
    record PrivilegeCheckEvent(
            Long personId,
            String privilegeName,
            String resourceName,
            boolean granted,
            String reason,
            long durationMs
    ) {}

    /**
     * Event representing a cache access operation.
     */
    record CacheAccessEvent(
            String cacheType,
            String operation,
            String key,
            boolean hit,
            String cacheLevel,
            long durationMs
    ) {}

    /**
     * Event representing a configuration change.
     */
    record ConfigurationChangeEvent(
            String configKey,
            String oldValue,
            String newValue,
            String changedBy
    ) {}

    /**
     * General security event for errors, warnings, and informational messages.
     */
    record SecurityEvent(
            SecurityEventLevel level,
            String category,
            String message,
            String details
    ) {}

    /**
     * Security event severity levels.
     */
    enum SecurityEventLevel {
        INFO,
        WARNING,
        ERROR,
        CRITICAL
    }
}
