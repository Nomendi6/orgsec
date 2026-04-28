# Spring Boot Starter

`orgsec-spring-boot-starter` wires the common OrgSec beans for a Spring Boot application.

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-spring-boot-starter</artifactId>
    <version>1.0.3</version>
</dependency>
```

The starter brings in core APIs, common services, and the in-memory storage backend. Redis and JWT are separate optional modules.

## What The Starter Provides

- privilege evaluator services
- RSQL filter builder
- business role configuration binding
- storage facade and in-memory backend
- integration points for `SecurityContextProvider`, `PrivilegeDefinitionProvider`, and storage providers
- Spring Security support when Spring Security is present

## What Your Application Provides

- authentication and user-to-person mapping
- business role configuration under `orgsec.business-roles`
- privilege definitions
- protected entities or DTOs exposing Resource Security Context
- security data through fixtures, `SecurityQueryProvider`, Redis warmup, JWT claims, or another adapter

## Component Scan

If your application package is not a parent of `com.nomendi6.orgsec`, make sure OrgSec packages are scanned or imported according to your project setup. Missing component scan is usually visible as missing beans such as `PrivilegeChecker`, `RsqlFilterBuilder`, `BusinessRoleConfiguration`, or in-memory loaders/stores.

Keep application code and OrgSec code separated. Do not place your domain classes under `com.nomendi6.orgsec`.

## Minimal Configuration

```yaml
orgsec:
  storage:
    primary: memory
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
```

Next: [Spring Security](./02-spring-security.md).
