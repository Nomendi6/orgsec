# Configuration

This page is the practical guide to configuring OrgSec. It covers the prefix layout, the minimum YAML you need, how to pick a storage backend, the per-data-type routing knobs, and the validation OrgSec performs at startup. For the exhaustive list of every property, see [Properties Reference](../reference/properties.md).

## The `orgsec.*` prefix

All OrgSec configuration lives under the `orgsec` prefix in your application configuration. There are five `@ConfigurationProperties` classes contributing to it, one per concern:

| Prefix                      | Bound class                  | Module                       | What it controls                                            |
| --------------------------- | ---------------------------- | ---------------------------- | ----------------------------------------------------------- |
| `orgsec` (top-level)        | `OrgsecProperties`           | `orgsec-spring-boot-starter` | Master switch, security toggles, feature flags, Person API  |
| `orgsec.business-roles`     | `BusinessRoleConfiguration`  | `orgsec-common`              | Business-role declarations and their `supported-fields`     |
| `orgsec.storage`            | `StorageFeatureFlags`        | `orgsec-storage-inmemory`    | Active backend, fallback, hybrid-mode data-source routing   |
| `orgsec.storage.redis`      | `RedisStorageProperties`     | `orgsec-storage-redis`       | Redis connection, TLS, L1/L2 cache, Pub/Sub, circuit breaker |
| `orgsec.storage.jwt`        | `JwtStorageProperties`       | `orgsec-storage-jwt`         | JWT claim name, header, parsed-Person caching               |

These classes are independent. A property under one prefix does not influence another - for example, `orgsec.storage.redis.enabled` and `orgsec.storage.features.redis-enabled` are *both* required to activate the Redis backend in hybrid mode.

## Minimum configuration

The smallest configuration that boots OrgSec is one block: declare your business roles. Storage defaults to in-memory; audit logging is off.

```yaml
orgsec:
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
    customer:
      supported-fields: [COMPANY, COMPANY_PATH, PERSON]
```

This is enough to pass the OrgSec auto-configuration. You still have to provide a `SecurityQueryProvider` bean and a `PrivilegeDefinitionProvider` bean for the in-memory backend to load anything, but the YAML side is complete.

If your JPA/entity property names do not match OrgSec's default RSQL selector convention, add `rsql-fields` under the affected role:

```yaml
orgsec:
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
      rsql-fields:
        COMPANY: ownerCompanyId
        COMPANY_PATH: ownerCompanyPath
        ORG: ownerOrgId
        ORG_PATH: ownerOrgPath
        PERSON: ownerPersonId
```

The value is the full RSQL selector. Dotted JPA relationship paths such as `ownerCompany.id` and flat id properties such as `ownerCompanyId` are both valid. Selectors are validated at startup and must be simple dotted property paths without RSQL operators or whitespace. A selector may only be configured for a field that is also listed in `supported-fields`.

## Top-level toggles

`OrgsecProperties` exposes four sub-objects: `security`, `features`, `storage` (a different one from `StorageFeatureFlags` - see below), and `api`.

```yaml
orgsec:
  enabled: true                       # master switch (default true)
  security:
    privilege-checking: true          # reserved; not enforced in 1.0.x
    role-hierarchy: true              # reserved; not enforced in 1.0.x
    audit-logging: false              # reserved; see Auditing below for the actual switch
  features:
    business-roles: true              # reserved; not enforced in 1.0.x
    position-roles: true              # reserved; not enforced in 1.0.x
    delegations: false                # reserved; delegation feature not implemented in 1.0.x
  api:
    person:
      enabled: false                  # expose /api/orgsec/person/by-user/{userId} (default false)
      required-role: ORGSEC_API_CLIENT # role required to call the endpoint
```

The `security.*` and `features.*` blocks are **reserved** in 1.0.x - the fields bind from YAML but no OrgSec code reads them at runtime. They are stable property names so configuration written today survives a future minor release that wires them up. The only top-level switches that affect runtime behavior in 1.0.x are `enabled` (the master switch) and `api.person.*` (the Person API).

The Person API is **off by default** (post-security-review). Turn it on only when you also configure a Spring Security rule that requires the role named in `required-role`. It is intended for Keycloak service accounts that need to read OrgSec data when assembling the JWT claim. See [Cookbook / Keycloak mapper](../cookbook/05-keycloak-mapper.md) for the full setup.

> **Spring Security `ROLE_` prefix.** OrgSec's auto-configured filter chain protects the Person API with `hasRole(requiredRole)`. With the default `required-role: ORGSEC_API_CLIENT`, the authenticated principal must carry the authority `ROLE_ORGSEC_API_CLIENT` - Spring Security adds the `ROLE_` prefix automatically. If your IdP emits unprefixed authorities (e.g. raw Keycloak realm roles), either map them to `ROLE_*` authorities, set `required-role` to a value your authorities already use, or override the `orgsecApiSecurityFilterChain` bean with a rule that matches your authority shape.

## Choosing a storage backend

`StorageFeatureFlags` is the entry point for storage routing. There are three knobs:

