# Auto-configuration

This page describes how OrgSec's Spring Boot auto-configurations decide what to wire and how application code overrides any of it.

OrgSec ships **five** classes registered through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Three are the headline auto-configurations covered in detail on this page; the remaining two are smaller helpers documented briefly below.

| Class                                | Module                       | Imported in `AutoConfiguration.imports`? | Role                                                         |
| ------------------------------------ | ---------------------------- | ---------------------------------------- | ------------------------------------------------------------ |
| `OrgsecAutoConfiguration`            | `orgsec-spring-boot-starter` | Yes                                      | Top-level wiring + the `api`-package component scan          |
| `PersonApiServiceConfiguration`      | `orgsec-spring-boot-starter` | Yes                                      | Person API controller, service, and `orgsecApiSecurityFilterChain` |
| `StorageConfiguration`               | `orgsec-storage-inmemory`    | Yes                                      | `primaryInMemoryStorage`, `delegateSecurityDataStorage`, placeholder JWT/Redis beans |
| `RedisStorageAutoConfiguration`      | `orgsec-storage-redis`       | Yes (opt-in via `orgsec-storage-redis` JAR) | Redis backend + cache infrastructure                       |
| `JwtStorageAutoConfiguration`        | `orgsec-storage-jwt`         | Yes (opt-in via `orgsec-storage-jwt` JAR)   | JWT backend + Spring Security JWT integration              |

Spring Boot picks each up without your application doing anything beyond adding the JAR. The three headline classes covered in detail below are `OrgsecAutoConfiguration`, `RedisStorageAutoConfiguration`, and `JwtStorageAutoConfiguration`. The other two are described as part of "remaining beans" further down.

## `OrgsecAutoConfiguration`

```java
@AutoConfiguration
@ConditionalOnClass(PrivilegeSecurityService.class)
@EnableConfigurationProperties(OrgsecProperties.class)
@AutoConfigureBefore(SecurityAutoConfiguration.class)
@AutoConfigureAfter(JpaRepositoriesAutoConfiguration.class)
@ComponentScan(
    basePackages = {"com.nomendi6.orgsec.api"},
    excludeFilters = {
        // exclude api.controller.* and api.service.*
    }
)
public class OrgsecAutoConfiguration {
```

Five things to know:

- **`@ConditionalOnClass(PrivilegeSecurityService.class)`** - gate that ensures the in-memory module is on the classpath. Since the starter pulls `orgsec-storage-inmemory` in transitively, this is effectively always true; the conditional exists to keep the auto-config dormant if a future deployment removes the in-memory module entirely.
- **`@EnableConfigurationProperties(OrgsecProperties.class)`** - binds the top-level `orgsec.*` block to `OrgsecProperties`. The other property classes (`BusinessRoleConfiguration`, `StorageFeatureFlags`, `RedisStorageProperties`, `JwtStorageProperties`) bind from their own `@ConfigurationProperties` annotations and do not need to be enabled here.
- **`@AutoConfigureBefore(SecurityAutoConfiguration.class)`** - OrgSec's auto-config runs *before* Spring Security's, which gives `OrgsecAutoConfiguration` a chance to contribute or replace beans Spring Security would otherwise create. The `orgsecApiSecurityFilterChain` filter-chain ordering itself is set by `@Order(SecurityProperties.BASIC_AUTH_ORDER - 50)` on the bean declaration in `PersonApiServiceConfiguration`, not by this `@AutoConfigureBefore` directive.
- **`@AutoConfigureAfter(JpaRepositoriesAutoConfiguration.class)`** - OrgSec's auto-config runs *after* Spring's JPA-repositories auto-config, so application-side `SecurityQueryProvider` beans that depend on JPA repositories are available when OrgSec wires its in-memory storage.
- **`@ComponentScan(basePackages = "com.nomendi6.orgsec.api", excludeFilters = ...)`** - scans the `api` package only (with `api.controller.*` and `api.service.*` excluded; those are wired by `PersonApiServiceConfiguration` instead). The Java package `com.nomendi6.orgsec.api` contains the SPI interfaces `PrivilegeRegistry` and `PrivilegeDefinitionProvider`. (Note: `SecurityEnabledEntity` and `SecurityEnabledDTO` files sit in the same source directory but declare `package com.nomendi6.orgsec.interfaces;`, so the `api` scan does not reach them - not that it matters, since they are interfaces.) The scan picks up any `@Component`-annotated classes inside the `api` package, but the interface implementations live in other packages and reach the context through other means. For example, `PrivilegeRegistry`'s implementation is `AllPrivilegesStore` in `com.nomendi6.orgsec.storage.inmemory.store` - the `api` scan does **not** register that bean. The scan does not cover storage or common-module beans either; those are wired through the application-side component scan described below.

