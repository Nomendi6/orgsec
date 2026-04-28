# Public API Reference

This page is the index of OrgSec's public Java API - the types you can depend on, grouped by purpose. The truth is in the source code and the auto-generated Javadoc; this page exists so you can see the *shape* of the surface in one place.

The numbers here cover **OrgSec 1.0.x**. Internal helpers and implementation classes that are not in the tables below should be considered unstable, even if they are technically `public`.

## Fixture API

`com.nomendi6.orgsec.storage.inmemory.fixtures.OrgsecInMemoryFixtures` is a programmatic helper for tests, examples, and demos using the in-memory backend. It can register privileges, companies, organizations, roles, business role tags, persons, memberships, and then atomically apply the fixture to in-memory storage.

Production data should normally flow through `SecurityQueryProvider` or another storage adapter.

## Generating Javadoc

```bash
mvn javadoc:javadoc
# Per-module HTML output at <module>/target/site/apidocs/index.html
```

For a multi-module aggregate, run from the parent:

```bash
mvn javadoc:aggregate
# Aggregated HTML at target/site/apidocs/index.html
```

There is no published Javadoc URL for 1.0.x as of this writing; build it locally or consult the source on GitHub.

## Stability annotations

OrgSec does not yet ship `@Stable` / `@Internal` annotations. The intended public surface is defined by **package** and **placement in this document**:

| Package                                                  | Stability  | Notes                                                        |
| -------------------------------------------------------- | ---------- | ------------------------------------------------------------ |
| `com.nomendi6.orgsec.api`                                | Public     | SPIs the application implements (`PrivilegeDefinitionProvider`, `PrivilegeRegistry`). |
| `com.nomendi6.orgsec.interfaces`                         | Public     | `SecurityEnabledEntity` and `SecurityEnabledDTO` interfaces. The source files for both live under `orgsec-core/src/main/java/com/nomendi6/orgsec/api/` on disk, but the declared Java package is `com.nomendi6.orgsec.interfaces`. Always use the declared package name in imports. |
| `com.nomendi6.orgsec.provider`                           | Public     | Application-supplied data providers (`PersonDataProvider`, `UserDataProvider`, `SecurityQueryProvider`, `SecurityContextProvider`). |
| `com.nomendi6.orgsec.constants`                          | Public     | Enums used in the API surface.                               |
| `com.nomendi6.orgsec.model`                              | Public     | Domain models flowing through the API (`PersonDef`, `OrganizationDef`, `RoleDef`, `PrivilegeDef`, `BusinessRoleDef`, `ResourceDef`). |
| `com.nomendi6.orgsec.dto`                                | Public     | Lightweight data carriers (`PersonData`, `OrganizationData`, `UserData`). |
| `com.nomendi6.orgsec.audit`                              | Public     | `SecurityAuditLogger` SPI and event records.                 |
| `com.nomendi6.orgsec.exceptions`                         | Public     | OrgSec exception types.                                      |
| `com.nomendi6.orgsec.storage`                            | Public     | `SecurityDataStorage` SPI and `StorageSnapshot`.             |
| `com.nomendi6.orgsec.common.store`                       | Public     | `SecurityDataStore` facade.                                  |
| `com.nomendi6.orgsec.common.service`                     | Public     | `PrivilegeChecker`, `RsqlFilterBuilder`, `BusinessRoleConfiguration`. |
| `com.nomendi6.orgsec.helper`                             | Mixed      | `PathSanitizer` is public; other helpers may be internal.    |
| `com.nomendi6.orgsec.storage.inmemory.*`                 | Implementation | Use through configuration; depending on classes here couples you to the in-memory backend. |
| `com.nomendi6.orgsec.storage.redis.*`                    | Implementation | Same as above for Redis.                                     |
| `com.nomendi6.orgsec.storage.jwt.*`                      | Implementation | Same as above for JWT.                                       |
| `com.nomendi6.orgsec.autoconfigure`                      | Implementation | Spring Boot auto-config; consume through configuration, not direct calls. |

When OrgSec adds explicit stability annotations, this matrix will narrow accordingly.

## Application-implemented SPIs

These are the interfaces *you* implement. OrgSec calls into them.

