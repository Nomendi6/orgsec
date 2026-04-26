package com.nomendi6.orgsec.storage.redis.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Redis storage.
 * <p>
 * These properties are bound from the {@code orgsec.storage.redis} prefix in application configuration.
 * </p>
 */
@ConfigurationProperties(prefix = "orgsec.storage.redis")
@Validated
public class RedisStorageProperties {

    /**
     * Redis server hostname.
     */
    @NotNull(message = "Redis host cannot be null")
    private String host = "localhost";

    /**
     * Redis server port.
     */
    @Min(value = 1, message = "Port must be greater than 0")
    @Max(value = 65535, message = "Port must be less than 65536")
    private int port = 6379;

    /**
     * Redis server password (optional).
     */
    private String password;

    /**
     * Connection timeout in milliseconds.
     */
    @Min(value = 100, message = "Timeout must be at least 100ms")
    private int timeout = 2000;

    /**
     * TTL configuration for cached entities.
     */
    private TtlConfig ttl = new TtlConfig();

    /**
     * Cache configuration (L1 in-memory cache).
     */
    private CacheConfig cache = new CacheConfig();

    /**
     * Invalidation configuration (Pub/Sub).
     */
    private InvalidationConfig invalidation = new InvalidationConfig();

    /**
     * Preload configuration (eager/lazy loading).
     */
    private PreloadConfig preload = new PreloadConfig();

    /**
     * Circuit breaker configuration.
     */
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();

    /**
     * Monitoring configuration.
     */
    private MonitoringConfig monitoring = new MonitoringConfig();

    /**
     * Connection pool configuration.
     */
    private PoolConfig pool = new PoolConfig();

    /**
     * Serialization configuration (Jackson ObjectMapper).
     */
    private SerializationConfig serialization = new SerializationConfig();

    /**
     * Audit logging configuration.
     */
    private AuditConfig audit = new AuditConfig();

    // Getters and Setters

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public TtlConfig getTtl() {
        return ttl;
    }

    public void setTtl(TtlConfig ttl) {
        this.ttl = ttl;
    }

    public CacheConfig getCache() {
        return cache;
    }

    public void setCache(CacheConfig cache) {
        this.cache = cache;
    }

    public InvalidationConfig getInvalidation() {
        return invalidation;
    }

    public void setInvalidation(InvalidationConfig invalidation) {
        this.invalidation = invalidation;
    }

    public PreloadConfig getPreload() {
        return preload;
    }

    public void setPreload(PreloadConfig preload) {
        this.preload = preload;
    }

    public CircuitBreakerConfig getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerConfig circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public MonitoringConfig getMonitoring() {
        return monitoring;
    }

    public void setMonitoring(MonitoringConfig monitoring) {
        this.monitoring = monitoring;
    }

    public PoolConfig getPool() {
        return pool;
    }

    public void setPool(PoolConfig pool) {
        this.pool = pool;
    }

    public SerializationConfig getSerialization() {
        return serialization;
    }

    public void setSerialization(SerializationConfig serialization) {
        this.serialization = serialization;
    }

    public AuditConfig getAudit() {
        return audit;
    }

    public void setAudit(AuditConfig audit) {
        this.audit = audit;
    }

    /**
     * TTL (Time-To-Live) configuration for cached entities.
     */
    public static class TtlConfig {
        /**
         * TTL for person entities in seconds (default: 1 hour).
         */
        @Min(value = 1, message = "TTL must be at least 1 second")
        private long person = 3600;

        /**
         * TTL for organization entities in seconds (default: 2 hours).
         */
        @Min(value = 1, message = "TTL must be at least 1 second")
        private long organization = 7200;

        /**
         * TTL for role entities in seconds (default: 2 hours).
         */
        @Min(value = 1, message = "TTL must be at least 1 second")
        private long role = 7200;

        /**
         * TTL for privilege entities in seconds (default: 2 hours).
         */
        @Min(value = 1, message = "TTL must be at least 1 second")
        private long privilege = 7200;

        /**
         * Reduced TTL after security-related changes in seconds (default: 5 minutes).
         */
        @Min(value = 1, message = "TTL must be at least 1 second")
        private long onSecurityChange = 300;

        public long getPerson() {
            return person;
        }

        public void setPerson(long person) {
            this.person = person;
        }

        public long getOrganization() {
            return organization;
        }

        public void setOrganization(long organization) {
            this.organization = organization;
        }

        public long getRole() {
            return role;
        }

        public void setRole(long role) {
            this.role = role;
        }

        public long getPrivilege() {
            return privilege;
        }

        public void setPrivilege(long privilege) {
            this.privilege = privilege;
        }

        public long getOnSecurityChange() {
            return onSecurityChange;
        }

        public void setOnSecurityChange(long onSecurityChange) {
            this.onSecurityChange = onSecurityChange;
        }
    }

    /**
     * L1 (in-memory) cache configuration.
     */
    public static class CacheConfig {
        /**
         * Enable L1 in-memory cache.
         */
        private boolean l1Enabled = true;