The class declares one bean directly:

| Bean                                          | Conditional                                                              | Purpose                                                            |
| --------------------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------------------------ |
| `SecurityContextProvider` (`SpringSecurityContextProvider`) | `@ConditionalOnMissingBean` plus `@ConditionalOnClass(SecurityContextHolder)` | Resolve the current user from Spring Security's context. Only wires if Spring Security is on the classpath and the application has not supplied its own `SecurityContextProvider`. |

The remaining beans the starter contributes are wired through two mechanisms:

1. **Auto-configurations registered through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.** Five classes total - the three headline ones above plus:
    - `com.nomendi6.orgsec.storage.inmemory.StorageConfiguration` - declares `primaryInMemoryStorage`, `delegateSecurityDataStorage`, and placeholder JWT / Redis beans. It does **not** declare the `AllPersonsStore`, `AllOrganizationsStore`, `PersonLoader`, etc., and it does not carry a `@ComponentScan`.
    - `com.nomendi6.orgsec.autoconfigure.PersonApiServiceConfiguration` - declares the Person API controller, service, and `orgsecApiSecurityFilterChain`.
    - The three headline classes (`OrgsecAutoConfiguration`, `RedisStorageAutoConfiguration`, `JwtStorageAutoConfiguration`) are themselves part of this list; they are documented in their own sections above.
