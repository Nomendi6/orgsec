# Properties Reference

This is the complete catalogue of OrgSec configuration properties for the **1.1.x** line. The list is generated from the five `@ConfigurationProperties` classes in the codebase and is verified by `PropertiesDocumentationCoverageTest` - if you add or remove a property in the source, the test will fail until this file is updated.

If you are looking for the *narrative* explanation of a property, follow the link in the **See** column. If you are looking up "what does this property do?", the table on this page is authoritative.

| Source class                  | Module                       | Prefix                  |
| ----------------------------- | ---------------------------- | ----------------------- |
| `OrgsecProperties`            | `orgsec-spring-boot-starter` | `orgsec`                |
| `BusinessRoleConfiguration`   | `orgsec-common`              | `orgsec.business-roles` |
| `StorageFeatureFlags`         | `orgsec-storage-inmemory`    | `orgsec.storage`        |
| `RedisStorageProperties`      | `orgsec-storage-redis`       | `orgsec.storage.redis`  |
| `JwtStorageProperties`        | `orgsec-storage-jwt`         | `orgsec.storage.jwt`    |

The conventions used in the tables below:

- **Type** is the Java type the property binds to.
- **Default** is the value the field is initialized with in the source class. Empty cell means `null`.
- A property whose name starts with the prefix listed in the section header is in the right place; the **Property** column drops the prefix to keep the table compact.

---

## `OrgsecProperties` - `orgsec.*`

### Master switch - `orgsec.*`

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                             | `boolean` | `true`                 | Master OrgSec switch. Setting `false` disables auto-configuration.                       | [Spring Boot starter](../spring/01-spring-boot-starter.md) |

### Security toggles - `orgsec.security.*`

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `privilege-checking`                  | `boolean` | `true`                 | Reserved; not enforced in 1.0.x. The field binds from YAML but no OrgSec code reads it.  | [Privileges](../usage/05-privileges.md) |
| `role-hierarchy`                      | `boolean` | `true`                 | Reserved; not enforced in 1.0.x.                                                         | [Privileges](../usage/05-privileges.md) |
| `audit-logging`                       | `boolean` | `false`                | Reserved; not enforced in 1.0.x. To enable audit logging, use `orgsec.storage.redis.audit.enabled` or supply your own `SecurityAuditLogger` bean. | [Monitoring](../operations/monitoring.md) |