        /**
         * Maximum number of entries in L1 cache (LRU eviction).
         */
        @Min(value = 1, message = "L1 max size must be at least 1")
        @Max(value = 1000000, message = "L1 max size must be less than 1000000")
        private int l1MaxSize = 1000;

        /**
         * Obfuscate cache keys (SHA-256 hash for security).
         */
        private boolean obfuscateKeys = false;

        public boolean isL1Enabled() {
            return l1Enabled;
        }

        public void setL1Enabled(boolean l1Enabled) {
            this.l1Enabled = l1Enabled;
        }

        public int getL1MaxSize() {
            return l1MaxSize;
        }

        public void setL1MaxSize(int l1MaxSize) {
            this.l1MaxSize = l1MaxSize;
        }

        public boolean isObfuscateKeys() {
            return obfuscateKeys;
        }

        public void setObfuscateKeys(boolean obfuscateKeys) {
            this.obfuscateKeys = obfuscateKeys;
        }
    }

    /**
     * Invalidation configuration (Redis Pub/Sub).
     */
    public static class InvalidationConfig {
        /**
         * Enable cache invalidation via Redis Pub/Sub.
         */
        private boolean enabled = true;

        /**
         * Use async publishing for invalidation events.
         */
        private boolean async = true;

        /**
         * Redis Pub/Sub channel name for invalidation events.
         */
        @NotNull(message = "Invalidation channel cannot be null")
        private String channel = "orgsec:invalidation";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAsync() {
            return async;
        }

        public void setAsync(boolean async) {
            this.async = async;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }
    }

    /**
     * Preload configuration (cache warmup on startup).
     */
    public static class PreloadConfig {
        /**
         * Enable cache preload.
         */
        private boolean enabled = true;

        /**
         * Trigger preload on application startup.
         */
        private boolean onStartup = true;

        /**
         * Preload strategy: "all", "persons", "organizations", "roles".
         */
        @NotNull(message = "Preload strategy cannot be null")
        private String strategy = "all";

        /**
         * Warming mode: "eager" (all at startup), "lazy" (on first access), "progressive" (background loading).
         */
        @NotNull(message = "Warming mode cannot be null")
        private String mode = "eager";

        /**
         * Batch size for progressive loading (number of items per batch).
         */
        @Min(value = 1, message = "Batch size must be at least 1")
        private int batchSize = 100;

        /**
         * Delay between batches in progressive loading (milliseconds).
         */
        @Min(value = 0, message = "Batch delay must be at least 0ms")
        private long batchDelayMs = 50;

        /**
         * Async warmup - don't block application startup.
         */
        private boolean async = false;

        /**
         * Number of threads for parallel warmup.
         */
        @Min(value = 1, message = "Parallelism must be at least 1")
        private int parallelism = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isOnStartup() {
            return onStartup;
        }

        public void setOnStartup(boolean onStartup) {
            this.onStartup = onStartup;
        }

        public String getStrategy() {
            return strategy;
        }

        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getBatchDelayMs() {
            return batchDelayMs;
        }

        public void setBatchDelayMs(long batchDelayMs) {
            this.batchDelayMs = batchDelayMs;
        }

        public boolean isAsync() {
            return async;
        }

        public void setAsync(boolean async) {
            this.async = async;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }
    }

    /**
     * Circuit breaker configuration.
     */
    public static class CircuitBreakerConfig {
        /**
         * Enable circuit breaker for Redis operations.
         */
        private boolean enabled = true;

        /**
         * Failure rate threshold percentage to open circuit (default: 50%).
         */
        @Min(value = 1, message = "Failure threshold must be at least 1%")
        @Max(value = 100, message = "Failure threshold must be at most 100%")
        private int failureThreshold = 50;

        /**
         * Wait duration before attempting to close circuit in milliseconds (default: 30 seconds).
         */
        @Min(value = 1000, message = "Wait duration must be at least 1 second")
        private long waitDuration = 30000;

        /**
         * Sliding window size for failure rate calculation (default: 10).
         */
        @Min(value = 1, message = "Sliding window size must be at least 1")
        private int slidingWindowSize = 10;

        /**
         * Minimum number of calls before calculating failure rate (default: 5).
         */
        @Min(value = 1, message = "Minimum calls must be at least 1")
        private int minimumCalls = 5;

        /**
         * Number of permitted calls in half-open state (default: 3).
         */
        @Min(value = 1, message = "Permitted calls must be at least 1")
        private int permittedCallsInHalfOpen = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getWaitDuration() {
            return waitDuration;
        }

        public void setWaitDuration(long waitDuration) {
            this.waitDuration = waitDuration;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumCalls() {
            return minimumCalls;
        }

        public void setMinimumCalls(int minimumCalls) {
            this.minimumCalls = minimumCalls;
        }

        public int getPermittedCallsInHalfOpen() {
            return permittedCallsInHalfOpen;
        }

        public void setPermittedCallsInHalfOpen(int permittedCallsInHalfOpen) {
            this.permittedCallsInHalfOpen = permittedCallsInHalfOpen;
        }
    }

