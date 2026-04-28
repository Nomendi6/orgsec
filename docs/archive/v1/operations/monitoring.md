# Monitoring

This page covers what OrgSec gives you for runtime observability and what it does not. The summary is short: **OrgSec does not ship a Micrometer `MeterBinder`** and does not plan to in the 1.0.x or 2.0.x line. What it does ship is a Spring Boot Actuator health indicator (when the Redis backend is active), an audit logger with structured MDC keys, and programmatic access to L1 cache statistics. Each is described below.

If you need OrgSec data exposed through Micrometer / Prometheus / Grafana, you wrap the programmatic surfaces in your own `MeterBinder`. The wrapping points are stable; OrgSec just does not impose a metrics dependency.

## Health endpoints

When the Redis backend is active, OrgSec auto-configures a `RedisStorageHealthIndicator`. With `spring-boot-starter-actuator` on the classpath and `management.endpoints.web.exposure.include` covering `health`, the indicator is reachable at `/actuator/health`:

```bash
curl -s http://localhost:8080/actuator/health/orgsecRedisStorage
```

```json
{
  "status": "UP",
  "details": {
    "connection": "active",
    "response": "PONG"
  }
}
```

The indicator does a `PING` against Redis on every health probe. Wire this into your readiness probe (Kubernetes, ECS, ...) so a Redis outage takes the pod out of rotation rather than leaving it serving stale-or-empty authorization decisions.