2. **Application-side component scan.** The OrgSec storage and common beans (`AllPersonsStore`, `AllOrganizationsStore`, `AllRolesStore`, `AllPrivilegesStore`, the loaders, `InMemorySecurityDataStorage`, `PrivilegeChecker`, `RsqlFilterBuilder`, `BusinessRoleConfiguration`) are annotated with `@Component` / `@Configuration`. **They are not registered by any of the auto-configurations above.** They reach the application context only when the application's component scan covers their packages.

   The default `@SpringBootApplication` scan rooted at the application's own base package (e.g. `com.example.docs`) does not reach the OrgSec packages, so the application must declare an explicit `@ComponentScan`. The exact set depends on whether the application needs only the privilege evaluator (minimal) or the end-to-end current-user authorization flow (full).

   **Minimal evaluator scan** (Quick Start / wiring-only smoke tests):

   ```java
   @SpringBootApplication
   @ComponentScan(
       basePackages = {
           "com.example.docs",
           "com.nomendi6.orgsec.common",
           "com.nomendi6.orgsec.storage.inmemory"
       },
       excludeFilters = {
           @ComponentScan.Filter(
               type = FilterType.REGEX,
               pattern = "com\\.nomendi6\\.orgsec\\.storage\\.inmemory\\.StorageConfiguration"
           ),
           @ComponentScan.Filter(
               type = FilterType.REGEX,
               pattern = "com\\.nomendi6\\.orgsec\\.storage\\.inmemory\\.PrivilegeSecurityService"
           )
       }
   )
   public class Application { ... }
   ```

   **Full current-user scan** (real applications that map login / token to a person and use `PrivilegeSecurityService` directly):

   ```java
   @SpringBootApplication
   @ComponentScan(
       basePackages = {
           "com.example.app",
           "com.nomendi6.orgsec.common",
           "com.nomendi6.orgsec.storage.inmemory"
       },
       excludeFilters = @ComponentScan.Filter(
           type = FilterType.REGEX,
           pattern = "com\\.nomendi6\\.orgsec\\.storage\\.inmemory\\.StorageConfiguration"
       )
   )
   public class Application { ... }
   ```

   Pick the second form **only** when your application provides `PersonDataProvider` and `UserDataProvider` beans (and a `SecurityContextProvider`, which the starter wires by default when Spring Security is on the classpath). `PrivilegeSecurityService` requires all three in its constructor; without them, the context fails to construct it and startup aborts.

   Why the scan is narrow either way:

   - `com.nomendi6.orgsec.common` covers `PrivilegeChecker`, `RsqlFilterBuilder`, `BusinessRoleConfiguration`, and `SecurityDataStore`.
   - `com.nomendi6.orgsec.storage.inmemory` covers `InMemorySecurityDataStorage`, `StorageFeatureFlags`, the loader / store sub-packages, and (when not excluded) `PrivilegeSecurityService`.
   - The `StorageConfiguration` exclude is required in both forms because that class is already imported through `AutoConfiguration.imports`; including it again via the component scan duplicates its bean definitions.
   - `com.nomendi6.orgsec.autoconfigure`, `com.nomendi6.orgsec.api.controller`, `com.nomendi6.orgsec.api.service`, `com.nomendi6.orgsec.storage.jwt`, and `com.nomendi6.orgsec.storage.redis` are intentionally **left out**. They are wired by their own auto-configurations through Spring Boot's `AutoConfiguration.imports` mechanism, with property-based gating. A broad scan of `"com.nomendi6.orgsec"` would pick up `JwtClaimsParser` (an unconditional `@Component` whose constructor needs a `JwtDecoder`) the moment a user adds the JWT JAR to the classpath, which can fail startup before the user has opted into the JWT backend.

   This is a known wrinkle in 1.0.x - the starter is not as "zero-config" as a typical Spring Boot starter. Future releases may move the common / in-memory bean declarations into auto-configuration classes so the explicit scan becomes unnecessary.

## `RedisStorageAutoConfiguration`

```java
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass(RedisConnectionFactory.class)
@ConditionalOnProperty(prefix = "orgsec.storage.redis", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RedisStorageProperties.class)
public class RedisStorageAutoConfiguration {
```

Three gates, in order:

- **`@AutoConfigureBefore` is not used; `after = JacksonAutoConfiguration.class` is.** Redis serializers depend on Jackson's `ObjectMapper`, so Redis wiring must run after Jackson has set up its bean.
- **`@ConditionalOnClass(RedisConnectionFactory.class)`** - `spring-boot-starter-data-redis` must be on the classpath. The Redis module declares this dependency, so the condition is always satisfied when the JAR is present.
- **`@ConditionalOnProperty(prefix = "orgsec.storage.redis", name = "enabled", havingValue = "true")`** - the activation flag the application must set. Without it, no Redis bean is created. This is the gate that the configuration guide and properties reference both call out.

Beans declared by the class (selected, in declaration order):

