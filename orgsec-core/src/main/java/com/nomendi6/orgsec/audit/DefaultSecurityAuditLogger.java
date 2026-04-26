package com.nomendi6.orgsec.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Default implementation of SecurityAuditLogger using SLF4J.
 * <p>
 * Logs audit events to a dedicated "SECURITY_AUDIT" logger which can be
 * configured separately in logback.xml or other logging configurations.
 * </p>
 * <p>
 * Uses MDC (Mapped Diagnostic Context) for structured logging support.
 * </p>
 */
public class DefaultSecurityAuditLogger implements SecurityAuditLogger {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("SECURITY_AUDIT");
    private static final Logger log = LoggerFactory.getLogger(DefaultSecurityAuditLogger.class);

    private final boolean enabled;
    private final boolean logCacheAccess;

    /**
     * Creates audit logger with default settings (enabled, cache logging disabled).
     */
    public DefaultSecurityAuditLogger() {
        this(true, false);
    }

    /**
     * Creates audit logger with specified settings.
     *
     * @param enabled        whether audit logging is enabled
     * @param logCacheAccess whether to log cache access events (can be verbose)
     */
    public DefaultSecurityAuditLogger(boolean enabled, boolean logCacheAccess) {
        this.enabled = enabled;
        this.logCacheAccess = logCacheAccess;
        log.info("SecurityAuditLogger initialized: enabled={}, logCacheAccess={}", enabled, logCacheAccess);
    }

    @Override
    public void logPrivilegeCheck(PrivilegeCheckEvent event) {
        if (!enabled) return;

        try {
            MDC.put("audit.type", "PRIVILEGE_CHECK");
            MDC.put("audit.personId", String.valueOf(event.personId()));
            MDC.put("audit.privilege", sanitizeLogValue(event.privilegeName()));
            MDC.put("audit.resource", sanitizeLogValue(event.resourceName()));
            MDC.put("audit.granted", String.valueOf(event.granted()));
            MDC.put("audit.durationMs", String.valueOf(event.durationMs()));

            if (event.granted()) {
                AUDIT_LOG.info("PRIVILEGE_CHECK: personId={}, privilege={}, resource={}, granted=true, duration={}ms",
                        event.personId(), sanitizeLogValue(event.privilegeName()), sanitizeLogValue(event.resourceName()), event.durationMs());
            } else {
                AUDIT_LOG.warn("PRIVILEGE_CHECK: personId={}, privilege={}, resource={}, granted=false, reason={}, duration={}ms",
                        event.personId(), sanitizeLogValue(event.privilegeName()), sanitizeLogValue(event.resourceName()),
                        sanitizeLogValue(event.reason()), event.durationMs());
            }
        } finally {
            clearMDC();
        }
    }

    @Override
    public void logCacheAccess(CacheAccessEvent event) {
        if (!enabled || !logCacheAccess) return;

        try {
            MDC.put("audit.type", "CACHE_ACCESS");
            MDC.put("audit.cacheType", sanitizeLogValue(event.cacheType()));
            MDC.put("audit.operation", sanitizeLogValue(event.operation()));
            MDC.put("audit.cacheLevel", sanitizeLogValue(event.cacheLevel()));
            MDC.put("audit.hit", String.valueOf(event.hit()));
            MDC.put("audit.durationMs", String.valueOf(event.durationMs()));

            AUDIT_LOG.debug("CACHE_ACCESS: type={}, operation={}, key={}, level={}, hit={}, duration={}ms",
                    sanitizeLogValue(event.cacheType()), sanitizeLogValue(event.operation()), maskKey(event.key()),
                    sanitizeLogValue(event.cacheLevel()), event.hit(), event.durationMs());
        } finally {
            clearMDC();
        }
    }

    @Override
    public void logConfigurationChange(ConfigurationChangeEvent event) {
        if (!enabled) return;

        try {
            MDC.put("audit.type", "CONFIG_CHANGE");
            MDC.put("audit.configKey", sanitizeLogValue(event.configKey()));
            MDC.put("audit.changedBy", sanitizeLogValue(event.changedBy()));

            AUDIT_LOG.info("CONFIG_CHANGE: key={}, oldValue={}, newValue={}, changedBy={}",
                    sanitizeLogValue(event.configKey()), maskSensitive(event.configKey(), event.oldValue()),
                    maskSensitive(event.configKey(), event.newValue()), sanitizeLogValue(event.changedBy()));
        } finally {
            clearMDC();
        }
    }

    @Override
    public void logSecurityEvent(SecurityEvent event) {
        if (!enabled) return;

        try {
            MDC.put("audit.type", "SECURITY_EVENT");
            MDC.put("audit.level", event.level().name());
            MDC.put("audit.category", sanitizeLogValue(event.category()));

            String message = String.format("SECURITY_EVENT: [%s] %s - %s. Details: %s",
                    event.level(), sanitizeLogValue(event.category()), sanitizeLogValue(event.message()),
                    sanitizeLogValue(event.details()));

            switch (event.level()) {
                case INFO -> AUDIT_LOG.info(message);
                case WARNING -> AUDIT_LOG.warn(message);
                case ERROR -> AUDIT_LOG.error(message);
                case CRITICAL -> {
                    AUDIT_LOG.error(message);
                    // For critical events, also log to application logger
                    log.error("CRITICAL SECURITY EVENT: {}", message);
                }
            }
        } finally {
            clearMDC();
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Mask cache keys to avoid logging sensitive data.
     */
    private String maskKey(String key) {
        if (key == null || key.length() <= 10) {
            return key;
        }
        return key.substring(0, 5) + "..." + key.substring(key.length() - 5);
    }

    /**
     * Mask potentially sensitive configuration values.
     */
    private String maskSensitive(String key, String value) {
        if (value == null) {
            return "null";
        }
        if (key == null) {
            return "***MASKED***";
        }
        String lowerKey = key != null ? key.toLowerCase() : "";
        if (lowerKey.contains("password") || lowerKey.contains("secret") ||
            lowerKey.contains("key") || lowerKey.contains("token")) {
            return "***MASKED***";
        }
        return sanitizeLogValue(value);
    }

    private String sanitizeLogValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replace('\r', '_').replace('\n', '_');
    }

    private void clearMDC() {
        MDC.remove("audit.type");
        MDC.remove("audit.personId");
        MDC.remove("audit.privilege");
        MDC.remove("audit.resource");
        MDC.remove("audit.granted");
        MDC.remove("audit.durationMs");
        MDC.remove("audit.cacheType");
        MDC.remove("audit.operation");
        MDC.remove("audit.cacheLevel");
        MDC.remove("audit.hit");
        MDC.remove("audit.configKey");
        MDC.remove("audit.changedBy");
        MDC.remove("audit.level");
        MDC.remove("audit.category");
    }
}
