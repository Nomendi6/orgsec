# Quick Start

This guide takes you from an empty Maven project to a Spring Boot application with the OrgSec wiring fully in place. It is intentionally narrow: a single entity, two business roles, three privileges, and the in-memory storage backend. By the end you will know exactly which classes you have to provide for OrgSec to work, and where each one fits.

The example uses a *stub* `SecurityQueryProvider` that returns no rows - that is enough to verify the wiring (privileges register, beans wire, the privilege checker is callable), but no privilege check will return `true` until you replace the stub with real database queries. We mark that step explicitly at the end. If you want the same code as a single runnable project, jump to [`examples/in-memory-app.md`](../examples/in-memory-app.md).

## Prerequisites

- Java 17 or newer
- Maven 3.6 or newer
- Spring Boot 3.5.x (this guide uses 3.5.14, the version OrgSec 1.0.x ships against)
- A working understanding of Spring Boot auto-configuration

## 1. Add the dependency

OrgSec is published to Maven Central as `com.nomendi6.orgsec:orgsec-spring-boot-starter`. The starter pulls in `orgsec-core`, `orgsec-common`, and `orgsec-storage-inmemory`, which is everything you need for an in-memory deployment.

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

For Gradle:

```gradle
implementation 'com.nomendi6.orgsec:orgsec-spring-boot-starter:1.0.1'
```

## 2. Declare your business roles

OrgSec does not assume which business roles your domain uses. Declare them in `application.yml` and tell OrgSec which `SecurityFieldType` values each role expects on its entities. For this guide we use two roles - `owner` and `customer`.

```yaml
orgsec:
  storage:
    primary: memory                     # in-memory backend (default)
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
    customer:
      supported-fields: [COMPANY, COMPANY_PATH]
```

The `supported-fields` list controls which fields OrgSec asks for when it evaluates a privilege for a given role. The `owner` role is hierarchical (it has both `COMPANY_PATH` and `ORG_PATH`); the `customer` role is company-scoped only.

## 3. Mark your entity as security-enabled

The entity that the privilege check protects must implement `SecurityEnabledEntity`. The interface has two methods: `getSecurityField` and `setSecurityField`. The `businessRole` argument selects which set of fields to expose; the `fieldType` selects which one within that set.

```java
package com.example.docs;

import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.interfaces.SecurityEnabledEntity;

public class Document implements SecurityEnabledEntity {

    private Long id;
    private String title;

    private Long ownerCompanyId;
    private String ownerCompanyPath;
    private Long ownerOrgId;
    private String ownerOrgPath;
    private Long ownerPersonId;

    private Long customerCompanyId;
    private String customerCompanyPath;

    @Override
    public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
        if ("owner".equalsIgnoreCase(businessRole)) {
            return switch (fieldType) {
                case COMPANY -> ownerCompanyId;
                case COMPANY_PATH -> ownerCompanyPath;
                case ORG -> ownerOrgId;
                case ORG_PATH -> ownerOrgPath;
                case PERSON -> ownerPersonId;
            };
        }
        if ("customer".equalsIgnoreCase(businessRole)) {
            return switch (fieldType) {
                case COMPANY -> customerCompanyId;
                case COMPANY_PATH -> customerCompanyPath;
                default -> null;
            };
        }
        return null;
    }

    @Override
    public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
        if ("owner".equalsIgnoreCase(businessRole)) {
            switch (fieldType) {
                case COMPANY -> ownerCompanyId = (Long) value;
                case COMPANY_PATH -> ownerCompanyPath = (String) value;
                case ORG -> ownerOrgId = (Long) value;
                case ORG_PATH -> ownerOrgPath = (String) value;
                case PERSON -> ownerPersonId = (Long) value;
            }
        } else if ("customer".equalsIgnoreCase(businessRole)) {
            switch (fieldType) {
                case COMPANY -> customerCompanyId = (Long) value;
                case COMPANY_PATH -> customerCompanyPath = (String) value;
                default -> { /* no-op */ }
            }
        }
    }

    // ... id/title getters/setters omitted
}
```