| Bean                              | Conditional                                  | Purpose                                                            |
| --------------------------------- | -------------------------------------------- | ------------------------------------------------------------------ |
| `instanceId` (String)             | `@ConditionalOnMissingBean`                  | Unique-per-JVM identifier for the Pub/Sub publisher.               |
| `OrgsecObjectMapperFactory`       | `@ConditionalOnMissingBean`                  | Builds a Jackson `ObjectMapper` for cache serialization.           |
| `CacheKeyBuilder`                 | `@ConditionalOnMissingBean`                  | Computes cache keys (with optional SHA-256 obfuscation).           |
| `JsonSerializer<PersonDef>` (and orgs / roles / privileges) | `@ConditionalOnMissingBean(name = "...")` | Per-type Jackson serializers.                                       |
| L1 / L2 caches (`L1Cache`, `L2RedisCache`) | `@ConditionalOnMissingBean(name = "...")` | One pair per entity type.                                          |
| `RedisCircuitBreakerService`      | `@ConditionalOnMissingBean`                  | Wraps Redis calls in Resilience4j.                                 |
| `InvalidationEventPublisher`      | `@ConditionalOnMissingBean`                  | Publishes invalidation events. Created whenever the Redis auto-config is active - not gated by `invalidation.enabled`. When invalidation is disabled, the publisher is wired but its publishes are effectively silent (no listeners on the channel from this application). |
| `InvalidationEventListener`       | `@ConditionalOnMissingBean` + `@ConditionalOnProperty(prefix = "orgsec.storage.redis.invalidation", name = "enabled", havingValue = "true")` | Subscribes to the channel and drops L1 entries on remote invalidations. Only created when invalidation is enabled. |
| `RedisMessageListenerContainer`   | `@ConditionalOnProperty(prefix = "orgsec.storage.redis.invalidation", name = "enabled", havingValue = "true")` | Spring Data Redis container that delivers messages to the listener. Manages reconnect on its own. |
| `CacheWarmer`                     | `@ConditionalOnMissingBean`                  | Runs preload at `initialize()` if loaders are registered.          |
| `RedisStorageHealthIndicator`     | `@ConditionalOnMissingBean`                  | Spring Boot Actuator health indicator.                             |
| `SecurityAuditLogger`             | `@ConditionalOnMissingBean` + runtime branch on `orgsec.storage.redis.audit.enabled` | When `audit.enabled: false` (the default), the bean is a `NoOpSecurityAuditLogger`. When `audit.enabled: true`, it is a `DefaultSecurityAuditLogger`. The bean exists either way; the *behavior* depends on the flag. |
| `RedisSecurityDataStorage`        | `@Primary` (no `@ConditionalOnMissingBean`)  | The `@Primary` `SecurityDataStorage` bean when Redis is active. To override, declare your own `@Primary SecurityDataStorage` bean and exclude this auto-config (or use `@AutoConfiguration(after = ...)` to ensure your bean wins ordering). |

Most of the beans use `@ConditionalOnMissingBean` so a custom bean replaces OrgSec's default. The exceptions are noted in the table: `RedisSecurityDataStorage` is unconditionally created (override by excluding the auto-config), and `InvalidationEventListener` / `RedisMessageListenerContainer` only exist when invalidation is enabled.

## `JwtStorageAutoConfiguration`

```java
@AutoConfiguration
@EnableConfigurationProperties(JwtStorageProperties.class)
@ConditionalOnProperty(name = "orgsec.storage.features.jwt-enabled", havingValue = "true")
public class JwtStorageAutoConfiguration {
```

The JWT module's gate is on `orgsec.storage.features.jwt-enabled` (one of the three storage feature flags) rather than on a dedicated `orgsec.storage.jwt.enabled`. This reflects the JWT module's role as a "feature on top of an existing backend" rather than a standalone backend.

Beans:

| Bean                                | Conditional                                            | Purpose                                                            |
| ----------------------------------- | ------------------------------------------------------ | ------------------------------------------------------------------ |
| `JwtTokenContextHolder`             | `@ConditionalOnMissingBean`                            | Per-request holder for the parsed `PersonDef`.                     |
| `JwtClaimsParser`                   | `@ConditionalOnMissingBean`                            | Parses the `orgsec` claim. Requires `JwtDecoder`.                  |
| **fail-fast bean** (`Object`)       | `@ConditionalOnMissingBean(JwtDecoder.class)`          | Throws `IllegalStateException` at startup if the application has not supplied a `JwtDecoder`. The `Object` return type is intentional - the bean is never used; its constructor is the fail-fast point. |
| `JwtSecurityDataStorage` (named `jwtSecurityDataStorage`) | `@Primary` + `@ConditionalOnMissingBean(name = "jwtSecurityDataStorage")` | The `@Primary` storage when JWT is the active backend. Wraps a delegate. |
| `JwtTokenFilter` (registered as `FilterRegistrationBean`) | (always registered)                       | Reads the bearer token off the request and primes the context holder. |

