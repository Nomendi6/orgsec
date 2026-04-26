# OrgSec

[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.14-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

OrgSec is a Spring Boot library that adds organizational role-based access control (RBAC) to Java applications. It models access as **privileges** scoped to a **business role** (`owner`, `customer`, `contractor`, ...) within an **organizational hierarchy**, evaluates them at runtime, and serves authorization data through a pluggable storage backend (in-memory, Redis, or JWT).

The full documentation lives in [`docs/`](./docs/index.md).

## Highlights

- **Hierarchical, multi-tenant authorization model.** Privileges scope to *exactly* one organization, *down* through descendants, or *up* through ancestors. Cascade evaluation runs across company -> org -> person and is fail-closed.
- **String-based privilege identifiers, registered at runtime.** Your application defines its own vocabulary - `DOCUMENT_READ`, `INVOICE_APPROVE`, `CONTRACT_SIGN_HD` - through `PrivilegeDefinitionProvider` beans. OrgSec ships no closed enum.
- **Three pluggable storage backends.** Choose in-memory (default), Redis (L1+L2 with Pub/Sub invalidation), or JWT (stateless Person from a token claim) per data type. Backend changes are configuration-only.
- **Spring Boot auto-configuration.** Add the starter, declare your business roles, register your privileges, and the privilege evaluator, security data store, audit logger, and Spring Security adapter are wired automatically.

## Modules

| Module                          | Purpose                                                                                                |
| ------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `orgsec-core`                   | Public API: SPIs, domain models, exceptions.                                                           |
| `orgsec-common`                 | Privilege evaluator, business-role configuration, RSQL filter builder.                                 |
| `orgsec-storage-inmemory`       | Default backend; thread-safe, process-local. Bundled with the starter.                                 |
| `orgsec-storage-redis`          | L1 + L2 + Pub/Sub invalidation, circuit breaker, preload strategies. Opt-in.                           |
| `orgsec-storage-jwt`            | Reads `PersonDef` from a JWT claim; delegates other types to another backend. Opt-in.                  |
| `orgsec-spring-boot-starter`    | Auto-configuration, configuration properties, Spring Security adapter, Person API.                     |

## Getting started

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

Declare your business roles:

```yaml
orgsec:
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
    customer:
      supported-fields: [COMPANY, COMPANY_PATH]
```

Then implement `SecurityEnabledEntity` on your domain class, register your privileges through a `PrivilegeDefinitionProvider`, and inject `PrivilegeChecker` where you need to make a decision. The full path is in the [Quick Start](./docs/guide/02-quick-start.md) (under thirty minutes) and the in-memory [example app](./docs/examples/in-memory-app.md).

## Documentation

- [Documentation home](./docs/index.md)
- [Introduction](./docs/guide/01-introduction.md) - what OrgSec solves and when to use it
- [Quick Start](./docs/guide/02-quick-start.md) - first privilege check in 30 minutes
- [Core Concepts](./docs/guide/03-core-concepts.md) - the privilege model in depth
- [Configuration](./docs/guide/04-configuration.md) - every `orgsec.*` property in context
- [Storage Overview](./docs/storage/01-overview.md) - pick a backend
- [Properties Reference](./docs/reference/properties.md) - every property name, type, default

## Compatibility

| OrgSec version | Spring Boot   | Spring Security | Java | Status                                       |
| -------------- | ------------- | --------------- | ---- | -------------------------------------------- |
| **1.0.x**      | 3.5.x         | 6.x             | 17   | Current GA; receives security and bug fixes  |
| 2.0.x          | 4.x (planned) | 7.x (planned)   | 21   | In development; not yet released             |

## Building

```bash
# Full build with tests
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Build a single module with its dependencies
mvn clean install -pl orgsec-storage-redis -am
```

Redis integration tests under `orgsec-storage-redis` use Testcontainers and require Docker.

## Releases

OrgSec is published to Maven Central via the Sonatype Central Portal. See [`CHANGELOG.md`](./CHANGELOG.md) for release history.

## Reporting security issues

Do not file public issues for security problems. See [`SECURITY.md`](./SECURITY.md) for the disclosure process.

## License

Apache License 2.0. See [`LICENSE`](./LICENSE).

## Links

- [GitHub repository](https://github.com/Nomendi6/orgsec)
- [Issues](https://github.com/Nomendi6/orgsec/issues)
- [Contributing](./CONTRIBUTING.md)
- [Changelog](./CHANGELOG.md)
- [Security policy](./SECURITY.md)
