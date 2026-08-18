# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-08-18

This is a minor release on the Spring Boot 3.5 / Java 17 line. It is source- and binary-compatible
with 1.0.5 for the japicmp-guarded public types. It is **not** drop-in for Redis deployments or
for handwritten RSQL that assumed `=*`.

### Security

- **Redis `<= 1.0.5` can serve a stale authorization view.** The previous L1/L2 + Pub/Sub path
  was a cache, not a generation-checked snapshot. A revoke that missed a notify, or a peer that
  rebuilt L1 from an older L2 value, could keep a grant that the source database no longer has.
  1.1.0 replaces managed GET/LIST with a lease-fenced immutable snapshot of all six families.
  Upgrade every Redis instance together; mixed 1.0.x / 1.1.0 Redis processes are unsupported.
- **List filters now use case-sensitive `=^*`.** On 1.0.x, `HIERARCHY_DOWN` emitted `=*`, which
  most JPA/RSQL stacks compile to `lower(col) LIKE ...` while `PrivilegeChecker` compares paths
  case-sensitively. Path segments are usually numeric, so the two agreed in practice; if your
  paths contain letters, a list endpoint could return a row that a direct GET would deny.

### Added

- **Managed Redis snapshot protocol.** One standalone Redis primary, one lease-elected writer,
  a full copy-on-write snapshot, and a local view that GET/LIST re-check against the current
  READY generation. Pub/Sub remains a hint only. Cluster, Sentinel, replicas, WAIT and capacity
  ledgers are out of scope.
- **`SecurityEventPublisher` after-commit notify.** Producer methods (`partyRoleChanged`,
  `personChanged`, ...) apply storage notify (and the Kafka publish attempt) once after the
  surrounding transaction commits. Rollback applies nothing. With no transaction the notify
  runs immediately. `apply*` stays the consumer/internal path and is never deferred. A failure
  after commit is `SecurityNotifyAfterCommitException` and does not hide that the source change
  already landed.
- **Optional ID-based `HIERARCHY_UP`.** `orgsec.hierarchy-up.strategy` is `PATH` (default, unchanged)
  or `IDS`. `IDS` uses inclusive `orgLineageIds` / `companyLineageIds` built from party `parentId`
  at load, on both GET and LIST. Missing lineage denies. This is a different source of truth than
  the denormalized path on the record, not a faster encoding of `PATH`.

### Changed

- Person API successful responses now include payload `"version": "1.0"`. `404` returns `{"code":"PERSON_NOT_FOUND"}`; `401`/`403` on the Person chain return `CALLBACK_UNAUTHENTICATED` / `CALLBACK_FORBIDDEN`.
- **`RsqlFilterBuilder` emits `=^*` for `HIERARCHY_DOWN`** and folds identical or subsumed
  subtree clauses on the same selector. `EXACT` and `HIERARCHY_UP` (`=in=` of ancestor prefixes)
  are unchanged. The 1.x empty-`privilegesList` aggregate fallback remains; it is removed in 2.x.

### Migration Notes

- Change handwritten `ownerOrgPath=*'|...|*'` / `ownerCompanyPath=*'|...|*'` filters to `=^*`.
- Call `SecurityEventPublisher` producer methods from the service that mutates security data;
  do not call `storage.notify*` from inside an open transaction if a rollback is still possible.
- Redis: set `orgsec.storage.redis.enabled=true` and a stable `orgsec.storage.redis.security-dataset-id`.
  Provide `SecurityDatasetFenceStore` and `RedisSnapshotLoader`. `update*` on a managed Redis
  storage is rejected.

## [1.0.5] - 2026-08-16

This is a patch release: it is source- and binary-compatible with 1.0.4. It is **not** drop-in.
Authorization changes in both directions - most entries narrow it, three widen it deliberately to
bring per-record checks back in line with list queries - and two storage configurations that started
on 1.0.4 are now **refused at startup**. Read the Migration Notes before upgrading; the configuration
section is the one that can stop a running deployment.

### Security

These entries describe defects that granted **more** access than was assigned, or let a token assert
facts nothing verified. All 1.0.x deployments before 1.0.5 are affected unless stated otherwise.

- **Paths that match everything were accepted as hierarchy anchors.** An empty path, a bare `"|"`, or
  a missing anchor passed the gate and then compared true against every record, because
  `startsWith` is unconditionally true against all three. `PrivilegeChecker` and `RsqlFilterBuilder`
  now refuse them and deny. A missing anchor previously raised a `NullPointerException` that escaped
  to the caller instead of denying the record.