The fail-fast pattern is worth noting:

```java
@Bean
@ConditionalOnMissingBean(JwtDecoder.class)
public Object jwtDecoderRequiredFailFast() {
    throw new IllegalStateException(
        "orgsec.storage.features.jwt-enabled=true requires a JwtDecoder bean. ...");
}
```

If a `JwtDecoder` is in the context, this method does not run (the conditional excludes it). If no decoder exists, Spring tries to create the bean, the constructor throws, and the application context fails to refresh. The application then never starts - which is the correct behavior for an OrgSec deployment that would otherwise accept unverified tokens.

## Override patterns

OrgSec's auto-configuration is built around `@ConditionalOnMissingBean` so that supplying your own bean of the same type replaces OrgSec's default. The four overrides applications need most often:

### 1. Custom `SecurityContextProvider`

```java
@Component
public class HeaderBasedContextProvider implements SecurityContextProvider {
    @Override public Optional<String> getCurrentUserLogin() { ... }
}
```

`OrgsecAutoConfiguration` then skips its `SpringSecurityContextProvider` bean.

### 2. Custom `SecurityAuditLogger`

```java
@Component
@Primary
public class SiemAuditLogger implements SecurityAuditLogger { ... }
```

The `@Primary` is needed because the Redis module also declares a `SecurityAuditLogger` bean under `@ConditionalOnMissingBean`. The Redis-side implementation depends on `orgsec.storage.redis.audit.enabled`: it returns `NoOpSecurityAuditLogger` when disabled (the default) and `DefaultSecurityAuditLogger` when enabled. In either case, marking your bean as `@Primary` ensures it wins regardless of bean-creation order. Without the Redis module on the classpath, no `SecurityAuditLogger` bean is auto-wired by OrgSec at all - supply your own (with or without `@Primary`).

### 3. Custom `SecurityDataStorage` backend

Override mechanics differ across the three built-in `@Primary` backend declarations. Only one of them is bean-name override-friendly:

| Built-in `@Primary` bean       | Has `@ConditionalOnMissingBean(name = ...)`? | Recommended override                                                                 |
| ------------------------------ | -------------------------------------------- | ------------------------------------------------------------------------------------ |
| JWT `jwtSecurityDataStorage`   | Yes                                          | Reuse the bean name with `@Primary` - OrgSec skips its default cleanly.         |
| Redis `redisSecurityDataStorage` | No                                         | Exclude the Redis auto-config or leave Redis disabled (see Option A / B below).       |
| In-memory `primaryInMemoryStorage` | No                                       | Set `orgsec.storage.features.jwt-enabled=true` or `redis-enabled=true` so the OrgSec bean's `@ConditionalOnProperty` skips it; alternatively, exclude `StorageConfiguration` from auto-import. |

**Pattern for JWT (the only `@ConditionalOnMissingBean(name = ...)` case):**

```java
@Configuration
public class MyBackendConfiguration {

    @Bean(name = "jwtSecurityDataStorage")
    @Primary
    public SecurityDataStorage myBackend(...) { return new MyBackend(...); }
}
```

**Pattern for Redis (no `@ConditionalOnMissingBean`):**

```java
// Option A: exclude the Redis auto-config and wire your own backend by hand.
@SpringBootApplication(exclude = RedisStorageAutoConfiguration.class)
public class Application { ... }

@Configuration
class MyRedisReplacementConfig {
    @Bean
    @Primary
    public SecurityDataStorage myCustomRedisLikeBackend(...) { ... }
}
```