The interface lives in `com.nomendi6.orgsec.interfaces` (the package name is intentional; do not confuse it with `com.nomendi6.orgsec.api`). For a deeper discussion of why entities expose security fields by role rather than as fixed columns, see [Core Concepts](./03-core-concepts.md#the-securityenabledentity-contract).

## 4. Register your privileges

OrgSec does not ship a closed enum of privileges. Your application defines them at startup by exposing a `PrivilegeDefinitionProvider` bean. Privilege identifiers are conventionally formatted as `RESOURCE_SCOPE_OPERATION` - for example `DOCUMENT_COMPHD_R` (read access on the document resource, scoped to the owner's company plus all descendants). The naming convention is parsed by `PrivilegeLoader.createPrivilegeDefinition`, so you can let it build the `PrivilegeDef` for you.

```java
package com.example.docs;

import com.nomendi6.orgsec.api.PrivilegeDefinitionProvider;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentPrivileges implements PrivilegeDefinitionProvider {

    private static final List<String> IDENTIFIERS = List.of(
        "DOCUMENT_COMPHD_R", // read documents in own company sub-tree
        "DOCUMENT_ORG_W",    // write documents owned by own org
        "DOCUMENT_ALL_R"     // read everything (super-user)
    );

    private final PrivilegeLoader loader;

    public DocumentPrivileges(PrivilegeLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    public void register() {
        loader.initializePrivileges(this);
    }

    @Override
    public Map<String, PrivilegeDef> getPrivilegeDefinitions() {
        Map<String, PrivilegeDef> defs = new HashMap<>();
        for (String id : IDENTIFIERS) {
            defs.put(id, createPrivilegeDefinition(id));
        }
        return defs;
    }

    @Override
    public PrivilegeDef createPrivilegeDefinition(String identifier) {
        return PrivilegeLoader.createPrivilegeDefinition(identifier);
    }
}
```

The naming suffixes are:

| Suffix    | Meaning                                                       |
| --------- | ------------------------------------------------------------- |
| `_ALL_R`  | Read everywhere (no scope check; super-user)                  |
| `_COMP_R` | Read on the exact company                                     |
| `_COMPHD_R` | Read on the company and all its descendants                 |
| `_COMPHU_R` | Read on the company and all its ancestors                   |
| `_ORG_R`  | Read on the exact organizational unit                         |
| `_ORGHD_R` | Read on the organizational unit and all its descendants      |
| `_ORGHU_R` | Read on the organizational unit and all its ancestors        |
| `_EMP_R`  | Read on entities owned by the person directly                 |

Replace the trailing `R` with `W` for write or `E` for execute. The full table is in [Privileges and Business Roles](./05-privileges-and-business-roles.md).

## 5. Provide a SecurityQueryProvider

OrgSec does not query your database directly - it asks your application for the data it needs through `SecurityQueryProvider`. The provider returns JPA `Tuple` rows for persons, organizations, roles, and role-to-privilege mappings. The in-memory backend calls these methods at startup (and again whenever you tell it to refresh) to load its caches.

For a Quick Start where you just want to see the wiring work, you can bind a stub provider that returns empty lists. The application will boot, but no person / organization data will be loaded - privilege checks will return `false` for every entity. That is enough to verify that the wiring is correct; once you see the bean graph come up cleanly, replace the stub with the real queries against your schema.

```java
package com.example.docs;

import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StubSecurityQueryProvider implements SecurityQueryProvider {

    // Override only the methods that touch your real schema once you have one.
    // Everything else returns empty so the in-memory storage initializes cleanly.

    @Override public List<Tuple> loadAllParties() { return List.of(); }
    @Override public List<Tuple> loadPartyById(Long partyId) { return List.of(); }
    @Override public List<Tuple> loadAllPartyAssignedRoles() { return List.of(); }
    @Override public List<Tuple> loadPartyAssignedRolesByPartyId(Long partyId) { return List.of(); }
    @Override public List<Tuple> loadPartiesRelatedToRole(Long partyRoleId) { return List.of(); }

    @Override public List<Tuple> loadAllPersons() { return List.of(); }
    @Override public List<Tuple> loadPersonById(Long personId) { return List.of(); }
    @Override public List<Tuple> loadAllPersonParties() { return List.of(); }
    @Override public List<Tuple> loadPersonPartiesByPersonId(Long personId) { return List.of(); }
    @Override public List<Tuple> loadAllPersonPartyRoles() { return List.of(); }
    @Override public List<Tuple> loadPersonPartyRolesByPersonId(Long personId) { return List.of(); }
    @Override public List<Tuple> loadAllPersonPositionRoles() { return List.of(); }
    @Override public List<Tuple> loadPersonPositionRolesByPersonId(Long personId) { return List.of(); }
    @Override public List<Tuple> loadPersonsRelatedToRole(Long partyRoleId) { return List.of(); }
    @Override public List<Tuple> loadPersonsRelatedToParty(Long partyId) { return List.of(); }
    @Override public List<Tuple> loadPersonsRelatedToPosition(Long positionId) { return List.of(); }

    @Override public List<Tuple> loadAllPartyRoles() { return List.of(); }
    @Override public List<Tuple> loadPartyRoleById(Long partyRoleId) { return List.of(); }
    @Override public List<Tuple> loadAllPartyRolePrivileges() { return List.of(); }
    @Override public List<Tuple> loadPartyRolePrivilegesByRoleId(Long roleId) { return List.of(); }
    @Override public List<Tuple> loadAllPositionRoles() { return List.of(); }
    @Override public List<Tuple> loadPositionRoleById(Long roleId) { return List.of(); }
    @Override public List<Tuple> loadAllPositionRolePrivileges() { return List.of(); }
    @Override public List<Tuple> loadPositionRolePrivilegesByRoleId(Long roleId) { return List.of(); }

    @Override public List<Tuple> loadAllPartyRolePrivilegesAsStrings() { return List.of(); }
    @Override public List<Tuple> loadPartyRolePrivilegesByRoleIdAsStrings(Long roleId) { return List.of(); }
    @Override public List<Tuple> loadAllPositionRolePrivilegesAsStrings() { return List.of(); }
    @Override public List<Tuple> loadPositionRolePrivilegesByRoleIdAsStrings(Long roleId) { return List.of(); }
}
```

In a real application the implementation queries your schema with JPA. The exact query shapes that the in-memory loader expects are documented in [`storage/02-in-memory.md`](../storage/02-in-memory.md). For seeding production data, see also [Cache Invalidation](../cookbook/04-cache-invalidation.md) (recipe section).

## 6. Make a privilege check

Once your application's component scan covers the OrgSec packages (see ["Component scan note" below](#component-scan-note)), Spring registers a `PrivilegeChecker` bean that you can inject anywhere. For programmatic checks you typically work with two methods:

- `getResourcePrivileges(ResourceDef, PrivilegeOperation)` - returns the aggregated `PrivilegeDef` your caller holds for a given resource and operation, or `null` if they hold none.
- `hasRequiredOperation(PrivilegeDef, PrivilegeOperation)` - checks whether that aggregated privilege is sufficient for the operation you want to perform.

Combine them in a service:

```java
package com.example.docs;

import com.nomendi6.orgsec.common.service.PrivilegeChecker;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;
import org.springframework.stereotype.Service;

@Service
public class DocumentAuthService {

    private final PrivilegeChecker checker;

    public DocumentAuthService(PrivilegeChecker checker) {
        this.checker = checker;
    }

    public boolean canRead(ResourceDef resourceForCaller) {
        PrivilegeDef granted = checker.getResourcePrivileges(resourceForCaller, PrivilegeOperation.READ);
        return checker.hasRequiredOperation(granted, PrivilegeOperation.READ);
    }
}
```

`ResourceDef` is built up from the caller's organizational membership and the entity being checked - the in-memory store and `PrivilegeChecker` cooperate to produce it. The full evaluation flow is described step by step in [Privilege Evaluation](../architecture/privilege-evaluation.md).

For richer integration patterns - controller-level checks with `@PreAuthorize`, list filtering with `RsqlFilterBuilder`, audit logging - see the [Cookbook](../cookbook/01-defining-privileges.md).

## 7. Component scan note

OrgSec's auto-configuration registers a few specific beans (the `SpringSecurityContextProvider`, the Person API filter chain, the in-memory storage configuration), but the bulk of the library's beans (`PrivilegeChecker`, `RsqlFilterBuilder`, `BusinessRoleConfiguration`, `SecurityDataStore`, `InMemorySecurityDataStorage`, the in-memory store / loader components) are `@Component`-annotated under `com.nomendi6.orgsec.common.*` and `com.nomendi6.orgsec.storage.inmemory.*`. The OrgSec auto-config only scans `com.nomendi6.orgsec.api`, and the default scan rooted at your `@SpringBootApplication` package does **not** reach those packages.

Add a **narrow** explicit scan to your application class - one that picks up only the in-memory runtime beans, not the entire OrgSec namespace:

```java
package com.example.docs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

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
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

Without the explicit scan, the context fails to start as soon as a bean depends on `PrivilegeChecker` (or the in-memory registry / store beans).

The list is intentionally narrow. The Quick Start is a **minimal evaluator example** - it shows how to register privileges, wire the storage backend, and call `PrivilegeChecker` programmatically against a `ResourceDef` you have already aggregated. It is **not** an end-to-end current-user flow: there is no login -> person id mapping, and no `PersonDataProvider` / `UserDataProvider` beans.

Two parts of the OrgSec namespace are deliberately excluded:

- **`PrivilegeSecurityService`** is excluded by the second filter above. That `@Service` is the entry point for the end-to-end current-user authorization flow and requires `PersonDataProvider` / `UserDataProvider` / `SecurityContextProvider` beans in its constructor. Once your application defines those (typical for a real web service that authenticates users), drop the second filter and let the scan pick `PrivilegeSecurityService` up.
- **`com.nomendi6.orgsec.autoconfigure`, `com.nomendi6.orgsec.api.controller`, `com.nomendi6.orgsec.api.service`, and the optional `storage.jwt` / `storage.redis` packages** are not in the scan because they are wired by their own auto-configurations through Spring Boot's `AutoConfiguration.imports` mechanism, with property-based gating. **Do not** broaden the scan to `"com.nomendi6.orgsec"` - that catches `JwtClaimsParser` (an unconditional `@Component` whose constructor needs a `JwtDecoder`) and breaks startup the moment the JWT JAR is on the classpath without the corresponding configuration. The narrow scan above is safe even if you later add the Redis or JWT modules.

The `StorageConfiguration` exclude is required because that class is already imported through `AutoConfiguration.imports`; including it again via the component scan duplicates its bean definitions. See [Architecture / Auto-configuration](../architecture/auto-configuration.md) for the full wiring detail.

## 8. Run it

Boot the application and watch the log:

```bash
mvn spring-boot:run
```

You should see lines from `BusinessRoleConfiguration`:

```
... Initializing business roles from N providers
... Business roles initialization completed. Active roles: [...]
```

The active-roles list includes every role contributed by every `BusinessRoleProvider` bean in the context. With the narrow scan above, the starter's `DefaultBusinessRoleProvider` is picked up and contributes `owner`, `customer`, and `contractor` as defaults. Your `application.yml` then overrides or extends these; for the YAML in step 2, the resulting list is `[owner, customer, contractor]` - `contractor` is left over from the default provider even though our YAML only mentions `owner` and `customer`. To suppress the defaults entirely, register a Spring bean named `applicationBusinessRoleProvider` that implements `BusinessRoleProvider`; the starter's default provider has `@ConditionalOnMissingBean(name = "applicationBusinessRoleProvider")` and steps aside.

If those lines appear, the business-role wiring is correct.

The privilege registration path (`DocumentPrivileges` -> `PrivilegeLoader.initializePrivileges` -> `AllPrivilegesStore.registerBulk`) does **not** log anything itself in 1.0.x. To verify privileges loaded, add a small `CommandLineRunner` that prints them on startup:

```java
package com.example.docs;

import com.nomendi6.orgsec.api.PrivilegeRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.TreeSet;

@Component
public class PrivilegeSmokeTest implements CommandLineRunner {
    private final PrivilegeRegistry registry;

    public PrivilegeSmokeTest(PrivilegeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(String... args) {
        System.out.println("Registered privileges: " + new TreeSet<String>(registry.getAllPrivilegeIdentifiers()));
    }
}
```

You should see `Registered privileges: [DOCUMENT_ALL_R, DOCUMENT_COMPHD_R, DOCUMENT_ORG_W]`. If those three identifiers appear, the privilege wiring is correct too. The runnable variant of this smoke test is built into [Examples / In-memory app](../examples/in-memory-app.md).

From here you can replace the stub `SecurityQueryProvider` with real queries, add more privileges, mark more entities as `SecurityEnabledEntity`, and start enforcing the checks at controller or service entry points.

## What you have not yet done

The Quick Start kept things deliberately small. The following pieces are necessary in any real deployment and have their own documents:

- **Spring Security integration** - the starter ships a `SpringSecurityContextProvider` that resolves the current login from `SecurityContextHolder`. Mapping that login to an OrgSec `personId` is the application's job through `PersonDataProvider` / `UserDataProvider`. If you authenticate differently, plug in a custom `SecurityContextProvider`. See [Spring Security Integration](./06-spring-security-integration.md).
- **Real database queries** - replace the stub with JPA queries that select the rows the in-memory loader expects. See [Storage / In-memory](../storage/02-in-memory.md).
- **List filtering** - turn the privilege evaluator into a query predicate with `RsqlFilterBuilder`. See [Cookbook / RSQL filtering](../cookbook/03-rsql-filtering.md).
- **Audit logging** - with the Redis backend, turn on `orgsec.storage.redis.audit.enabled` to route privilege decisions through `DefaultSecurityAuditLogger` and the `SECURITY_AUDIT` logger. Without Redis, supply your own `SecurityAuditLogger` bean. See [Configuration - Auditing](./04-configuration.md#auditing).
- **Multi-instance deployment** - replace the in-memory backend with Redis once you scale beyond one process. See [Storage / Redis](../storage/03-redis.md).
- **JWT-issued user identity** - if your callers arrive with a Keycloak-issued JWT that already carries OrgSec claims, switch to the JWT backend. See [Storage / JWT](../storage/04-jwt.md) and the [Keycloak mapper cookbook](../cookbook/05-keycloak-mapper.md).

## Next steps

- [Core Concepts](./03-core-concepts.md) - the privilege model in depth.
- [Configuration](./04-configuration.md) - every `orgsec.*` property in context.
- [Privileges and Business Roles](./05-privileges-and-business-roles.md) - the full naming convention and definition options.
- [Examples / In-memory app](../examples/in-memory-app.md) - the same code as a single runnable project.