| Type                         | Package                              | Purpose                                                                            |
| ---------------------------- | ------------------------------------ | ---------------------------------------------------------------------------------- |
| `SecurityEnabledEntity`      | `com.nomendi6.orgsec.interfaces`     | Mark a domain entity so OrgSec can read its `COMPANY` / `ORG` / `PERSON` fields per business role. |
| `SecurityEnabledDTO`         | `com.nomendi6.orgsec.interfaces`     | Same contract for DTOs that travel over HTTP.                                       |
| `PrivilegeDefinitionProvider`| `com.nomendi6.orgsec.api`            | Register privileges at startup.                                                     |
| `SecurityQueryProvider`      | `com.nomendi6.orgsec.provider`       | Translate your database into JPA `Tuple` rows that OrgSec's loaders consume.        |
| `PersonDataProvider`         | `com.nomendi6.orgsec.provider`       | Map `personId` / login / `userId` -> `PersonData`.                              |
| `UserDataProvider`           | `com.nomendi6.orgsec.provider`       | Map login -> `UserData`.                                                        |
| `SecurityContextProvider`    | `com.nomendi6.orgsec.provider`       | Resolve the current user. Default: `SpringSecurityContextProvider`.                 |
| `SecurityAuditLogger`        | `com.nomendi6.orgsec.audit`          | Receive privilege-check / cache-access / config-change / security events. Provided by Redis auto-config (when active): `NoOpSecurityAuditLogger` when `orgsec.storage.redis.audit.enabled: false` (default), `DefaultSecurityAuditLogger` when enabled. Without the Redis module, no `SecurityAuditLogger` bean is wired automatically - supply your own. |

## OrgSec-implemented SPIs

These are the interfaces *OrgSec* implements. You call them.

| Type                         | Package                              | Purpose                                                                            |
| ---------------------------- | ------------------------------------ | ---------------------------------------------------------------------------------- |
| `PrivilegeRegistry`          | `com.nomendi6.orgsec.api`            | Programmatic registration of `PrivilegeDef` instances.                              |
| `SecurityDataStorage`        | `com.nomendi6.orgsec.storage`        | Per-backend storage SPI (`getPerson`, `getOrganization`, `notifyXxxChanged`, `updateXxx`, `initialize`, `refresh`). |
| `SecurityDataStore`          | `com.nomendi6.orgsec.common.store`   | Application-facing facade in front of `SecurityDataStorage`. Use in services.       |
| `PrivilegeChecker`           | `com.nomendi6.orgsec.common.service` | The privilege evaluator (`getResourcePrivileges`, `hasRequiredOperation`).          |
| `RsqlFilterBuilder`          | `com.nomendi6.orgsec.common.service` | Generate RSQL filters from the caller's privileges.                                 |
| `BusinessRoleConfiguration`  | `com.nomendi6.orgsec.common.service` | YAML-bound business-role definitions.                                               |
| `StorageFeatureFlags`        | `com.nomendi6.orgsec.storage.inmemory` | Storage-routing flags; mutable at runtime.                                          |

## Domain models

The `model` package carries the OrgSec domain. These are the types flowing through the API; treat them as immutable-by-convention even though their fields are not `final`.

| Type                  | Purpose                                                                                            |
| --------------------- | -------------------------------------------------------------------------------------------------- |
| `PersonDef`           | The authenticated user, with `organizationsMap`.                                                    |
| `OrganizationDef`     | One node in the organizational tree, with `pathId`, `parentPath`, `companyParentPath`, `businessRolesMap`. |
| `RoleDef`             | A position-role definition, with privilege identifiers and tagged business roles.                  |
| `BusinessRoleDef`     | The aggregated view of what a person can do as a given business role inside a given organization.   |
| `ResourceDef`         | A logical resource type (`Document`, `Invoice`), with aggregated read/write/execute privileges.    |
| `PrivilegeDef`        | The unit of an authorization grant.                                                                 |
| `BusinessRoleContext` | A computed view used by `PrivilegeChecker.extractSecurityContext`.                                  |
| `BusinessRoleDefinition` | YAML-bound `(roleName, supportedFields)` pair.                                                  |

## Lightweight DTOs

The `dto` package carries lighter-weight types used at the API boundary. They do not extend `*Def` types - they are flat JavaBeans.

| Type               | Purpose                                                                                                        |
| ------------------ | -------------------------------------------------------------------------------------------------------------- |
| `PersonData`       | `id`, `name`, `relatedUserId`, `relatedUserLogin`, `defaultCompanyId`, `defaultOrgunitId`, `status`. Has a `Builder`. |
| `OrganizationData` | Smaller representation of an organization for caller-facing flows.                                              |
| `UserData`         | The authenticated user (login, principal-style fields).                                                         |

`PersonData` has `getId()` / `getName()` / etc. - it is a JavaBean, not a record. Method references like `PersonData::getId` work; `PersonData::id` does not.

## Constants and enums