```java
// Option B: leave Redis disabled (orgsec.storage.redis.enabled = false) and run your custom
// backend as the @Primary SecurityDataStorage. The Redis auto-config never activates.
```

**Pattern for in-memory (no `@ConditionalOnMissingBean`):**

The in-memory `primaryInMemoryStorage` bean is gated by `@ConditionalOnProperty` - it activates only when both `orgsec.storage.features.jwt-enabled=false` and `orgsec.storage.features.redis-enabled=false`. To replace it, either set one of those flags so the OrgSec bean steps aside, or exclude `StorageConfiguration` and wire the in-memory beans yourself. Reusing the bean name `primaryInMemoryStorage` directly is **not** safe - the OrgSec bean has no missing-bean guard, so you would hit a `BeanDefinitionOverrideException` or a silent override depending on your Spring Boot configuration.

In short: bean-name reuse is the right pattern only for JWT; for Redis and in-memory, prefer auto-config exclusion or feature-flag-driven deactivation.

### 4. Replace the Person API filter chain

```java
@Bean(name = "orgsecApiSecurityFilterChain")
@Order(SecurityProperties.BASIC_AUTH_ORDER - 50)
public SecurityFilterChain customOrgsecApiSecurityFilterChain(HttpSecurity http) throws Exception {
    return http
        .securityMatcher("/api/orgsec/person/**")
        // ... your auth rules, e.g., API-key validation instead of hasRole
        .build();
}
```

The bean's name (`orgsecApiSecurityFilterChain`) is what `PersonApiServiceConfiguration`'s `@ConditionalOnMissingBean(name = "orgsecApiSecurityFilterChain")` checks for; supplying it skips the default chain.

## Boot order summary

For a starter + Redis + JWT deployment with all flags enabled, OrgSec's five auto-configurations and the relevant Spring Boot core ones execute in roughly this order. Spring Boot orders auto-configurations by their `@AutoConfigureBefore` / `@AutoConfigureAfter` constraints; classes with no explicit ordering run whenever their conditions are satisfied.

1. `JacksonAutoConfiguration` (Spring Boot core).
2. `RedisStorageAutoConfiguration` (gated `after = JacksonAutoConfiguration.class`).
3. `JpaRepositoriesAutoConfiguration` (Spring Boot core; relevant when applications expose `SecurityQueryProvider` through JPA).
4. `OrgsecAutoConfiguration` (gated `after = JpaRepositoriesAutoConfiguration.class`, `before = SecurityAutoConfiguration.class`).
5. `StorageConfiguration` (no explicit ordering; Spring runs it whenever its conditions are satisfied. The `AllPersonsStore` / `AllOrganizationsStore` / loader component beans this configuration's `primaryInMemoryStorage` ultimately depends on come from the application-side component scan described above, **not** from `StorageConfiguration` itself).
6. `JwtStorageAutoConfiguration` (no explicit ordering; runs whenever its conditions are satisfied).
7. `PersonApiServiceConfiguration` (no explicit class-level ordering; the filter-chain bean it declares is ordered through `@Order(SecurityProperties.BASIC_AUTH_ORDER - 50)`).
8. `SecurityAutoConfiguration` (Spring Boot core, gated to run after OrgSec).

If `JwtDecoder` is missing under `jwt-enabled: true`, step 6 throws and the application context fails to refresh. If Redis is unreachable, step 2 still creates beans (the connection is lazy) and the failure surfaces on the first call - the circuit breaker handles the rest.

## Where to go next

- [Architecture / Overview](./overview.md) - module layering and bean graph.
- [Architecture / Cache architecture](./cache-architecture.md) - what the Redis beans actually do.
- [Architecture / Privilege evaluation](./privilege-evaluation.md) - what `PrivilegeChecker` does at request time.
- [Configuration](../guide/04-configuration.md) - the YAML side.
