# In-memory Example App

This walkthrough builds a complete, runnable Spring Boot application that uses OrgSec with the in-memory storage backend. Every file you need appears in this document - you should be able to copy-paste them into a fresh Maven project and run it. There are no external dependencies beyond Java 17 and Maven.

## What you will build

A small Spring Boot application that:

- Defines two business roles, `owner` and `reader`.
- Models a `Document` entity that implements `SecurityEnabledEntity`.
- Registers three privileges through a `PrivilegeDefinitionProvider`.
- Loads *stub* data through a `SecurityQueryProvider` (so the app boots without a real database).
- Prints the registered privileges on startup as a wiring smoke-test.

The example is on purpose a *wiring-only* skeleton: it does not connect to a database, does not expose any HTTP endpoint, and does not integrate with Spring Security. Because the stub query provider returns empty lists, no person has any organizational membership - so a real privilege check would always return `false`. Once you replace the stub with JPA queries against your schema, the same code starts producing real authorization decisions. The intent here is to show the *wiring*; the database integration is the next step.

## Project layout

```
in-memory-example/
|-- pom.xml
`-- src
    `-- main
        |-- java
        |   `-- com
        |       `-- example
        |           `-- docs
        |               |-- Application.java
        |               |-- Document.java
        |               |-- DocumentPrivileges.java
        |               |-- StubSecurityQueryProvider.java
        |               `-- DemoRunner.java
        `-- resources
            `-- application.yml
```