| Enum                    | Values                                                                          |
| ----------------------- | ------------------------------------------------------------------------------- |
| `PrivilegeOperation`    | `NONE`, `READ`, `WRITE`, `EXECUTE`                                              |
| `PrivilegeDirection`    | `NONE`, `EXACT`, `HIERARCHY_DOWN`, `HIERARCHY_UP`, `ALL`                        |
| `PrivilegeScope`        | `ALL`, `COMP`, `COMPHD`, `COMPHU`, `ORG`, `ORGHD`, `ORGHU`, `EMP`               |
| `SecurityFieldType`     | `COMPANY`, `ORG`, `PERSON`, `COMPANY_PATH`, `ORG_PATH`                          |

`SecurityConstants` and `Event` (in the same `constants` package) are *classes* with `public static final String` constants - not enums. `Event` defines event-name strings (`PARTY_ROLE_CHANGED`, `PERSON_CHANGED`, `ORGANIZATION_CHANGED`, `PREPARE_SERVICE_ACCOUNT`, ...) used for cross-service notifications and audit. `SecurityConstants` holds general string constants used across the library.

For the truth tables that govern these enums, see [Privilege Model Reference](./privilege-model.md).

## Audit API

| Type                                     | Purpose                                                              |
| ---------------------------------------- | -------------------------------------------------------------------- |
| `SecurityAuditLogger`                    | Interface with four `log*` methods plus `isEnabled`.                 |
| `SecurityAuditLogger.PrivilegeCheckEvent`| `record(personId, privilegeName, resourceName, granted, reason, durationMs)` |
| `SecurityAuditLogger.CacheAccessEvent`   | `record(cacheType, operation, key, hit, cacheLevel, durationMs)`     |
| `SecurityAuditLogger.ConfigurationChangeEvent` | `record(configKey, oldValue, newValue, changedBy)`             |
| `SecurityAuditLogger.SecurityEvent`      | `record(level, category, message, details)` with `SecurityEventLevel` enum |
| `DefaultSecurityAuditLogger`             | SLF4J + MDC implementation of `SecurityAuditLogger`. Wired by the Redis auto-config when `orgsec.storage.redis.audit.enabled: true`; otherwise the Redis auto-config wires `NoOpSecurityAuditLogger`. Without the Redis module, no `SecurityAuditLogger` bean is wired by OrgSec - supply your own if you want audit events. |

## Helpers and utilities

| Type             | Purpose                                                                                              |
| ---------------- | ---------------------------------------------------------------------------------------------------- |
| `PathSanitizer`  | Validate and escape pipe-delimited path strings (`|1|10|22|`). Throws `OrgsecSecurityException` on malformed input. |
| `StorageSnapshot`| Immutable snapshot of the four data maps (persons, orgs, party roles, position roles, privileges). Used for save/restore in integration tests. |

## Exceptions

| Exception                          | When                                                                          |
| ---------------------------------- | ----------------------------------------------------------------------------- |
| `OrgsecConfigurationException`     | Configuration validation fails at runtime.                                    |
| `OrgsecDataAccessException`        | Reserved for custom backends to wrap data-layer errors.                       |
| `OrgsecSecurityException`          | Authorization invariant violated (path sanitization, etc.). Carries `code`, `resource`, `operation`. |
| `org.springframework.security.access.AccessDeniedException` | Thrown by `RsqlFilterBuilder` on missing person / null path / no privileges. |
| `IllegalStateException`            | JWT decoder missing at startup; storage not ready for snapshot.               |
| `IllegalArgumentException`         | Malformed privilege identifier in `PrivilegeLoader.createPrivilegeDefinition`. |
| `UnsupportedOperationException`    | Optional `SecurityDataStorage` operation not supported by this backend.       |

For the full handling guidance see [Reference / Exceptions](./exceptions.md).

## Service-layer call patterns

Two API call patterns dominate application code. Both are described in narrative form in the First Working Example and usage guides; they are listed here as the canonical entry points.

### Programmatic single-entity check

```java
PersonDef person = securityDataStore.getPerson(callerId);
ResourceDef resource = /* aggregate from person + entity + resourceName */;
PrivilegeDef granted = privilegeChecker.getResourcePrivileges(resource, PrivilegeOperation.READ);
boolean ok = privilegeChecker.hasRequiredOperation(granted, PrivilegeOperation.READ);
```

### List-endpoint filtering

```java
String rsql = rsqlFilterBuilder.buildRsqlFilterForReadPrivileges(
    "Document", null, currentPersonData);
// pass `rsql` to your RSQL-aware Specification, parsed and applied as WHERE clause
```

## Where to go next

- [Core Concepts](../reference/concepts.md) - the narrative.
- [Privilege Model Reference](./privilege-model.md) - truth tables.
- [Reference / Properties](./properties.md) - configuration surface.
- [Reference / Exceptions](./exceptions.md) - failure modes.