### Feature flags - `orgsec.features.*`

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `business-roles`                      | `boolean` | `true`                 | Reserved; not enforced in 1.0.x. Business-role aggregation is always active when `BusinessRoleConfiguration` is on the classpath. | [Privileges](../usage/05-privileges.md) |
| `position-roles`                      | `boolean` | `true`                 | Reserved; not enforced in 1.0.x.                                                         | [Core Concepts](../reference/concepts.md#business-role-vs-position-role) |
| `delegations`                         | `boolean` | `false`                | Reserved; delegation feature not implemented in 1.0.x.                                   | - |

### Storage type and in-memory tuning - `orgsec.storage.*` (legacy properties on `OrgsecProperties`)

These properties predate `StorageFeatureFlags`. Neither of them selects a backend; see [Feature flags](#feature-flags---orgsecstoragefeatures) for the switches that actually do. The in-memory sub-section holds reserved tuning knobs.

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `type`                                | `String`  | `"inmemory"`           | Legacy storage type selector. **Inert** - no code reads it.                              | [Choose storage](../storage/01-choose-storage.md) |
| `inmemory.cache-ttl`                  | `int`     | `3600`                 | Reserved; not enforced by the in-memory backend in 1.0.x.                                | [Storage / In-memory](../storage/02-in-memory.md#configuration) |
| `inmemory.max-entries`                | `int`     | `10000`                | Reserved; not enforced by the in-memory backend in 1.0.x.                                | [Storage / In-memory](../storage/02-in-memory.md#configuration) |

### Person API - `orgsec.api.person.*`

The Person API is consumed by Keycloak's custom protocol mapper to assemble the `orgsec` claim. It is **off** by default; enable only with a properly configured `required-role`.

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                             | `boolean` | `false`                | Expose `GET /api/orgsec/person/by-user/{userId}`.                                               | [Keycloak Person API](../spring/03-keycloak-person-api.md) |
| `required-role`                       | `String`  | `"ORGSEC_API_CLIENT"`  | Role required to call the endpoint. Enforced via Spring Security `hasRole(requiredRole)`, which **prepends `ROLE_`** - the authenticated principal must carry authority `ROLE_<requiredRole>` (default: `ROLE_ORGSEC_API_CLIENT`). | [Spring Security](../spring/02-spring-security.md) |

---

## `BusinessRoleConfiguration` - `orgsec.business-roles.*`

Each entry under `business-roles` is a *named* business role with a list of supported field types. The role name (`<role>`) is free-form - OrgSec does not pre-declare any business roles in 1.0.x.

| Property                              | Type                    | Default | Description                                                                              | See                                                            |
| ------------------------------------- | ----------------------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `<role>.supported-fields`             | `Set<SecurityFieldType>`| `[]`    | Subset of `COMPANY`, `COMPANY_PATH`, `ORG`, `ORG_PATH`, `PERSON` the entity exposes for this role. | [Business roles](../usage/04-business-roles.md) |
| `<role>.rsql-fields`                  | `Map<String,String>`    | `{}`    | Optional RSQL selector override per security field (`COMPANY`, `COMPANY_PATH`, `ORG`, `ORG_PATH`, `PERSON`). Values must be simple dotted property paths such as `ownerCompanyId` or `ownerCompany.id`. Each configured key must also appear in `<role>.supported-fields` (this includes path types `COMPANY_PATH` and `ORG_PATH`). | [Filter a list endpoint](../usage/07-filter-list-endpoint.md#custom-field-selectors) |

---

## `StorageFeatureFlags` - `orgsec.storage.*`

`StorageFeatureFlags` (in `orgsec-storage-inmemory`) is the binding target for `orgsec.storage.*`.

> **Only two of these properties do anything.** Backend selection happens through
> `@ConditionalOnProperty` evaluated against the `Environment` when the context starts:
> `orgsec.storage.redis.enabled` activates Redis and `orgsec.storage.features.jwt-enabled` activates
> JWT. The remaining properties on this page - `primary`, `fallback`, `hybrid-mode-enabled`,
> `memory-enabled` and everything under `data-sources` - are bound and then read by nothing.
> `JwtSecurityDataStorage` has a single delegate for every data type; there is no per-data-type
> router. They are listed here because they still bind, not because they take effect.
>
> The setter-style methods on `StorageFeatureFlags` (`enableJwtStorage()`, `setPersonDataSource(...)`)
> are inert for the same reason: wiring is decided once, at startup, from the `Environment`.

### Activation - `orgsec.storage.*`

Checked by `OrgsecStorageActivationValidator`, an `EnvironmentPostProcessor`, before any bean is
defined. Every failure below refuses startup and names a stable diagnostic code; none of them is
merely warned about, because a warning would leave a different storage serving authorization than
the operator selected.

| Code | Condition |
| --- | --- |
| `ORGSEC_STORAGE_REDIS_ACTIVATION_MISMATCH` | `redis.enabled` disagrees with `features.redis-enabled` |
| `ORGSEC_STORAGE_REDIS_MODULE_REQUIRED` | Redis enabled, `orgsec-storage-redis` absent |
| `ORGSEC_STORAGE_JWT_MODULE_REQUIRED` | JWT enabled, `orgsec-storage-jwt` absent |
| `ORGSEC_STORAGE_JWT_REDIS_UNSUPPORTED` | JWT and Redis enabled together |

| Property                              | Type      | Default   | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | --------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `primary`                             | `String`  | `"memory"`| **Inert.** No code reads it.                                                             | [Choose storage](../storage/01-choose-storage.md) |
| `fallback`                            | `String`  | `"memory"`| **Inert.** No code reads it; the Redis backend does not fall back to another storage on miss or outage. | [Choose storage](../storage/01-choose-storage.md) |

### Feature flags - `orgsec.storage.features.*`

| Property                              | Type      | Default | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `jwt-enabled`                         | `boolean` | `false` | Activates the JWT backend. Requires `orgsec-storage-jwt`, and cannot be combined with Redis. | [Storage / JWT](../storage/04-jwt.md)                          |
| `redis-enabled`                       | `boolean` | `false` | Does **not** activate Redis - `orgsec.storage.redis.enabled` does. This flag only decides whether the in-memory storage keeps `@Primary`, so it must be set to the same value or startup is refused. | [Storage / Redis](../storage/03-redis.md)                      |
| `memory-enabled`                      | `boolean` | `true`  | **Inert.** The in-memory backend is always available as a delegate.                      | [Storage / In-memory](../storage/02-in-memory.md)              |
| `hybrid-mode-enabled`                 | `boolean` | `false` | **Inert.** There is no per-data-type router to switch on.                                | [Hybrid storage](../storage/05-hybrid.md) |

### Per-data-type routing - `orgsec.storage.data-sources.*`

**Inert in 1.0.x.** These bind onto `StorageFeatureFlags` and are read by nothing; `getDataSource(...)`
has no call sites outside that class. Setting them has no effect on which backend answers a lookup.

| Property                              | Type     | Default     | Description                                                                              | See                                                            |
| ------------------------------------- | -------- | ----------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `person`                              | `String` | `"primary"` | **Inert.**                                                                               | [Storage / JWT](../storage/04-jwt.md#orgsec-configuration)     |
| `organization`                        | `String` | `"primary"` | **Inert.**                                                                               | [Hybrid storage](../storage/05-hybrid.md) |
| `role`                                | `String` | `"primary"` | **Inert.**                                                                               | [Hybrid storage](../storage/05-hybrid.md) |
| `privilege`                           | `String` | `"memory"`  | **Inert.**                                                                               | [Hybrid storage](../storage/05-hybrid.md) |

---

## `RedisStorageProperties` - `orgsec.storage.redis.*`

Bound only when `orgsec-storage-redis` is on the classpath. All defaults apply per-instance.

### Activation - `orgsec.storage.redis.*`

| Property                              | Type      | Default      | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------------ | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                             | `boolean` | `false`      | Gates `RedisStorageAutoConfiguration` via `@ConditionalOnProperty`. Must be `true` for any Redis bean to be created and for `security-dataset-id` to be required. | [Storage / Redis](../storage/03-redis.md#activation) |
| `security-dataset-id`                 | `String`  | (none)       | **Required when Redis is enabled.** Stable deployment-unique ID shared by all instances serving the same security dataset; maximum 256 UTF-8 bytes. | [Storage / Redis](../storage/03-redis.md#activation) |

### Connection - `orgsec.storage.redis.*`

| Property                              | Type      | Default      | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------------ | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `host`                                | `String`  | `"localhost"`| Redis server hostname. **Required.**                                                     | [Storage / Redis](../storage/03-redis.md#connection-settings)  |
| `port`                                | `int`     | `6379`       | Redis port (1-65535).                                                                    | [Storage / Redis](../storage/03-redis.md#connection-settings)  |
| `password`                            | `String`  | (none)       | Optional password.                                                                       | [Storage / Redis](../storage/03-redis.md#connection-settings)  |
| `ssl`                                 | `boolean` | `false`      | TLS for Redis connections. **Mandatory in production.**                                  | [Storage / Redis](../storage/03-redis.md#connection-settings)  |
| `timeout`                             | `int`     | `2000`       | Connection timeout in milliseconds.                                                      | [Storage / Redis](../storage/03-redis.md#connection-settings)  |

### TTLs - `orgsec.storage.redis.ttl.*`

| Property                              | Type     | Default | Description                                                                              | See                                                            |
| ------------------------------------- | -------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `person`                          | `long`   | `3600`  | Cache TTL for `PersonDef` in seconds.                                                    | [Storage / Redis](../storage/03-redis.md#ttl-configuration)    |
| `organization`                    | `long`   | `7200`  | Cache TTL for `OrganizationDef` in seconds.                                              | [Storage / Redis](../storage/03-redis.md#ttl-configuration)    |
| `role`                            | `long`   | `7200`  | Cache TTL for `RoleDef` in seconds.                                                      | [Storage / Redis](../storage/03-redis.md#ttl-configuration)    |
| `privilege`                       | `long`   | `7200`  | Cache TTL for `PrivilegeDef` in seconds.                                                 | [Storage / Redis](../storage/03-redis.md#ttl-configuration)    |
| `on-security-change`              | `long`   | `300`   | Reserved; not enforced in 1.0.x. Intended as a reduced TTL after `notifyXxxChanged`; the current update path uses the per-type TTLs above. | [Storage / Redis](../storage/03-redis.md#ttl-configuration)    |

### L1 cache - `orgsec.storage.redis.cache.*`

| Property                              | Type      | Default | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `l1-enabled`                    | `boolean` | `true`  | Reserved; not enforced in 1.0.x. The L1 LRU is always created. Use `l1-max-size` to bound it. | [Storage / Redis](../storage/03-redis.md#l1-cache)             |
| `l1-max-size`                   | `int`     | `1000`  | Maximum number of entries in L1 (LRU eviction).                                          | [Storage / Redis](../storage/03-redis.md#l1-cache)             |
| `obfuscate-keys`                | `boolean` | `false` | SHA-256 hash on cache keys; trades visibility for namespace privacy.                     | [Storage / Redis](../storage/03-redis.md#l1-cache)             |

### Pub/Sub invalidation - `orgsec.storage.redis.invalidation.*`

| Property                              | Type      | Default                  | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------------------------ | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                | `boolean` | `false`                  | Publish/subscribe invalidation events. Default off. Not part of the 1.1 GET/LIST proof. | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)  |
| `async`                  | `boolean` | `true`                   | Publish invalidation events asynchronously.                                              | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)  |
| `channel`                | `String`  | `"orgsec:invalidation"`  | Redis channel name. Change for multi-tenant Redis.                                       | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)  |

### Preload - `orgsec.storage.redis.preload.*`

> 1.1.0 GET/LIST use the snapshot coordinator, not `CacheWarmer`. These properties still bind for
> the leftover cache plane. Do not treat preload as the authorization load path.

| Property                              | Type      | Default   | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | --------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                     | `boolean` | `true`    | Run leftover `CacheWarmer` loaders at startup. Not the 1.1 snapshot load path. | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)   |
| `on-startup`                  | `boolean` | `true`    | Trigger leftover preload during `ApplicationContext` startup.                  | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)   |
| `strategy`                    | `String`  | `"all"`   | One of `all`, `persons`, `organizations`, `roles`.                              | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)   |
| `mode`                        | `String`  | `"eager"` | `eager` / `progressive` / `lazy` leftover warmup modes.                        | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)   |
| `batch-size`                  | `int`     | `100`     | Also used as the snapshot loader batch size.                                   | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)   |
| `batch-delay-ms`              | `long`    | `50`      | Delay between leftover warmup batches.                    | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)   |
| `async`                       | `boolean` | `false`   | Detach leftover preload from the startup path.            | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)   |
| `parallelism`                 | `int`     | `2`       | Threads for leftover parallel warmup.                     | [Storage / Redis](../storage/03-redis.md#legacy-cache-plane)   |

### Circuit breaker - `orgsec.storage.redis.circuit-breaker.*`

| Property                              | Type      | Default | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`             | `boolean` | `true`  | Wrap Redis calls in a Resilience4j circuit breaker.                                      | [Storage / Redis](../storage/03-redis.md#circuit-breaker)      |
| `failure-threshold`   | `int`     | `50`    | Failure rate (%) above which the circuit opens.                                          | [Storage / Redis](../storage/03-redis.md#circuit-breaker)      |
| `wait-duration`       | `long`    | `30000` | Time in milliseconds before half-open probe.                                             | [Storage / Redis](../storage/03-redis.md#circuit-breaker)      |
| `sliding-window-size` | `int`     | `10`    | Window size for failure rate calculation.                                                | [Storage / Redis](../storage/03-redis.md#circuit-breaker)      |
| `minimum-calls`       | `int`     | `5`     | Minimum calls before failure rate is calculated.                                         | [Storage / Redis](../storage/03-redis.md#circuit-breaker)      |
| `permitted-calls-in-half-open` | `int` | `3`  | Probe calls allowed in half-open state.                                                   | [Storage / Redis](../storage/03-redis.md#circuit-breaker)      |

### Monitoring - `orgsec.storage.redis.monitoring.*`

| Property                              | Type      | Default | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `metrics-enabled`          | `boolean` | `true`  | Internal cache statistics. Not exported to Micrometer in 1.0.x.                          | [Operations / Monitoring](../operations/monitoring.md) |
| `health-check-enabled`     | `boolean` | `true`  | Reserved; not enforced in 1.0.x. The health indicator bean is always created when Redis is active.                                | [Storage / Redis](../storage/03-redis.md#health-and-monitoring)|

### Connection pool - `orgsec.storage.redis.pool.*`

| Property                              | Type      | Default | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                        | `boolean` | `true`  | Enable Lettuce connection pooling.                                                       | [Storage / Redis](../storage/03-redis.md#connection-pool)      |
| `min-idle`                       | `int`     | `5`     | Minimum idle connections.                                                                | [Storage / Redis](../storage/03-redis.md#connection-pool)      |
| `max-idle`                       | `int`     | `10`    | Maximum idle connections.                                                                | [Storage / Redis](../storage/03-redis.md#connection-pool)      |
| `max-active`                     | `int`     | `20`    | Maximum active connections.                                                              | [Storage / Redis](../storage/03-redis.md#connection-pool)      |
| `max-wait`                       | `long`    | `2000`  | Maximum wait for connection in milliseconds. `-1` blocks indefinitely.                   | [Storage / Redis](../storage/03-redis.md#connection-pool)      |
| `test-while-idle`                | `boolean` | `true`  | Run evictor thread on idle connections.                                                  | [Storage / Redis](../storage/03-redis.md#connection-pool)      |
| `time-between-eviction-runs`     | `long`    | `30000` | Time between eviction runs in milliseconds.                                              | [Storage / Redis](../storage/03-redis.md#connection-pool)      |
| `min-evictable-idle-time`        | `long`    | `60000` | Minimum idle time before a connection is eligible for eviction (ms).                     | [Storage / Redis](../storage/03-redis.md#connection-pool)      |

### Serialization - `orgsec.storage.redis.serialization.*`

| Property                              | Type      | Default | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `fail-on-unknown-properties` | `boolean` | `false` | Strict deserialization; trades forward compatibility for stricter validation.        | [Storage / Redis](../storage/03-redis.md)                      |
| `strict-mode`           | `boolean` | `false` | Apply additional security restrictions to ObjectMapper.                                  | [Storage / Redis](../storage/03-redis.md)                      |

### Audit - `orgsec.storage.redis.audit.*`

| Property                              | Type      | Default | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                       | `boolean` | `false` | Audit logging for Redis backend events.                                                  | [Monitoring](../operations/monitoring.md)         |
| `log-cache-access`              | `boolean` | `false` | Log every cache hit / miss (verbose).                                                    | [Monitoring](../operations/monitoring.md)         |
| `log-privilege-checks`          | `boolean` | `true`  | Log privilege check events.                                                              | [Monitoring](../operations/monitoring.md)         |
| `log-config-changes`            | `boolean` | `true`  | Log configuration changes (feature-flag flips).                                          | [Monitoring](../operations/monitoring.md)         |

---

## `JwtStorageProperties` - `orgsec.storage.jwt.*`

Bound only when `orgsec-storage-jwt` is on the classpath. Spring Security's OAuth2 resource-server configuration (`spring.security.oauth2.resourceserver.jwt.*`) is configured separately and supplies the `JwtDecoder` bean OrgSec requires.

| Property                              | Type      | Default          | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `claim-name`                          | `String`  | `"orgsec"`       | JWT claim that carries OrgSec data.                                                      | [Storage / JWT](../storage/04-jwt.md#the-orgsec-claim)         |
| `claim-version`                       | `String`  | `"1.0"`          | Expected `version` field inside the claim.                                               | [Storage / JWT](../storage/04-jwt.md#the-orgsec-claim)         |
| `token-header`                        | `String`  | `"Authorization"`| HTTP header carrying the bearer token.                                                   | [Storage / JWT](../storage/04-jwt.md)                          |
| `token-prefix`                        | `String`  | `"Bearer "`      | Prefix in front of the token in the header.                                              | [Storage / JWT](../storage/04-jwt.md)                          |
| `cache-parsed-person`                 | `boolean` | `true`           | Cache parsed `PersonDef` per request.                                                    | [Storage / JWT](../storage/04-jwt.md#orgsec-configuration)     |
| `cache-ttl-seconds`                   | `int`     | `60`             | Reserved; not enforced for cross-request caching in 1.0.x.                               | [Storage / JWT](../storage/04-jwt.md#orgsec-configuration)     |

---

## Verifying coverage

`PropertiesDocumentationCoverageTest` (in the `orgsec-spring-boot-starter` module) reflects every `@ConfigurationProperties` class in the project, walks the bound fields recursively, and checks that this document mentions each one. The test fails the build with a clear message if:

- A new property exists in the source and is not listed here.
- A property listed here no longer exists in the source.

If you change a property name or default, run:

```bash
mvn test -pl orgsec-spring-boot-starter -Dtest=PropertiesDocumentationCoverageTest
```

The test takes less than a second and is cheap to run on every build.

## Where to go next

- [Spring Boot starter](../spring/01-spring-boot-starter.md) - how the starter uses these properties.
- [Storage / Redis](../storage/03-redis.md) - Redis-specific recommendations.
- [Storage / JWT](../storage/04-jwt.md) - JWT-specific recommendations.
