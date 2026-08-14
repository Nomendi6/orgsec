# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.4] - 2026-08-14

### Security

These entries describe defects that granted **more** access than was assigned. Deployments on
1.0.0 or 1.0.3 are affected. The cross-principal accumulation entry is the exception: in those
releases it was latent, and it is fixed here - in the same release that would otherwise have made
it reachable.

- **Cross-principal privilege leak in the JWT backend.** `JwtSecurityDataStorage.enrichPersonWithRoles`
  copied the delegate's business-role map by reference and then merged the request's position roles
  into the shared `BusinessRoleDef` objects. One principal's privileges were written into state that
  other principals of the same organization read. The delegate's objects are now deep-copied before
  the merge.
- **`allowedBusinessRoles` was accepted and ignored.** `RsqlFilterBuilder.buildRsqlFilterForPrivileges`
  took the parameter but never applied it, so a call restricted to one business role still evaluated
  every business role the principal held. A principal whose unrelated role carried a broader
  privilege received an unfiltered result - and `buildRsqlFilterForBasicPrivileges`, documented as
  "owner role only", was affected too. The parameter is now honoured: `null` means all roles, a
  non-empty list restricts to those roles, and an empty list denies.
- **Company-wide access from combined hierarchy privileges.** `PrivilegeDef.add` mapped
  `HIERARCHY_DOWN + HIERARCHY_UP` to `ALL`, and an aggregated `org` direction of `ALL` was then
  rewritten to `company = EXACT, org = NONE`. A role holding both a `*_ORGHD_*` and a `*_ORGHU_*`
  privilege for one resource therefore became company-scoped: on resources carrying a COMPANY field
  this granted access across the whole company. On resources without one it removed access entirely -
  the symptom that led to this report.
- **Cross-tree access in company `HIERARCHY_UP` checks.** `PrivilegeChecker.checkCompanyPrivilege`
  compared paths with `endsWith` where the org branch correctly used `startsWith`. It accepted an
  unrelated organization whose path merely ended with the principal's (`|X|A|B|C|` matched
  `|A|B|C|`), and rejected genuine ancestors.
- **Unfiltered list queries on an unsupported direction.** `RsqlFilterBuilder` returned an empty
  clause for a direction it did not handle. Callers read a non-null result as "privilege present"
  and an empty filter as "no filtering", so the query returned every row. It now denies.
- **Cross-principal privilege accumulation (latent in 1.0.x).** `BusinessRoleDef.addResourceDefinition`
  stored the source role's privilege list by reference instead of copying it, then appended into it
  when a later role contributed the same resource - writing into the shared, store-owned `RoleDef`.
  Nothing read that list for an authorization decision before this release, so it was not reachable;
  it is fixed here because this release makes the list authoritative.

### Fixed

- `PrivilegeDef.add` now returns the wider direction when combining `EXACT` with a hierarchy
  direction, instead of narrowing to `EXACT`. `EXACT` is a subset of both hierarchy directions, so
  the union is the hierarchy direction.
- `RsqlFilterBuilder` now enumerates ancestor path prefixes as an `=in=` list for `HIERARCHY_UP` on
  both the org and the company dimension. The previous suffix `LIKE` matched only the principal's own
  path, so no ancestor record appeared in any list query. On the org dimension the per-record check
  does grant those ancestors, so an ancestor's record was reachable through a GET yet invisible in a
  list; on the company dimension the per-record check rejected them as well, so company ancestors
  were unreachable on both paths.
- Per-record and list authorization now evaluate each privilege in `ResourceDef.privilegesList` and
  OR the outcomes, instead of deciding from a single aggregated direction. A direction is one enum
  value and cannot express "subtree OR ancestors", so the aggregate lost information that the
  per-privilege evaluation preserves. The two paths also applied different aggregate prechecks and
  could return opposite answers for the same data; those prechecks are gone.
- `ResourceDef` and `BusinessRoleDef` gained no-arg constructors so that `RoleDef`,
  `OrganizationDef` and `PersonDef` can be deserialized from the Redis L2 cache. Reading any of them
  with populated collections previously failed with `InvalidDefinitionException`, making the L2 cache
  non-functional for three of the four cached types.
- `PrivilegeDirection.ALL` is documented as what it is: not a valid `company` or `org` scope.
  Unrestricted access is expressed by the separate `PrivilegeDef.all` flag. `includesDown()` and
  `includesUp()` on `PrivilegeDirection` and `PrivilegeScope` are deprecated for removal - they are
  unused and report `true` for `ALL`, which no evaluator treats as a grant.
- Documentation that presented `getResourcePrivileges` + `hasRequiredOperation` as the canonical
  single-entity check has been corrected. That pair tests the operation only and never looks at the
  entity, so following it authorized every record as soon as the caller held the privilege anywhere.

### Migration Notes

- This is a security patch. It is source-compatible with 1.0.3 and needs no code changes, but it is
  deliberately **not** behaviour-identical: access that depended on the defects listed under
  `Security` stops working - that is the intent of the fix.
- **This release grants more access than before in four situations,** each a correction of an
  over-narrow result rather than a new grant: `add(EXACT, HIERARCHY_DOWN)` and
  `add(EXACT, HIERARCHY_UP)` now return the hierarchy direction; `HIERARCHY_UP` list queries now
  return ancestor records; a role holding privileges on two different scope axes now has both
  evaluated; and a role holding both hierarchy directions now has both evaluated. Review roles that
  combine scopes before upgrading.
- Authorization is now decided from `ResourceDef.privilegesList` when that list is populated, which
  is the case on every path the library builds. Application code that constructs a `ResourceDef` by
  hand and sets only the aggregated privilege keeps working through a documented fallback, but the
  fallback logs a warning and cannot express a role holding two hierarchy directions. Populate the
  list; the fallback is removed in 2.x, where the empty-list case denies.
- **`HIERARCHY_UP` filters now emit the `=in=` operator,** which OrgSec has not emitted before.
  OrgSec builds the RSQL string but does not parse it; the consuming RSQL layer must support `=in=`.
  Note that `=in=` is exact and case-sensitive, unlike the `=*` LIKE operator used for
  `HIERARCHY_DOWN`, so organizational paths must match the stored column exactly.
- **Invalidate cached authorization data when upgrading.** Redis L2 entries and warm L1 caches
  written by an earlier version hold aggregates computed with the old algebra. Clear the OrgSec Redis
  keyspace, or let the TTL expire, before relying on the new behaviour.

### Credits

- The direction-algebra defects - `PrivilegeDef.add`, the company `HIERARCHY_UP` predicate, the
  `HIERARCHY_UP` list filter, the unsupported-direction branch and the per-privilege evaluation on
  both authorization paths - were reported by the NIK2 backend team, who found and fixed them in
  their own implementation derived from this library, and contributed the patch series upstream.
- The remaining entries above - the cross-principal privilege accumulation, the cross-principal leak
  in the JWT backend, the ignored `allowedBusinessRoles` parameter, the removal of the aggregate
  prechecks and the Redis deserialization defect - were found during upstream review of that series.

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