- **Sibling branches matched as descendants.** A non-canonical anchor such as `|A|B` - one that does
  not end in the separator - made a prefix comparison match `|A|BX|C|`, a different branch whose
  first segment merely begins with the same characters. Both evaluators now validate that each path
  is canonical before comparing, so a prefix match means "is a descendant of" and nothing else.
- **JWT: the token decided its own hierarchy.** The claim carries only `pathId`, from which the
  parser derived `parentPath` - and derived the *strict* parent, one level above what every other
  backend stores. A principal at `|1|10|15|` was anchored at `|1|10|`, so `HIERARCHY_DOWN` granted
  every sibling subtree under organization 10; for a root-level membership the derived anchor
  collapsed to `"|"`, which matches everything. `companyParentPath` had no claim at all and stayed
  null. Anchors now come from the delegate storage, which is the authority for the hierarchy.
- **JWT: token principals received the organization's own roles.** Enrichment copied the delegate
  organization's `organizationRolesSet` and `businessRolesMap` onto the principal. Those are the
  roles the organization confers on its *party members*, so every token-authenticated principal
  received the union of everyone's privileges in that organization. Nothing role-shaped is copied
  any more; a principal's privileges come only from the position roles its own claim names.
- **JWT: unverified memberships granted access.** A membership naming an organization the delegate
  does not know, or naming a different company than the delegate records, was still evaluated. Such
  memberships are now removed from the principal entirely. Clearing only their anchors was not
  enough - an id-only membership still satisfies an `EXACT` privilege.
- **JWT: a duplicate `organizationId` silently overwrote a membership.** Memberships are keyed by
  organization id, so a second entry replaced the first and claim ordering decided which survived -
  while the two can disagree on exactly the fields authorization reads. A duplicate now rejects the
  whole claim.
- **JWT: a malformed `pathId` was accepted or crashed the request.** Values that are neither a valid
  local segment nor a valid full path now reject the whole claim, fail-closed, and
  `parsePersonFromToken` returns `null` rather than letting an exception reach the caller as HTTP
  500.
- **JWT: a stale cached principal outlived the change it was told about.** Fixed in this release
  together with the cache TTL - see *Fixed*.
- **The Person API was authorized but not authenticated.** `orgsecApiSecurityFilterChain` applied
  `hasRole(...)` without installing any authentication mechanism, so whether `/api/orgsec/person/**`
  was protected at all depended entirely on what the surrounding application happened to configure.
  The chain now authenticates the caller with a bearer token, validated by the application's own
  `JwtDecoder`, and refuses to start if the Person API is enabled without one.

**No GitHub Security Advisory is published for these entries, or for the 1.0.4 ones in the section
below.** They were found by the maintainers during an internal review rather than reported from
outside, and are documented here instead. That means a GHSA feed or a dependency scanner will not
flag 1.0.4 and earlier: treat this section as the notice, and upgrade. If you need an advisory record
for your own compliance process, open an issue and it will be filed.

### Changed

- **Storage activation is validated before the context is built, and every failure is fatal.**
  `OrgsecStorageActivationValidator`, an `EnvironmentPostProcessor`, reads the raw `Environment` and
  refuses to start on any of these, each with a stable diagnostic code:

  | Code | Condition |
  | --- | --- |
  | `ORGSEC_STORAGE_REDIS_ACTIVATION_MISMATCH` | `orgsec.storage.redis.enabled` disagrees with `orgsec.storage.features.redis-enabled` |
  | `ORGSEC_STORAGE_REDIS_MODULE_REQUIRED` | Redis enabled without `orgsec-storage-redis` on the classpath |
  | `ORGSEC_STORAGE_JWT_MODULE_REQUIRED` | JWT enabled without `orgsec-storage-jwt` on the classpath |
  | `ORGSEC_STORAGE_JWT_REDIS_UNSUPPORTED` | JWT and Redis enabled together |

  Only `orgsec.storage.redis.enabled` activates the Redis backend; `features.redis-enabled` decides
  whether the in-memory storage keeps `@Primary`. Because a disagreement leaves either two competing
  primaries or none, it is refused rather than warned about - a warning would leave a different
  storage serving authorization than the operator selected. **See the Migration Notes: this is the
  one change that can stop an existing application from starting.**
- **JWT and Redis can no longer be enabled together.** The combination needs a frozen contract for
  what the JWT backend delegates to, and a cache is not a safe delegate: it returns `null` for
  anything it does not hold, and the JWT backend reads a missing organization as an unproven
  membership and drops it - so a cold cache denies every request rather than merely slowing it down.
  Rather than ship that as a footgun, the combination is rejected at startup. Enable exactly one of
  the two.