## `pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>orgsec-in-memory-example</artifactId>
    <version>1.0.0</version>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.14</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>17</java.version>
        <orgsec.version>1.0.1</orgsec.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.nomendi6.orgsec</groupId>
            <artifactId>orgsec-spring-boot-starter</artifactId>
            <version>${orgsec.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

## `application.yml`

```yaml
orgsec:
  storage:
    primary: memory
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
    reader:
      supported-fields: [COMPANY, COMPANY_PATH]

logging:
  level:
    com.nomendi6.orgsec: INFO
    com.example.docs: INFO
```

The `owner` role advertises every field type; the `reader` role exposes the company line only. No storage tuning is required - the in-memory backend defaults are fine.

## `Document.java`

The entity that the privilege check protects. It implements `SecurityEnabledEntity` and exposes its security fields under both business roles.

```java
package com.example.docs;

import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.interfaces.SecurityEnabledEntity;

public class Document implements SecurityEnabledEntity {

    private final Long id;
    private final String title;

    private final Long ownerCompanyId;
    private final String ownerCompanyPath;
    private final Long ownerOrgId;
    private final String ownerOrgPath;
    private final Long ownerPersonId;

    private final Long readerCompanyId;
    private final String readerCompanyPath;

    public Document(Long id, String title,
                    Long ownerCompanyId, String ownerCompanyPath,
                    Long ownerOrgId, String ownerOrgPath,
                    Long ownerPersonId,
                    Long readerCompanyId, String readerCompanyPath) {
        this.id = id;
        this.title = title;
        this.ownerCompanyId = ownerCompanyId;
        this.ownerCompanyPath = ownerCompanyPath;
        this.ownerOrgId = ownerOrgId;
        this.ownerOrgPath = ownerOrgPath;
        this.ownerPersonId = ownerPersonId;
        this.readerCompanyId = readerCompanyId;
        this.readerCompanyPath = readerCompanyPath;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }

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
        if ("reader".equalsIgnoreCase(businessRole)) {
            return switch (fieldType) {
                case COMPANY -> readerCompanyId;
                case COMPANY_PATH -> readerCompanyPath;
                default -> null;
            };
        }
        return null;
    }

    @Override
    public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
        // Immutable for this example; in JPA you would assign to mutable fields here.
        throw new UnsupportedOperationException("Document is immutable in this example");
    }
}
```

## `DocumentPrivileges.java`

Three privileges: read down the company hierarchy, write at the exact organization, and a super-user read-all. The `PrivilegeDefinitionProvider` registers them at startup.

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
        "DOCUMENT_COMPHD_R",
        "DOCUMENT_ORG_W",
        "DOCUMENT_ALL_R"
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

## `StubSecurityQueryProvider.java`

The in-memory backend asks `SecurityQueryProvider` for `Tuple` rows of persons, organizations, and roles. This stub returns empty lists - enough to boot the application and show the wiring. Replace each method with a real JPA query when you connect to a database.

```java
package com.example.docs;

import com.nomendi6.orgsec.provider.SecurityQueryProvider;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StubSecurityQueryProvider implements SecurityQueryProvider {

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

## `DemoRunner.java`

A `CommandLineRunner` that prints the registered privileges and a sample document. With the stub query provider returning empty data, no person has any organizational membership - so `getResourcePrivileges` returns `null` for every business role - but the *registration* path is fully exercised, which is what we want to verify.

```java
package com.example.docs;

import com.nomendi6.orgsec.api.PrivilegeRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.TreeSet;

@Component
public class DemoRunner implements CommandLineRunner {

    private final PrivilegeRegistry registry;

    public DemoRunner(PrivilegeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(String... args) {
        Set<String> identifiers = new TreeSet<>(registry.getAllPrivilegeIdentifiers());
        System.out.println("Registered " + identifiers.size() + " privileges:");
        identifiers.forEach(id -> System.out.println("  - " + id));

        Document sample = new Document(
            1L, "Annual report",
            10L, "|10|",      // owner company
            22L, "|10|22|",   // owner org
            42L,               // owner person
            null, null         // no reader assigned
        );
        System.out.println("Sample document: id=" + sample.getId() + ", title=" + sample.getTitle());
    }
}
```

## `Application.java`

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

The explicit `@ComponentScan` is a workaround for a wiring gap in OrgSec 1.0.x: `PrivilegeChecker`, `RsqlFilterBuilder`, `BusinessRoleConfiguration`, `SecurityDataStore`, `InMemorySecurityDataStorage`, and the in-memory store / loader components are `@Component`-annotated under `com.nomendi6.orgsec.common.*` and `com.nomendi6.orgsec.storage.inmemory.*`, but OrgSec's auto-configuration only scans `com.nomendi6.orgsec.api`. The default scan rooted at `com.example.docs` does not reach those packages either, so without the explicit entry the application context fails to start as soon as a bean depends on `PrivilegeChecker` (or the in-memory registry / store beans).

This example is a **wiring-only smoke test**, not an end-to-end current-user flow. The scan list is **deliberately narrow**: it picks up only the beans the example needs to register privileges, populate stores from a `SecurityQueryProvider`, and invoke `PrivilegeChecker` programmatically. It does **not** include:

- `com.nomendi6.orgsec.autoconfigure`, `com.nomendi6.orgsec.api.controller`, `com.nomendi6.orgsec.api.service` - wired by their own auto-configurations through Spring Boot's `AutoConfiguration.imports` mechanism.
- The optional `storage.jwt` / `storage.redis` packages - same reason. A broader scan such as `"com.nomendi6.orgsec"` would pull `JwtClaimsParser` (an unconditional `@Component` whose constructor needs a `JwtDecoder`) into the context the moment you add the JWT JAR, breaking startup before you have opted into the JWT backend.
- `PrivilegeSecurityService` (excluded by the second filter above) - this `@Service` requires `PersonDataProvider` and `UserDataProvider` beans in its constructor for end-to-end current-user authorization. The example does not provide them. Once your application defines stub or real `PersonDataProvider` / `UserDataProvider` beans, you can remove the exclude and pick up `PrivilegeSecurityService`.
- `StorageConfiguration` (excluded by the first filter) - already imported through `AutoConfiguration.imports`; including it again via the component scan duplicates its bean definitions.

See [Architecture / Auto-configuration](../architecture/auto-configuration.md) for the full wiring detail.

## Running it

From the project root:

```bash
mvn spring-boot:run
```

The first time you run, Maven downloads OrgSec from your local repository (`mvn install` in the OrgSec project must have been run first if you have not published 1.0.1 to a remote yet). Subsequent runs start in a few seconds.

You should see the business-role initialization logs from `BusinessRoleConfiguration`:

```
... Initializing business roles from N providers
... Business roles initialization completed. Active roles: [...]
```

The list includes every role contributed by every active `BusinessRoleProvider` bean. With this example's component scan, the starter's `DefaultBusinessRoleProvider` is picked up and contributes `owner`, `customer`, and `contractor`; the YAML in `application.yml` then overrides `owner` and adds `reader`. The actual list is therefore `[owner, customer, contractor, reader]` - `customer` and `contractor` stay from the default provider even though the example's YAML only mentions `owner` and `reader`. To suppress the defaults entirely, register a Spring bean named `applicationBusinessRoleProvider` implementing `BusinessRoleProvider`; the starter's default provider has `@ConditionalOnMissingBean(name = "applicationBusinessRoleProvider")` and steps aside.

The privilege registration path itself does not log in 1.0.x - the `DemoRunner` below is what confirms the three privileges are in the registry. Its output follows:

```
Registered 3 privileges:
  - DOCUMENT_ALL_R
  - DOCUMENT_COMPHD_R
  - DOCUMENT_ORG_W
Sample document: id=1, title=Annual report
```

If those lines all appear, your wiring is correct.

## What to change next

This example stops short of a real authorization decision because the `SecurityQueryProvider` returns no data. The realistic next steps:

1. **Connect to a database.** Replace `StubSecurityQueryProvider` with JPA queries against the schema described in [Storage / In-memory](../storage/02-in-memory.md). The four `AllXxxStore` classes inside the in-memory backend consume the resulting `Tuple` rows.
2. **Add a Spring Security context.** When you authenticate users through Spring Security, OrgSec's auto-configured `SpringSecurityContextProvider` resolves the current login from `SecurityContextHolder`. Mapping that login to an OrgSec `personId` is the application's job: implement `PersonDataProvider` (and typically `UserDataProvider`) to perform the lookup, or use the JWT backend when the token already carries the OrgSec person claim. See [Spring Security Integration](../guide/06-spring-security-integration.md).
3. **Wire `PrivilegeChecker` into your services.** Use the pattern from [Cookbook / Securing entities](../cookbook/02-securing-entities.md).
4. **Filter list endpoints.** Use `RsqlFilterBuilder` to convert privileges into a query predicate. See [Cookbook / RSQL filtering](../cookbook/03-rsql-filtering.md).
5. **Move to Redis when you scale.** When a second JVM joins the deployment, replace the in-memory backend with Redis as documented in [Storage / Redis](../storage/03-redis.md).

## Where to go next

- [Quick Start](../guide/02-quick-start.md) - the same wiring described as a checklist.
- [Cookbook / Defining privileges](../cookbook/01-defining-privileges.md) - recipes for common privilege patterns.
- [Cookbook / Securing entities](../cookbook/02-securing-entities.md) - integrating with services and controllers.
- [Storage / In-memory](../storage/02-in-memory.md) - what the backend loads and when.
