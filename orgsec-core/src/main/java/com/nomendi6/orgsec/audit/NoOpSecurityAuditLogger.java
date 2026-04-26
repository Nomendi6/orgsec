package com.nomendi6.orgsec.audit;

/**
 * No-operation implementation of SecurityAuditLogger.
 * <p>
 * Use this implementation when audit logging is not required
 * or should be disabled for performance reasons.
 * </p>
 */
public class NoOpSecurityAuditLogger implements SecurityAuditLogger {

    @Override
    public void logPrivilegeCheck(PrivilegeCheckEvent event) {
        // No-op
    }

    @Override
    public void logCacheAccess(CacheAccessEvent event) {
        // No-op
    }

    @Override
    public void logConfigurationChange(ConfigurationChangeEvent event) {
        // No-op
    }

    @Override
    public void logSecurityEvent(SecurityEvent event) {
        // No-op
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
