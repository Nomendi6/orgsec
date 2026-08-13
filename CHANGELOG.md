# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - Unreleased

### Changed

- Upgraded the baseline to Java 21 and Spring Boot 4.0.6, bringing Spring Security 7 and Jakarta EE 11 managed dependencies through the Spring Boot BOM.
- Added a Java 25 supported build profile. The profile requires JDK 25 for the Maven process but keeps the compiled bytecode at `--release 21`.
- Migrated Jackson databind usage to Jackson 3 `tools.jackson.*` APIs while keeping Jackson annotations in the `com.fasterxml.jackson.annotation.*` namespace.
- Updated the Redis backend to the Spring Boot 4 compatible Resilience4j adapter and Testcontainers 2.x managed by the Spring Boot BOM.
- Updated the JWT backend to keep Spring Security's `JwtDecoder` as the validation surface and added contract coverage for the mapper-compatible `orgsec` claim shape.

### Migration Notes

- Applications must run on Java 21 or newer. Java 17 remains supported only on the OrgSec 1.0.x line.
- Applications must use Spring Boot 4.x / Spring Security 7.x with OrgSec 2.0.x. Spring Boot 3.5.x applications should remain on OrgSec 1.0.x.
- If an application uses OrgSec Redis or JWT internals directly, update imports from Jackson 2 `com.fasterxml.jackson.databind.*` to Jackson 3 `tools.jackson.databind.*`. Jackson *annotations* stay in the `com.fasterxml.jackson.annotation.*` namespace — Jackson 3 has no annotation package of its own and reads those directly.
- `RedisStorageHealthIndicator` now implements `org.springframework.boot.health.contributor.HealthIndicator` instead of `org.springframework.boot.actuate.health.HealthIndicator`, following the Spring Boot 4 actuator split. Applications that reference the type directly must update the import; applications that only rely on the `/actuator/health` endpoint are unaffected.
- The Keycloak custom mapper contract is unchanged: the JWT claim root is `orgsec`, with `version`, `person`, and `memberships`. Rebuild and smoke-test the mapper before releasing an application that depends on JWT storage.
- Apgen integration is external to this repository. Generate and test one application with `organizationalSecurity=true` against `2.0.0-SNAPSHOT` before cutting the final 2.0.0 release.

## [1.0.3] - 2026-04-28

### Fixed

- `OrganizationLoader` and `PersonLoader` no longer call `PathSanitizer.sanitizePath` on the `pathId`, `parentPath`, and `companyParentPath` columns read from `Tuple` query results. Version 1.0.1 added these calls and unintentionally tightened the SQL contract: applications whose schema stores `pathId` as a local segment (e.g. `'ow'`) and `parentPath` as the pipe-separated full path (e.g. `'|ow|'`) failed to start with `OrgsecSecurityException: Invalid path format ...`. The 1.0.0 contract is restored: tuple values are passed to `OrganizationDef` unchanged. Applications that need path validation can call `PathSanitizer` from their own `SecurityQueryProvider`.
- `AllPersonsStore.getPerson(null)`, `AllOrganizationsStore.getOrganization(null)`, `AllRolesStore.getOrganizationRole(null)`, `AllRolesStore.getPositionRole(null)`, and `AllPrivilegesStore.getPrivilege(null)` / `hasPrivilege(null)` now return `null` / `false` instead of throwing `NullPointerException`. In 1.0.1 the backing maps were migrated from `HashMap` to `ConcurrentHashMap` for thread-safety; `ConcurrentHashMap` rejects `null` keys, which silently broke the documented "or null if not found" contract for callers that probe with a `null` id.

## [1.0.1] - 2026-04-26

### Security

