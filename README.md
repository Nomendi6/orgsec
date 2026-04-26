# OrgSec Security Library

[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Spring Boot library for organizational role-based access control.

OrgSec provides organizational security functionality including privilege management, business role configuration, hierarchical access control, and pluggable storage backends.

## Features

- **Privilege management** - fine-grained privilege model with operations (READ/WRITE/EXECUTE), organizational scopes (company/org/person), and hierarchy support (EXACT, UP, DOWN)
- **Business roles** - configurable business roles with YAML-based or provider-based definitions
- **Pluggable storage** - multiple storage backends: in-memory, Redis (with 2-level cache), and JWT
- **Spring Boot integration** - auto-configuration, configuration properties, health indicators
- **Audit logging** - extensible audit logging interface for security events
- **Cache invalidation** - Redis Pub/Sub for distributed cache invalidation across instances
- **RSQL filtering** - security-aware query filtering

## Project Structure

The project is organized as a Maven multi-module project:

```
orgsec
├── orgsec-core                  # Core API, interfaces, models, and DTOs
├── orgsec-common                # Shared business logic, services, and loaders
├── orgsec-storage-inmemory      # In-memory storage implementation
├── orgsec-storage-redis         # Redis storage with 2-level caching (L1 in-memory + L2 Redis)
├── orgsec-storage-jwt           # JWT token-based storage (person data from token claims)
└── orgsec-spring-boot-starter   # Spring Boot auto-configuration and REST API
```

### Module details

| Module | Description |
|--------|-------------|
| **orgsec-core** | Core interfaces (`SecurityDataStorage`, `SecurityEnabledEntity`, `PrivilegeRegistry`), domain models (`PersonDef`, `RoleDef`, `PrivilegeDef`, `OrganizationDef`), DTOs, constants, and exception hierarchy |
| **orgsec-common** | Business logic layer - `PrivilegeChecker` for privilege validation, `BusinessRoleConfiguration`, `SecurityDataStore`, `RsqlFilterBuilder`, and event publishing |
| **orgsec-storage-inmemory** | Thread-safe in-memory `SecurityDataStorage` implementation with data loaders, snapshot support for testing, and auto-initialization |
| **orgsec-storage-redis** | Redis-based storage with L1 (in-memory LRU) and L2 (Redis) caching, Pub/Sub cache invalidation, circuit breaker (Resilience4j), configurable cache warming strategies, and health indicators |
| **orgsec-storage-jwt** | Hybrid storage that reads person data from JWT token claims (stateless) and delegates organization/role/privilege queries to another storage backend |
| **orgsec-spring-boot-starter** | Spring Boot auto-configuration, `SpringSecurityContextProvider`, configuration properties (`orgsec.*`), and a REST API for person data (useful for Keycloak mappers) |

## Requirements

- Java 17+
- Maven 3.6+
- Spring Boot 3.4.5+

## Getting Started

### 1. Clone and install

```bash
git clone https://github.com/Nomendi6/orgsec.git
cd orgsec
mvn clean install -DskipTests
```

After installation, the library will be available in your local Maven repository (`~/.m2/repository`).

### 2. Add dependency

**Maven:**

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

**Gradle:**

```gradle
implementation 'com.nomendi6.orgsec:orgsec-spring-boot-starter:1.0.1'
```

The starter includes `orgsec-core`, `orgsec-common`, and `orgsec-storage-inmemory` by default.

### 3. Choose a storage backend

The starter uses **in-memory storage** by default. For other backends, add the corresponding dependency:

**Redis storage:**

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-storage-redis</artifactId>
    <version>1.0.1</version>
</dependency>
```

**JWT storage:**

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-storage-jwt</artifactId>
    <version>1.0.1</version>
</dependency>
```

## Configuration

All configuration uses the `orgsec` prefix in `application.yml`. Only business roles need to be configured explicitly - everything else has sensible defaults.

### Minimal configuration

The only required configuration is defining your business roles. Storage defaults to in-memory, and all security features are enabled by default.

```yaml
orgsec:
  business-roles:
    owner:
      supported-fields:
        - COMPANY
        - COMPANY_PATH
        - ORG
        - ORG_PATH
        - PERSON
    customer:
      supported-fields:
        - COMPANY
        - COMPANY_PATH
        - PERSON
    contractor:
      supported-fields:
        - PERSON
```

### Full configuration with in-memory storage

```yaml
orgsec:
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
    customer:
      supported-fields: [COMPANY, COMPANY_PATH, PERSON]
    contractor:
      supported-fields: [PERSON]

  storage:
    primary: memory                        # memory | jwt | redis
    fallback: memory                       # Fallback if primary fails
    features:
      memory-enabled: true
      jwt-enabled: false
      redis-enabled: false
      hybrid-mode-enabled: false
    # Per-data-type storage routing (hybrid mode)
    data-sources:
      person: primary                      # primary | jwt | redis | memory
      organization: primary
      role: primary
      privilege: memory                    # Privileges always from memory for performance
```

### Redis storage configuration

Requires `orgsec-storage-redis` dependency.

```yaml
orgsec:
  storage:
    primary: redis
    features:
      redis-enabled: true
      memory-enabled: true                 # Keep memory as delegate/fallback
    redis:
      enabled: true
      host: localhost
      port: 6379
      password: ""
      timeout: 2000
      fallback-storage: memory
      cache-ttl-seconds: 3600
      key-prefix: "security:"
      ttl:
        person: 3600                       # TTL per entity type (seconds)
        organization: 7200
        role: 7200
        privilege: 7200
      cache:
        l1-enabled: true                   # In-memory L1 cache
        l1-max-size: 1000
      invalidation:
        enabled: true                      # Redis Pub/Sub cache invalidation
        channel: "orgsec:invalidation"
      preload:
        enabled: true
        on-startup: true
        strategy: all                      # all | persons | organizations | roles
        mode: eager                        # eager | progressive
      circuit-breaker:
        enabled: true
        failure-threshold: 50
        wait-duration: 30000
        sliding-window-size: 10
      pool:
        min-idle: 5
        max-idle: 10
        max-active: 20
```

### JWT storage configuration

Requires `orgsec-storage-jwt` dependency. Person data is read from JWT claims; organization, role, and privilege data is delegated to another storage backend (in-memory by default).

```yaml
orgsec:
  storage:
    primary: jwt
    features:
      jwt-enabled: true
      memory-enabled: true                 # In-memory as delegate for org/role/privilege
    data-sources:
      person: jwt                          # Person data from JWT token
      organization: memory
      role: memory
      privilege: memory
  jwt:
    security-claims: auth_data             # JWT claim containing security data
    token-header: X-Security-Data          # HTTP header for security data token
```

## Usage

### Registering privileges

```java
@Component
public class AppPrivilegeSetup {

    private final PrivilegeRegistry privilegeRegistry;

    public AppPrivilegeSetup(PrivilegeRegistry privilegeRegistry) {
        this.privilegeRegistry = privilegeRegistry;
    }

    @PostConstruct
    public void registerPrivileges() {
        privilegeRegistry.registerPrivilege("DOCUMENT_READ",
            PrivilegeDef.builder()
                .name("DOCUMENT_READ")
                .resourceName("Document")
                .operation(PrivilegeOperation.READ)
                .companyDirection(PrivilegeDirection.HIERARCHY_DOWN)
                .orgDirection(PrivilegeDirection.EXACT)
                .personDirection(PrivilegeDirection.NONE)
                .build());

        privilegeRegistry.registerPrivilege("DOCUMENT_WRITE",
            PrivilegeDef.builder()
                .name("DOCUMENT_WRITE")
                .resourceName("Document")
                .operation(PrivilegeOperation.WRITE)
                .companyDirection(PrivilegeDirection.EXACT)
                .orgDirection(PrivilegeDirection.EXACT)
                .personDirection(PrivilegeDirection.EXACT)
                .build());
    }
}
```

### Implementing SecurityEnabledEntity

```java
@Entity
public class Document implements SecurityEnabledEntity {

    @Id
    private Long id;
    private String title;
    private Long companyId;
    private Long orgunitId;
    private Long personId;

    @Override
    public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
        return switch (fieldType) {
            case COMPANY -> companyId;
            case ORG -> orgunitId;
            case PERSON -> personId;
            default -> null;
        };
    }

    @Override
    public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
        switch (fieldType) {
            case COMPANY -> this.companyId = (Long) value;
            case ORG -> this.orgunitId = (Long) value;
            case PERSON -> this.personId = (Long) value;
        }
    }
}
```

### Checking privileges

```java
@Service
public class DocumentService {

    private final PrivilegeChecker privilegeChecker;
    private final SecurityDataStore securityDataStore;

    public DocumentService(PrivilegeChecker privilegeChecker, SecurityDataStore securityDataStore) {
        this.privilegeChecker = privilegeChecker;
        this.securityDataStore = securityDataStore;
    }

    public boolean canReadDocument(Long personId, DocumentDTO document) {
        PersonData person = securityDataStore.getPerson(personId);
        ResourceDef resource = ResourceDef.builder().name("Document").build();
        PrivilegeDef privilege = privilegeChecker.getResourcePrivileges(resource, PrivilegeOperation.READ);
        // ... perform privilege check based on business role context
        return privilege != null;
    }
}
```

## Building

```bash
# Build without tests
mvn clean install -DskipTests

# Build with tests
mvn clean install

# Run tests only
mvn test

# Run tests with integration tests (requires Docker for Redis tests)
mvn verify

# Build a specific module
mvn clean install -pl orgsec-core

# Build a module and its dependencies
mvn clean install -pl orgsec-storage-redis -am
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for release history.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contact

- GitHub Issues: https://github.com/Nomendi6/orgsec/issues