- **Widened: `EXACT` no longer requires a path.** The gate demanded both an id and a usable path for
  every direction. `EXACT` compares ids and never reads a path, so a record with no path was denied
  by a per-record check while the list query returned it. Records whose path is absent or unusable
  are now granted when the ids match.
- **Widened: hierarchy directions no longer require the record's id.** For the same reason, in the
  other direction: a hierarchy comparison reads paths and never the record's id.
- **The JWT delegate is configured separately.** `JwtSecurityDataStorage` now injects
  `jwtDelegateStorage`, which defaults to the in-memory storage. An application that needs a
  different authoritative store behind JWT declares that bean itself; see
  [Hybrid storage](docs/storage/05-hybrid.md).
- **Bean-name overrides now work.** `primaryInMemoryStorage` and `delegateSecurityDataStorage` carry
  `@ConditionalOnMissingBean(name = ...)`, so an application-declared bean of either name replaces
  the library's. It previously collided, raising `BeanDefinitionOverrideException` or silently
  overriding depending on Spring Boot configuration. This is a new capability, not a fix.
- **The reactor compiles with `--release 17` instead of `-source`/`-target`.** The old pair emitted
  Java 17 bytecode but linked against whichever JDK ran the build, so a release built on a newer JDK
  could reference methods absent from Java 17 and fail only in a consumer's application at runtime.
  `--release` makes javac refuse them at compile time. Verified: the reactor compiles clean under the
  new setting, so no such reference existed.
- **The starter declares `spring-boot-starter-oauth2-resource-server` as `optional`.** Applications
  that leave `orgsec.api.person.enabled` at its default of `false` are unaffected.
- **Widened: an entity id no longer has to be a `Long`.** The reflective extraction cast straight to
  `Long`, so an entity exposing an `Integer` id - which both JPA and MapStruct produce for an `int`
  column - raised a `ClassCastException` that was swallowed into `null`, and the record was denied.
  `Integer`, `Short`, `Byte` and an in-range `BigInteger` are now accepted. Decimal types and a
  `BigInteger` outside `long` range are still refused rather than rounded.
- **`ResourceDef.setPrivilegesList` copies the argument** instead of storing it by reference, and
  `null` clears the list rather than installing one. Both evaluators iterate this list, so an aliased
  collection let a caller change what a shared `ResourceDef` grants, and a `null` raised a
  `NullPointerException` on the authorization path.
- **`JwtClaimsParser.getPositionRoleIds` is deprecated** and removed in 2.0.0. Use
  `parsePrincipalFromToken`, which returns the memberships and their role ids as one value.
- **Placeholder storage beans are no longer registered.** `StorageConfiguration` used to register
  beans named `jwtSecurityDataStorage` and `redisSecurityDataStorage` - the same names the real
  modules use - whenever a feature flag was set without the corresponding module. The types remain,
  deprecated and inert, and are removed in 2.0.0.
- **Documented as inert:** `orgsec.storage.primary`, `fallback`, `hybrid-mode-enabled`,
  `memory-enabled` and everything under `data-sources.*` bind but are read by nothing. There is no
  per-data-type router on this line. The documentation previously described them as working.
- On the 1.0.x line the list filter still uses RSQL `=*`, which most JPA/RSQL stacks translate to a
  case-insensitive `LIKE`, while `PrivilegeChecker` compares paths case-sensitively. 2.0.0 uses the
  case-sensitive `=^*`.

### Fixed

- **The Person API returned HTTP 500 for every request.** The published jars were compiled without
  `-parameters`, so `@PathVariable` had no parameter name to bind to and Spring MVC refused the
  request - in any application, regardless of its own compiler settings, because the controller ships
  inside the starter jar. The reactor now compiles with `-parameters` and the path variables are
  named explicitly. The Keycloak mapper could not have worked against a released artifact before
  this.
- **JWT principal cache honours its settings and is invalidated on change.**
  `orgsec.storage.jwt.cache-parsed-person` and `cache-ttl-seconds` were read from configuration and
  then ignored, so an enriched principal lived as long as the process. The cache is now cleared by
  every `notifyXxxChanged` that can alter what enrichment copied, and `cache-ttl-seconds` bounds the
  changes the library is never told about.
- **`OrgsecInMemoryFixtures` built organizations with `pathId` and `parentPath` inverted**, so
  fixture-based tests exercised a graph the real loaders never produce.