The in-memory backend has no separate health indicator - the standard Spring Boot `livenessState` / `readinessState` indicators are sufficient. The JWT backend's health is the IdP's responsibility (and Spring Security's `JwtDecoder` health, if it has one in your version).

## Audit logging

OrgSec emits authorization decisions through a `SecurityAuditLogger` bean. The default implementation, `DefaultSecurityAuditLogger`, logs through SLF4J with structured MDC keys.

### Enabling

In 1.0.x the working switch is on the Redis storage module's audit block:

```yaml
orgsec:
  storage:
    redis:
      audit:
        enabled: true                  # turns the SecurityAuditLogger from no-op to real
        log-cache-access: false        # cache hit/miss is verbose; turn it on selectively for debugging
        log-privilege-checks: true
        log-config-changes: true
```

When `audit.enabled: true`, the Redis auto-configuration wires `DefaultSecurityAuditLogger`. When `audit.enabled: false` (the default) it wires `NoOpSecurityAuditLogger`, which silently discards every event.

Without the Redis module on the classpath at all, OrgSec does not auto-wire any `SecurityAuditLogger`. Supply your own bean (see "Custom audit logger" below) if you want audit events.

The top-level `orgsec.security.audit-logging` field exists on `OrgsecProperties` but is **reserved in 1.0.x** - it is not read by any OrgSec code and does not control audit behavior.

### Event types

The interface exposes four event types, each a Java `record`:

| Method                   | Event                       | Triggered when                                                            |
| ------------------------ | --------------------------- | ------------------------------------------------------------------------- |
| `logPrivilegeCheck`      | `PrivilegeCheckEvent`       | A `PrivilegeChecker` decision is made (granted / denied)                  |
| `logCacheAccess`         | `CacheAccessEvent`          | An L1 / L2 cache lookup finishes (off by default; enable for debugging)   |
| `logConfigurationChange` | `ConfigurationChangeEvent`  | A feature flag flips through `StorageFeatureFlags`                        |
| `logSecurityEvent`       | `SecurityEvent`             | An exceptional event (configuration error, suspicious input, ...)         |

Each event is emitted on the `SECURITY_AUDIT` SLF4J logger. Configure that logger separately in your `logback-spring.xml` to route OrgSec audit events to a dedicated appender:

```xml
<configuration>
  <appender name="AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/orgsec-audit.log</file>
    <encoder>
      <pattern>%d{ISO8601} [%thread] %-5level %logger{36} %X - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>logs/orgsec-audit-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
      <maxFileSize>50MB</maxFileSize>
      <maxHistory>30</maxHistory>
    </rollingPolicy>
  </appender>

  <logger name="SECURITY_AUDIT" level="INFO" additivity="false">
    <appender-ref ref="AUDIT_FILE"/>
  </logger>
</configuration>
```

`%X` includes the MDC map. The MDC keys OrgSec sets are:

| Key                  | Set on                         | Meaning                                          |
| -------------------- | ------------------------------ | ------------------------------------------------ |
| `audit.type`         | every event                    | `PRIVILEGE_CHECK`, `CACHE_ACCESS`, ...           |
| `audit.personId`     | `PrivilegeCheckEvent`          | The caller's `personId` (or `null` if anonymous) |
| `audit.privilege`    | `PrivilegeCheckEvent`          | Privilege identifier the check evaluated         |
| `audit.resource`     | `PrivilegeCheckEvent`          | Resource name on the entity                      |
| `audit.granted`      | `PrivilegeCheckEvent`          | `true` / `false`                                 |
| `audit.durationMs`   | most events                    | Wall-clock duration of the operation             |
| `audit.cacheType`    | `CacheAccessEvent`             | `Person` / `Organization` / `Role` / ...         |
| `audit.cacheLevel`   | `CacheAccessEvent`             | `L1` / `L2`                                      |
| `audit.hit`          | `CacheAccessEvent`             | `true` / `false`                                 |
| `audit.configKey`    | `ConfigurationChangeEvent`     | Property name that changed                       |
| `audit.changedBy`    | `ConfigurationChangeEvent`     | Source of the change (typically a username)      |
| `audit.level`        | `SecurityEvent`                | `INFO` / `WARNING` / `ERROR` / `CRITICAL`        |

Denials are logged at `WARN` with the `reason` string included. Granted checks are logged at `INFO`. Adjust the audit logger's level if your volume is high.

### Custom audit logger

Provide your own `SecurityAuditLogger` bean to send events somewhere other than SLF4J - a JSON line to a SIEM, an event to Kafka, a row in a dedicated database table:

```java
@Component
@Primary
public class SiemAuditLogger implements SecurityAuditLogger {

    @Override
    public void logPrivilegeCheck(PrivilegeCheckEvent event) {
        // serialize and forward to your SIEM
    }

    @Override public void logCacheAccess(CacheAccessEvent event) { /* ... */ }
    @Override public void logConfigurationChange(ConfigurationChangeEvent event) { /* ... */ }
    @Override public void logSecurityEvent(SecurityEvent event) { /* ... */ }
    @Override public boolean isEnabled() { return true; }
}
```

The `@Primary` annotation makes Spring prefer your bean over the default.

## Programmatic cache statistics

The Redis backend's L1 cache exposes counters per cache type. Each `L1Cache<K, V>` instance has a `getStats()` method that returns a small `CacheStats` record:

```java
L1Cache.CacheStats stats = personL1Cache.getStats();
stats.getSize();          // current entry count
stats.getHitCount();
stats.getMissCount();
stats.getEvictionCount();
stats.getHitRate();       // double, percentage
```

A scheduled reporter logs these on an interval:

```java
@Component
public class CacheStatsReporter {

    private final L1Cache<Long, PersonDef> personL1;
    private final Logger log = LoggerFactory.getLogger(CacheStatsReporter.class);

    public CacheStatsReporter(L1Cache<Long, PersonDef> personL1) {
        this.personL1 = personL1;
    }

    @Scheduled(fixedDelay = 60_000)
    public void report() {
        L1Cache.CacheStats s = personL1.getStats();
        log.info("L1 person cache: size={} hits={} misses={} hitRate={}%",
                 s.getSize(), s.getHitCount(), s.getMissCount(), s.getHitRate());
    }
}
```

If you need Micrometer metrics, wrap the same counters in `Gauge` / `FunctionCounter`:

```java
@PostConstruct
void registerMeters(MeterRegistry registry) {
    Gauge.builder("orgsec.cache.l1.size", personL1, c -> c.getStats().getSize())
        .tag("type", "person")
        .register(registry);
    FunctionCounter.builder("orgsec.cache.l1.hits", personL1, c -> c.getStats().getHitCount())
        .tag("type", "person")
        .register(registry);
    // ... etc. for misses, evictions, and the other entity types
}
```

OrgSec does **not** ship that registration code - intentionally. If you need Micrometer integration, this is the wrapping pattern.

## Circuit breaker observability

The Redis backend uses Resilience4j's circuit breaker. Resilience4j has its own Micrometer / Actuator integration (`resilience4j-micrometer`, `resilience4j-spring-boot3`) which is independent of OrgSec. Add those modules to your project if you want circuit-breaker state on `/actuator/health` or as Micrometer metrics. OrgSec configures the circuit breaker; it does not export its state.

## Pub/Sub invalidation logging

To see which entries are being invalidated across instances, set the OrgSec Pub/Sub package to `DEBUG`:

```yaml
logging:
  level:
    com.nomendi6.orgsec.storage.redis.invalidation: DEBUG
```

You will see one line per published invalidation and one per received invalidation. Useful when debugging "instance A says X is up to date, instance B disagrees" symptoms.

## What OrgSec deliberately does **not** ship

| Capability                    | OrgSec 1.0.x                          | Get it elsewhere                                            |
| ----------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| Micrometer `MeterBinder`      | Not provided                          | Wrap programmatic stats yourself (pattern above)            |
| Recommended Grafana dashboard | Not provided                          | Build one against your wrapped metrics                      |
| Tracing instrumentation       | Not provided                          | Spring Boot's built-in OTel auto-config covers most of what is interesting |
| Centralized event sink        | Not provided                          | Custom `SecurityAuditLogger` impl                           |

The reason is non-policy: a metrics binder forces a Micrometer dependency on every OrgSec consumer, and the right metric set varies between deployments. Keeping the surfaces programmatic lets each application choose.

## Where to go next

- [Operations / Production checklist](./production-checklist.md) - what monitoring to enable before going live.
- [Operations / Troubleshooting](./troubleshooting.md) - runbook.
- [Storage / Redis - Health and monitoring](../storage/03-redis.md#health-and-monitoring) - Redis-specific signals.
- [Configuration - Auditing](../guide/04-configuration.md#auditing) - the `audit-logging` flag in context.