- Upgraded dependency baseline to Spring Boot 3.5.14 and commons-lang3 3.18.0.
- JWT storage now requires a configured `JwtDecoder` and validates tokens before reading OrgSec claims.
- Person API is disabled by default and, when enabled, requires `ROLE_ORGSEC_API_CLIENT` unless the consumer overrides the `orgsecApiSecurityFilterChain` bean.
- RSQL filter generation now fails closed when hierarchy paths are missing instead of treating null paths as unrestricted access.
- Redis privilege cache keys now use the full privilege identifier instead of a 32-bit Java hash.
- Removed the unused Redis `IntegrityHashCalculator`; it used an unkeyed SHA-256 hash and was not wired into cache read/write paths.
- Redis Pub/Sub invalidation is opt-in via `orgsec.storage.redis.invalidation.enabled=true` because Pub/Sub messages are trusted by all listeners on the channel.
- Redis health details and audit logs no longer expose exception details or unsanitized user-controlled values.

### Added

#### Core (`orgsec-core`)
- Core API interfaces: `SecurityDataStorage`, `SecurityEnabledEntity`, `SecurityEnabledDTO`, `PrivilegeRegistry`
- Domain models: `PersonDef`, `RoleDef`, `PrivilegeDef`, `OrganizationDef`, `ResourceDef`, `BusinessRoleDef`
- Privilege model with operations (READ/WRITE/EXECUTE), scopes (company/org/person), and hierarchy directions (EXACT, UP, DOWN)
- Provider interfaces: `SecurityContextProvider`, `SecurityQueryProvider`, `UserDataProvider`, `PersonDataProvider`, `PrivilegeDefinitionProvider`
- Audit logging interfaces: `SecurityAuditLogger` with default and no-op implementations
- Exception hierarchy: `OrgsecConfigurationException`, `OrgsecDataAccessException`, `OrgsecSecurityException`
- Helper utilities: `PrivilegeSecurityHelper`, `RsqlHelper`, `PathSanitizer`

#### Common (`orgsec-common`)
- `PrivilegeChecker` service for privilege validation with hierarchy support
- `BusinessRoleConfiguration` with YAML and provider-based role definitions
- `SecurityDataStore` as unified data store bridge
- `RsqlFilterBuilder` for security-aware RSQL query generation
- `SecurityEventPublisher` for event-driven cache invalidation

#### In-Memory Storage (`orgsec-storage-inmemory`)
- Thread-safe in-memory `SecurityDataStorage` implementation with `ReadWriteLock`
- Data loaders: `PersonLoader`, `OrganizationLoader`, `RoleLoader`, `PrivilegeLoader`
- Snapshot support for testing (save/restore state)
- Auto-initialization of privileges via `PrivilegeDefinitionProvider`
- `PrivilegeSecurityService` for privilege operations

#### Redis Storage (`orgsec-storage-redis`)
- 2-level cache architecture: L1 (in-memory LRU) + L2 (Redis distributed)
- Cache invalidation via Redis Pub/Sub
- Configurable cache warming strategies: eager (all at once) and progressive (batched)
- Circuit breaker integration with Resilience4j
- Batch operations for efficient bulk reads/writes
- Connection pooling with Lettuce and Commons Pool2
- Spring Boot health indicator for Redis storage monitoring
- Cache statistics tracking
- Configurable TTL per entity type

#### JWT Storage (`orgsec-storage-jwt`)
- Hybrid storage: person data from JWT claims, other data from delegate storage
- `JwtClaimsParser` for extracting OrgSec data from JWT tokens
- `JwtTokenFilter` for automatic token extraction from HTTP requests
- `JwtTokenContextHolder` for per-request token management
- Support for custom JWT claim names and structure

#### Spring Boot Starter (`orgsec-spring-boot-starter`)
- Spring Boot auto-configuration with `@AutoConfiguration`
- `SpringSecurityContextProvider` for Spring Security integration
- Configuration properties with `orgsec.*` prefix
- REST API (`PersonApiController`) for person data (Keycloak mapper support)
- Conditional configuration for optional features

### Technical Details
- Java 17 minimum requirement
- Spring Boot 3.5.14 compatibility
- Spring Security integration
- Maven multi-module project structure
- JaCoCo code coverage enforcement (85% line, 80% branch for Redis module)