- A `ClassCastException` from an entity's `getSecurityField` - a non-`String` value for a path field -
  now denies rather than failing the request with HTTP 500.
- `RsqlFilterBuilder` no longer emits `selector==null` when the principal has no company or
  organization id; depending on the RSQL dialect that was either a parse error or a clause matching
  every row whose column is null.
- **The Redis backend could not start without a Bean Validation provider.**
  `RedisStorageProperties` was `@Validated` and the module shipped `jakarta.validation-api` without a
  provider. Spring Boot reads the API's presence as "JSR-303 is available" and bootstraps a validator
  while binding, so any application that set `orgsec.storage.redis.enabled=true` without its own
  validation provider failed to start with `NoProviderFoundException`. The constraints are now
  checked in code, reporting `ORGSEC_STORAGE_REDIS_INVALID_PROPERTY` with the offending property
  name, and the `jakarta.validation-api` dependency is gone. No Redis test caught this because none
  of them built a Spring context.
- The in-memory backend's `notifyPartyRoleChanged`, `notifyPositionRoleChanged` and
  `notifyOrganizationChanged` no longer run a targeted single-entity sync before the full reload that
  immediately clears it. Each notification issued two database queries whose results were discarded.
  The reload was, and remains, what actually makes the change visible - authorization reads the
  `OrganizationDef` copies carried on each person, which only a full load rebuilds.

### Migration Notes

**Narrowed - review before upgrading:**

- A principal whose `parentPath` or `companyParentPath` is empty, `"|"`, or absent no longer matches
  every record on that axis. If your application stores an absent path as `""`, hierarchy privileges
  for those principals now deny. Populate the anchors, or use `EXACT`.
- Organizational paths must be canonical (`|seg|seg|`, alphanumeric or underscore segments, at most
  30 characters each and 20 deep). A path missing its trailing separator is refused rather than
  compared.
- In JWT mode: memberships the delegate cannot confirm are dropped, a duplicate `organizationId`
  rejects the whole claim, and only the position roles the claim names grant anything. **Person-party
  grants are not available in JWT mode** - a deployment relying on them needs the in-memory or Redis
  backend as primary.
- `pathId` in the `orgsec` claim should be the organization's own segment (`"22"`). A full path
  (`"|1|10|22|"`) is still accepted and normalized, but anything else rejects the claim.
- The Person API now requires a `JwtDecoder` bean when enabled, and refuses to start without one.
  Callers must present a bearer token the application's decoder accepts, carrying the realm role
  named by `orgsec.api.person.required-role`.

**Widened - verify this is what you want:**

- A per-record `EXACT` check now grants when the ids match and the record's path is absent or
  unusable. Previously it denied, while a list query over the same rows returned them.
- A per-record hierarchy check now grants on a matching path even when the record carries no
  organization or company id.
- Records whose entity exposes a non-`Long` integral id are now evaluated instead of being denied.
  If your application relied on that denial - for example because a DTO exposes an unrelated
  `getId()` - the records it was hiding become visible.

**Configuration - this can stop an existing application from starting:**

- **Set `orgsec.storage.redis.enabled` and `orgsec.storage.features.redis-enabled` to the same
  value before upgrading.** Applications generated against 1.0.4 emit `redis.enabled: true` together
  with `features.redis-enabled: false`; on 1.0.5 that combination is refused at startup with
  `ORGSEC_STORAGE_REDIS_ACTIVATION_MISMATCH`. It was never a working configuration - it left the
  in-memory storage claiming `@Primary` while the Redis beans were created - so the upgrade turns a
  silent misconfiguration into a visible one. Check every deployment's effective configuration,
  including profile overrides and environment variables, before rolling out.
- **Applications running JWT and Redis together do not start on 1.0.5**
  (`ORGSEC_STORAGE_JWT_REDIS_UNSUPPORTED`). Enable exactly one. If you were relying on the Redis
  cache being present in a JWT deployment, note that the JWT backend never read through it for
  `Person` and would have denied on every cache miss for organizations.
- Enabling a backend without its module on the classpath is refused rather than silently falling
  back (`ORGSEC_STORAGE_JWT_MODULE_REQUIRED`, `ORGSEC_STORAGE_REDIS_MODULE_REQUIRED`).
- If you relied on `orgsec.storage.primary`, `hybrid-mode-enabled` or `data-sources.*` to select a
  backend, they never did anything. Use `orgsec.storage.redis.enabled` and
  `orgsec.storage.features.jwt-enabled`.

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