```yaml
orgsec:
  storage:
    primary: memory                   # memory | redis | jwt (default memory)
    fallback: memory                  # reserved/informational in 1.0.x; see note below
    features:
      memory-enabled: true            # default true
      redis-enabled: false            # must be true to activate Redis backend
      jwt-enabled: false              # must be true to activate JWT backend
      hybrid-mode-enabled: false      # routes per-data-type via data-sources below
    data-sources:                     # only consulted when hybrid-mode-enabled=true
      person: primary                 # primary | memory | redis | jwt
      organization: primary
      role: primary
      privilege: memory
```

A few things to know:

- **Backend selection uses feature flags plus the `primary` selector.** Adding a storage module's JAR to the classpath does not enable it on its own. For in-memory and JWT, set the matching `features.*-enabled` flag and (if you want it to be primary) `primary` to the matching name. **Redis additionally requires `orgsec.storage.redis.enabled: true`**, because its auto-configuration is separately gated by `@ConditionalOnProperty` - see the [Redis backend section below](#redis-backend-essentials) and [Storage / Redis - Activation](../storage/03-redis.md#activation). The flag layering lets you bring a backend's beans into the context without making them `@Primary`.
- **Hybrid mode is opt-in.** When `hybrid-mode-enabled=false`, OrgSec uses the `primary` backend for everything and ignores `data-sources`. When it is `true`, each entity type is fetched from the source listed in `data-sources` - this is how the JWT setup keeps `Person` in JWT while leaving organizations and roles in memory or Redis.
- **`fallback` is informational in 1.0.x.** It is exposed for routing decisions in higher layers (and for future use by the storage facade), but the Redis backend itself does not "fall back" to another storage on miss - it simply returns `null`. Set it to `memory` unless you have a specific reason.
- **`StorageFeatureFlags` is mutable at runtime.** Spring exposes it as a bean with setter methods (`enableJwtStorage`, `setPersonDataSource`, etc.). You can flip the flags from an admin endpoint without restarting; see the bean's javadoc for available methods.

The deprecated `orgsec.storage.type` property on `OrgsecProperties` predates the multi-backend split and is no longer the canonical way to select a backend. Prefer `orgsec.storage.primary`. The two should agree if you set both.

## Per-data-type routing (hybrid mode)

The most common use of hybrid mode is the JWT pattern: the current user's `Person` arrives in a JWT claim, but you still want organizations and roles to be served from memory (or Redis):

```yaml
orgsec:
  storage:
    primary: memory                   # everything that is not routed below
    features:
      memory-enabled: true
      jwt-enabled: true
      hybrid-mode-enabled: true       # opt in
    data-sources:
      person: jwt                     # PersonDef from token claims
      organization: primary           # = memory in this config
      role: primary
      privilege: memory               # always recommended
```

The same pattern applies for Redis. Keep `privilege: memory` even when other types are in Redis - the privilege definitions are fully populated at startup from your `PrivilegeDefinitionProvider` and there is no benefit to round-tripping them through the cache.

For multi-instance Redis with cross-instance invalidation, see [Storage / Redis](../storage/03-redis.md). For JWT and Keycloak, see [Storage / JWT](../storage/04-jwt.md).

## Redis backend (essentials)

Adding `orgsec-storage-redis` to the classpath gives you a large surface of Redis-specific properties. The defaults are reasonable; the ones you almost always have to override are connection details and TLS:

```yaml
orgsec:
  storage:
    primary: redis
    features:
      redis-enabled: true             # tells the storage facade Redis is active
      memory-enabled: true
    redis:
      enabled: true                   # gates the RedisStorageAutoConfiguration
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      ssl: true                       # MANDATORY in production
      timeout: 2000                   # connect timeout in ms
      ttl:
        person: 3600                  # seconds
        organization: 7200
        role: 7200
        privilege: 7200
      cache:
        l1-enabled: true
        l1-max-size: 1000
      invalidation:
        enabled: false                # default off; turn on for multi-instance
        channel: orgsec:invalidation
      preload:
        enabled: true
        on-startup: true
        strategy: all                 # all | persons | organizations | roles
        mode: eager                   # eager | progressive | lazy
      circuit-breaker:
        enabled: true
        failure-threshold: 50
        wait-duration: 30000
      pool:
        min-idle: 5
        max-idle: 10
        max-active: 20
```

Notes:

- **`ssl: true` is required for production.** Sending Redis traffic in plaintext puts every authorization decision your app makes on the wire. The default is `false` for local development; the production checklist enforces `true`.
- **`invalidation.enabled` defaults to `false`.** Enable it only after you have verified that `orgsec:invalidation` is not used by another consumer on the same Redis instance. See [Cookbook / Cache invalidation](../cookbook/04-cache-invalidation.md).
- **Preload runs at startup.** If your data set is large, switch `mode` to `progressive` to avoid blocking the Spring `ApplicationContext`.

## JWT backend (essentials)