    /**
     * Monitoring configuration.
     */
    public static class MonitoringConfig {
        /**
         * Enable Micrometer metrics for cache operations.
         */
        private boolean metricsEnabled = true;

        /**
         * Enable Spring Boot Actuator health check endpoint.
         */
        private boolean healthCheckEnabled = true;

        public boolean isMetricsEnabled() {
            return metricsEnabled;
        }

        public void setMetricsEnabled(boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
        }

        public boolean isHealthCheckEnabled() {
            return healthCheckEnabled;
        }

        public void setHealthCheckEnabled(boolean healthCheckEnabled) {
            this.healthCheckEnabled = healthCheckEnabled;
        }
    }

    /**
     * Connection pool configuration for Lettuce.
     */
    public static class PoolConfig {
        /**
         * Enable connection pooling.
         */
        private boolean enabled = true;

        /**
         * Minimum number of idle connections in pool.
         */
        @Min(value = 0, message = "Min idle must be at least 0")
        private int minIdle = 5;

        /**
         * Maximum number of idle connections in pool.
         */
        @Min(value = 1, message = "Max idle must be at least 1")
        private int maxIdle = 10;

        /**
         * Maximum number of active connections in pool.
         */
        @Min(value = 1, message = "Max active must be at least 1")
        private int maxActive = 20;

        /**
         * Maximum wait time for connection in milliseconds.
         * -1 means block indefinitely.
         */
        private long maxWait = 2000;

        /**
         * Run evictor thread to check for idle connections.
         */
        private boolean testWhileIdle = true;

        /**
         * Time between eviction runs in milliseconds (default: 30 seconds).
         */
        @Min(value = 1000, message = "Time between eviction runs must be at least 1 second")
        private long timeBetweenEvictionRuns = 30000;

        /**
         * Minimum time a connection may sit idle before eviction (default: 60 seconds).
         */
        @Min(value = 1000, message = "Min evictable idle time must be at least 1 second")
        private long minEvictableIdleTime = 60000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }

        public int getMaxIdle() {
            return maxIdle;
        }

        public void setMaxIdle(int maxIdle) {
            this.maxIdle = maxIdle;
        }

        public int getMaxActive() {
            return maxActive;
        }

        public void setMaxActive(int maxActive) {
            this.maxActive = maxActive;
        }

        public long getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(long maxWait) {
            this.maxWait = maxWait;
        }

        public boolean isTestWhileIdle() {
            return testWhileIdle;
        }

        public void setTestWhileIdle(boolean testWhileIdle) {
            this.testWhileIdle = testWhileIdle;
        }

        public long getTimeBetweenEvictionRuns() {
            return timeBetweenEvictionRuns;
        }

        public void setTimeBetweenEvictionRuns(long timeBetweenEvictionRuns) {
            this.timeBetweenEvictionRuns = timeBetweenEvictionRuns;
        }

        public long getMinEvictableIdleTime() {
            return minEvictableIdleTime;
        }

        public void setMinEvictableIdleTime(long minEvictableIdleTime) {
            this.minEvictableIdleTime = minEvictableIdleTime;
        }
    }

    /**
     * Serialization configuration for Jackson ObjectMapper.
     */
    public static class SerializationConfig {
        /**
         * Fail on unknown properties during deserialization.
         * Setting to true provides better security but less forward compatibility.
         * Default: false (for backward compatibility)
         */
        private boolean failOnUnknownProperties = false;

        /**
         * Enable strict mode for production environments.
         * When enabled, applies additional security restrictions.
         */
        private boolean strictMode = false;

        public boolean isFailOnUnknownProperties() {
            return failOnUnknownProperties;
        }

        public void setFailOnUnknownProperties(boolean failOnUnknownProperties) {
            this.failOnUnknownProperties = failOnUnknownProperties;
        }

        public boolean isStrictMode() {
            return strictMode;
        }

        public void setStrictMode(boolean strictMode) {
            this.strictMode = strictMode;
        }
    }

    /**
     * Audit logging configuration.
     */
    public static class AuditConfig {
        /**
         * Enable audit logging.
         */
        private boolean enabled = false;

        /**
         * Log cache access events (can be verbose).
         */
        private boolean logCacheAccess = false;

        /**
         * Log privilege check events.
         */
        private boolean logPrivilegeChecks = true;

        /**
         * Log configuration changes.
         */
        private boolean logConfigChanges = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLogCacheAccess() {
            return logCacheAccess;
        }

        public void setLogCacheAccess(boolean logCacheAccess) {
            this.logCacheAccess = logCacheAccess;
        }

        public boolean isLogPrivilegeChecks() {
            return logPrivilegeChecks;
        }

        public void setLogPrivilegeChecks(boolean logPrivilegeChecks) {
            this.logPrivilegeChecks = logPrivilegeChecks;
        }

        public boolean isLogConfigChanges() {
            return logConfigChanges;
        }

        public void setLogConfigChanges(boolean logConfigChanges) {
            this.logConfigChanges = logConfigChanges;
        }
    }
}
