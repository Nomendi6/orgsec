# Properties Reference

This is the complete catalogue of OrgSec configuration properties for the **1.0.x** line. The list is generated from the five `@ConfigurationProperties` classes in the codebase and is verified by `PropertiesDocumentationCoverageTest` - if you add or remove a property in the source, the test will fail until this file is updated.

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
| `enabled`                             | `boolean` | `true`                 | Master OrgSec switch. Setting `false` disables auto-configuration.                       | [Configuration](../guide/04-configuration.md#top-level-toggles) |

### Security toggles - `orgsec.security.*`

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `privilege-checking`                  | `boolean` | `true`                 | Reserved; not enforced in 1.0.x. The field binds from YAML but no OrgSec code reads it.  | [Configuration](../guide/04-configuration.md#top-level-toggles) |
| `role-hierarchy`                      | `boolean` | `true`                 | Reserved; not enforced in 1.0.x.                                                         | [Privileges and Business Roles](../guide/05-privileges-and-business-roles.md) |
| `audit-logging`                       | `boolean` | `false`                | Reserved; not enforced in 1.0.x. To enable audit logging, use `orgsec.storage.redis.audit.enabled` or supply your own `SecurityAuditLogger` bean. | [Configuration - Auditing](../guide/04-configuration.md#auditing) |

### Feature flags - `orgsec.features.*`

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `business-roles`                      | `boolean` | `true`                 | Reserved; not enforced in 1.0.x. Business-role aggregation is always active when `BusinessRoleConfiguration` is on the classpath. | [Privileges and Business Roles](../guide/05-privileges-and-business-roles.md) |
| `position-roles`                      | `boolean` | `true`                 | Reserved; not enforced in 1.0.x.                                                         | [Core Concepts](../guide/03-core-concepts.md#business-role-vs-position-role) |
| `delegations`                         | `boolean` | `false`                | Reserved; delegation feature not implemented in 1.0.x.                                   | - |

### Storage type and in-memory tuning - `orgsec.storage.*` (legacy properties on `OrgsecProperties`)

These properties predate `StorageFeatureFlags`. The canonical way to select a backend is `orgsec.storage.primary` (see the next section). `orgsec.storage.type` is kept for backwards compatibility; the in-memory sub-section holds reserved tuning knobs.

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `type`                                | `String`  | `"inmemory"`           | Legacy storage type selector. Prefer `orgsec.storage.primary`.                           | [Configuration](../guide/04-configuration.md#choosing-a-storage-backend) |
| `inmemory.cache-ttl`                  | `int`     | `3600`                 | Reserved; not enforced by the in-memory backend in 1.0.x.                                | [Storage / In-memory](../storage/02-in-memory.md#configuration) |
| `inmemory.max-entries`                | `int`     | `10000`                | Reserved; not enforced by the in-memory backend in 1.0.x.                                | [Storage / In-memory](../storage/02-in-memory.md#configuration) |

### Person API - `orgsec.api.person.*`

The Person API is consumed by Keycloak's custom protocol mapper to assemble the `orgsec` claim. It is **off** by default; enable only with a properly configured `required-role`.

| Property                              | Type      | Default                | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ---------------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                             | `boolean` | `false`                | Expose `GET /api/orgsec/person/by-user/{userId}`.                                               | [Cookbook / Keycloak mapper](../cookbook/05-keycloak-mapper.md) |
| `required-role`                       | `String`  | `"ORGSEC_API_CLIENT"`  | Role required to call the endpoint. Enforced via Spring Security `hasRole(requiredRole)`, which **prepends `ROLE_`** - the authenticated principal must carry authority `ROLE_<requiredRole>` (default: `ROLE_ORGSEC_API_CLIENT`). | [Configuration - Person API](../guide/04-configuration.md#top-level-toggles) |

---

## `BusinessRoleConfiguration` - `orgsec.business-roles.*`

Each entry under `business-roles` is a *named* business role with a list of supported field types. The role name (`<role>`) is free-form - OrgSec does not pre-declare any business roles in 1.0.x.

| Property                              | Type                    | Default | Description                                                                              | See                                                            |
| ------------------------------------- | ----------------------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `<role>.supported-fields`             | `Set<SecurityFieldType>`| `[]`    | Subset of `COMPANY`, `COMPANY_PATH`, `ORG`, `ORG_PATH`, `PERSON` the entity exposes for this role. | [Privileges and Business Roles](../guide/05-privileges-and-business-roles.md#supported-fields-semantics) |
| `<role>.rsql-fields`                  | `Map<String,String>`    | `{}`    | Optional RSQL selector override per security field (`COMPANY`, `COMPANY_PATH`, `ORG`, `ORG_PATH`, `PERSON`). Values must be simple dotted property paths such as `ownerCompanyId` or `ownerCompany.id`. Each configured key must also appear in `<role>.supported-fields` (this includes path types `COMPANY_PATH` and `ORG_PATH`). | [RSQL Filtering](../cookbook/03-rsql-filtering.md#custom-rsql-field-selectors) |

---

## `StorageFeatureFlags` - `orgsec.storage.*`

`StorageFeatureFlags` (in `orgsec-storage-inmemory`) is the canonical place to configure the active backend, the fallback, the per-feature flags, and per-data-type routing.

### Active backend - `orgsec.storage.*`

| Property                              | Type      | Default       | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `primary`                             | `String`  | `"memory"`    | Active backend: `memory` / `redis` / `jwt`.                                              | [Configuration](../guide/04-configuration.md#choosing-a-storage-backend) |
| `fallback`                            | `String`  | `"memory"`    | Reserved/informational in 1.0.x. Exposed by `StorageFeatureFlags` for higher-level routing and future fallback behavior; the Redis backend itself does not fall back to this storage on miss or outage.                                 | [Configuration - storage selection](../guide/04-configuration.md#choosing-a-storage-backend)      |

### Feature flags - `orgsec.storage.features.*`

| Property                              | Type      | Default | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `memory-enabled`                      | `boolean` | `true`  | Activate the in-memory backend (also used as a delegate).                                | [Storage / In-memory](../storage/02-in-memory.md)              |
| `redis-enabled`                       | `boolean` | `false` | Activate the Redis backend (must be `true` alongside `primary=redis`).                   | [Storage / Redis](../storage/03-redis.md)                      |
| `jwt-enabled`                         | `boolean` | `false` | Activate the JWT backend (must be `true` alongside `primary=jwt`).                       | [Storage / JWT](../storage/04-jwt.md)                          |
| `hybrid-mode-enabled`                 | `boolean` | `false` | When `true`, honor `data-sources` per data type. When `false`, route everything to `primary`. | [Configuration](../guide/04-configuration.md#per-data-type-routing-hybrid-mode) |

### Hybrid-mode routing - `orgsec.storage.data-sources.*`

Each entry routes one entity type to a specific source. Honored only when `hybrid-mode-enabled: true`.

| Property                              | Type     | Default     | Description                                                                              | See                                                            |
| ------------------------------------- | -------- | ----------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `person`                              | `String` | `"primary"` | One of `primary`, `memory`, `redis`, `jwt`.                                              | [Storage / JWT](../storage/04-jwt.md#orgsec-configuration)     |
| `organization`                        | `String` | `"primary"` | One of `primary`, `memory`, `redis`, `jwt`.                                              | [Configuration](../guide/04-configuration.md#per-data-type-routing-hybrid-mode) |
| `role`                                | `String` | `"primary"` | One of `primary`, `memory`, `redis`, `jwt`.                                              | [Configuration](../guide/04-configuration.md#per-data-type-routing-hybrid-mode) |
| `privilege`                           | `String` | `"memory"`  | One of `primary`, `memory`, `redis`, `jwt`. Recommended to keep at `memory`.             | [Configuration](../guide/04-configuration.md#per-data-type-routing-hybrid-mode) |

---

## `RedisStorageProperties` - `orgsec.storage.redis.*`

Bound only when `orgsec-storage-redis` is on the classpath. All defaults apply per-instance.

### Activation - `orgsec.storage.redis.*`

| Property                              | Type      | Default      | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | ------------ | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                             | `boolean` | `false`      | Gates `RedisStorageAutoConfiguration` via `@ConditionalOnProperty`. Must be `true` for any Redis bean to be created. Not bound on `RedisStorageProperties` - consumed directly by Spring. | [Storage / Redis](../storage/03-redis.md#activation) |

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
| `enabled`                | `boolean` | `false`                  | Publish/subscribe invalidation events. Default off.                                      | [Storage / Redis](../storage/03-redis.md#pubsub-invalidation)  |
| `async`                  | `boolean` | `true`                   | Publish invalidation events asynchronously.                                              | [Storage / Redis](../storage/03-redis.md#pubsub-invalidation)  |
| `channel`                | `String`  | `"orgsec:invalidation"`  | Redis channel name. Change for multi-tenant Redis.                                       | [Storage / Redis](../storage/03-redis.md#pubsub-invalidation)  |

### Preload - `orgsec.storage.redis.preload.*`

> Redis preload is **not** automatic database loading. Applications must register `CacheWarmer` data loaders (`setPersonLoader`, `setOrganizationLoader`, `setRoleLoader`); without them, every preload call completes with 0 records.

| Property                              | Type      | Default   | Description                                                                              | See                                                            |
| ------------------------------------- | --------- | --------- | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `enabled`                     | `boolean` | `true`    | Run configured `CacheWarmer` loaders at startup. No-op unless the application registers loaders. | [Storage / Redis](../storage/03-redis.md#preload-strategies)   |
| `on-startup`                  | `boolean` | `true`    | Trigger preload during `ApplicationContext` startup.                                     | [Storage / Redis](../storage/03-redis.md#preload-strategies)   |
| `strategy`                    | `String`  | `"all"`   | One of `all`, `persons`, `organizations`, `roles`.                                       | [Storage / Redis](../storage/03-redis.md#preload-strategies)   |
| `mode`                        | `String`  | `"eager"` | `eager` (single batch on startup), `progressive` (batched with delay), `lazy` (no startup warmup in 1.0.x; reads do not auto-warm). | [Storage / Redis](../storage/03-redis.md#preload-strategies)   |
| `batch-size`                  | `int`     | `100`     | Items per batch in progressive mode.                                                     | [Storage / Redis](../storage/03-redis.md#preload-strategies)   |
| `batch-delay-ms`              | `long`    | `50`      | Delay between batches in milliseconds.                                                   | [Storage / Redis](../storage/03-redis.md#preload-strategies)   |
| `async`                       | `boolean` | `false`   | Detach preload from the startup path.                                                    | [Storage / Redis](../storage/03-redis.md#preload-strategies)   |
| `parallelism`                 | `int`     | `2`       | Number of threads for parallel warmup.                                                   | [Storage / Redis](../storage/03-redis.md#preload-strategies)   |

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
| `enabled`                       | `boolean` | `false` | Audit logging for Redis backend events.                                                  | [Configuration](../guide/04-configuration.md#auditing)         |
| `log-cache-access`              | `boolean` | `false` | Log every cache hit / miss (verbose).                                                    | [Configuration](../guide/04-configuration.md#auditing)         |
| `log-privilege-checks`          | `boolean` | `true`  | Log privilege check events.                                                              | [Configuration](../guide/04-configuration.md#auditing)         |
| `log-config-changes`            | `boolean` | `true`  | Log configuration changes (feature-flag flips).                                          | [Configuration](../guide/04-configuration.md#auditing)         |

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

- [Configuration guide](../guide/04-configuration.md) - how the properties fit together.
- [Storage / Redis](../storage/03-redis.md) - Redis-specific recommendations.
- [Storage / JWT](../storage/04-jwt.md) - JWT-specific recommendations.