The JWT backend is short on its own knobs - the heavy lifting happens in Spring Security's OAuth2 resource-server configuration:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://idp.example.com/realms/myrealm   # supplies the JwtDecoder

orgsec:
  storage:
    primary: jwt
    features:
      jwt-enabled: true
      memory-enabled: true            # in-memory serves org/role/privilege
      hybrid-mode-enabled: true
    data-sources:
      person: jwt
      organization: primary
      role: primary
      privilege: memory
    jwt:
      claim-name: orgsec              # the claim name your IdP injects
      claim-version: "1.0"
      token-header: Authorization
      token-prefix: "Bearer "
      cache-parsed-person: true       # parse once per request
```

If a `JwtDecoder` bean is not present at startup, OrgSec **fails fast** with a clear error. This is intentional - an OrgSec deployment that accepts unverified tokens is a critical security regression. Configure `spring.security.oauth2.resourceserver.jwt.issuer-uri` (or supply a custom `JwtDecoder` bean) before turning on `jwt-enabled`.

## Auditing

Audit logging is off by default. The actual switch in 1.0.x is on the Redis storage module:

```yaml
orgsec:
  storage:
    redis:
      audit:
        enabled: true                 # turns the SecurityAuditLogger bean from no-op to real
```

When the Redis backend is active and `audit.enabled: true`, the auto-configuration wires `DefaultSecurityAuditLogger`, which logs through SLF4J on the `SECURITY_AUDIT` logger with structured MDC keys (`audit.type`, `audit.personId`, `audit.privilege`, `audit.resource`, `audit.granted`, ...). When `audit.enabled: false` (the default), the auto-configuration wires `NoOpSecurityAuditLogger` instead. Without the Redis module on the classpath at all, no `SecurityAuditLogger` bean is auto-wired - supply your own if you want audit events.

The `orgsec.security.audit-logging` field above is reserved - it does not control audit logging in 1.0.x. Use the Redis switch.

To route OrgSec audit events into a dedicated appender, use the MDC keys above with a Logback or Log4j2 filter on the `SECURITY_AUDIT` logger. See [Operations / Monitoring](../operations/monitoring.md).

## Validation at startup

The 1.0.x line ships one hard fail-fast and several softer expectations.

| Check                                                | Behavior                                                                                                                                                              |
| ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `JwtDecoder` bean exists when JWT is active          | **Hard fail-fast** - `JwtStorageAutoConfiguration` throws `IllegalStateException` if `jwt-enabled: true` and no `JwtDecoder` bean is present. Aborts startup.    |
| Privilege identifier follows `RES_SCOPE_OP` shape    | `PrivilegeLoader.createPrivilegeDefinition` throws `IllegalArgumentException` for malformed identifiers, which propagates up through the registering provider's `@PostConstruct`. |
| Each business role has at least one supported field  | *Recommendation, not enforced.* OrgSec accepts an empty `supported-fields` list; a role with no fields will simply never match an entity. Catch this in your tests.    |
| Redis host reachable                                 | *Not validated at startup.* The Redis client connects lazily, and the circuit breaker handles outages at runtime. A misconfigured host appears as connection errors at first use, not as a startup abort. |
| `required-role` set when Person API is enabled       | *Not validated at startup.* `OrgsecProperties.Api.Person` carries a default of `ORGSEC_API_CLIENT`, so the field is always populated. Ensure your Spring Security rule actually requires it. |

Treat the *recommendation* rows as part of your own production checklist: write a Spring Boot integration test that brings up the context with your real configuration, asserts the bean graph, and exercises one privilege check end-to-end. If startup fails with a message you do not recognize, search [Operations / Troubleshooting](../operations/troubleshooting.md).

## Profiles

You typically split OrgSec configuration across profiles:

- **`application.yml`** - business roles, defaults, anything the same in all environments.
- **`application-dev.yml`** - `redis.ssl: false`, `redis.audit.enabled: true`, smaller TTLs.
- **`application-prod.yml`** - `redis.ssl: true`, `redis.password` from env, larger TTLs, `invalidation.enabled: true` if multi-instance.

A small example of the dev override:

```yaml
# application-dev.yml
orgsec:
  storage:
    redis:
      ssl: false
      invalidation:
        enabled: false
      preload:
        mode: progressive             # avoid blocking dev startup
      audit:
        enabled: true                 # actual audit-logging switch in 1.0.x
```

## Programmatic overrides

`StorageFeatureFlags` exposes setter methods (`enableJwtStorage`, `setPersonDataSource`, ...). Calling those methods at runtime takes effect immediately for new requests; existing requests continue with whatever backend they already resolved. The mutability is intended for ops endpoints that flip a feature without restart, not for per-request routing - per-request routing is what hybrid mode is for.

## Where to go next

- [Properties Reference](../reference/properties.md) - every property name, type, default, and meaning.
- [Privileges and Business Roles](./05-privileges-and-business-roles.md) - how to design your privilege set.
- [Storage Overview](../storage/01-overview.md) - backend decision tree.
- [Operations / Production checklist](../operations/production-checklist.md) - enforce these settings before going live.
