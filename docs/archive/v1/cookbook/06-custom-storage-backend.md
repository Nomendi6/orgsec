# Custom Storage Backend

OrgSec ships three storage backends - in-memory, Redis, and JWT - that cover the common cases. If your deployment needs something else (a JDBC-backed cache shared across instances, a MongoDB store, a process-local cache that loads from a microservice over gRPC), you implement `SecurityDataStorage` and provide a Spring Boot auto-configuration that wires it. This cookbook is the recipe for doing that.

The example below uses a hypothetical "MongoDB" backend; the patterns are the same for any persistent / shared store.

## The SPI

`com.nomendi6.orgsec.storage.SecurityDataStorage` declares the contract. It splits cleanly into four groups of methods:

| Group           | Methods                                                            | Required? |
| --------------- | ------------------------------------------------------------------ | --------- |
| Reads           | `getPerson`, `getOrganization`, `getPartyRole`, `getPositionRole`, `getPrivilege` | Yes       |
| Writes          | `updatePerson`, `updateOrganization`, `updateRole`                  | Optional (default: `UnsupportedOperationException`) |
| Lifecycle       | `initialize`, `refresh`, `isReady`, `getProviderType`               | `initialize`, `refresh`, `isReady` are required; `getProviderType` has a default |
| Notifications   | `notifyPersonChanged`, `notifyOrganizationChanged`, `notifyPartyRoleChanged`, `notifyPositionRoleChanged` | Optional (default: no-op) |
| Snapshots (test) | `createSnapshot`, `restoreSnapshot`, `supportsSnapshot`           | Optional (default: `UnsupportedOperationException`) |

For a backend that aspires to feature parity with the in-memory or Redis backend, implement reads, writes, lifecycle, and notifications. Snapshots are only useful if you want OrgSec's testing utilities to work against your backend.

## Recipe: a Mongo-backed storage

The skeleton below is intentionally pseudo-Mongo - the pattern survives whatever real driver you use.

### 1. Module structure

```
orgsec-storage-mongo/
|-- pom.xml
`-- src/main/java/com/example/orgsec/mongo/
    |-- MongoSecurityDataStorage.java
    |-- MongoStorageProperties.java
    |-- MongoStorageAutoConfiguration.java
    `-- repository/
        |-- PersonDocument.java
        |-- OrganizationDocument.java
        `-- ...
```

The packaging mirrors `orgsec-storage-redis`. Place the auto-configuration in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### 2. Configuration properties

```java
package com.example.orgsec.mongo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orgsec.storage.mongo")
public class MongoStorageProperties {

    private boolean enabled = false;        // gates the auto-config
    private String connectionString;         // mongodb://...
    private String database = "orgsec";
    private long readTimeoutMs = 2000;

    // getters / setters
}
```

The `enabled` flag follows the same convention as Redis: the auto-configuration's `@ConditionalOnProperty` gate keys off it. Without `orgsec.storage.mongo.enabled: true`, no Mongo bean is created.

### 3. The storage class

```java
package com.example.orgsec.mongo;

import com.nomendi6.orgsec.model.*;
import com.nomendi6.orgsec.storage.SecurityDataStorage;

public class MongoSecurityDataStorage implements SecurityDataStorage {

    private final MongoCollection<PersonDocument> persons;
    private final MongoCollection<OrganizationDocument> organizations;
    // ... other collections

    private volatile boolean ready = false;

    public MongoSecurityDataStorage(/* injected via auto-config */) { ... }

    // ---- Reads ----

    @Override
    public PersonDef getPerson(Long personId) {
        if (personId == null || !ready) return null;
        PersonDocument doc = persons.find(eq("_id", personId)).first();
        return doc == null ? null : doc.toDef();
    }

    @Override
    public OrganizationDef getOrganization(Long orgId) {
        if (orgId == null || !ready) return null;
        OrganizationDocument doc = organizations.find(eq("_id", orgId)).first();
        return doc == null ? null : doc.toDef();
    }

    // ... getPartyRole, getPositionRole, getPrivilege

    // ---- Writes (optional but recommended) ----

    @Override
    public void updatePerson(Long personId, PersonDef person) {
        if (personId == null || person == null || !ready) return;
        persons.replaceOne(eq("_id", personId), PersonDocument.from(person), upsertOptions());
    }

    // ... updateOrganization, updateRole

    // ---- Lifecycle ----

    @Override
    public void initialize() {
        ensureIndexes();
        ready = true;
    }

    @Override
    public void refresh() {
        // Mongo is the source of truth; refresh is a no-op for this backend
        // (compare to in-memory, which reloads from SecurityQueryProvider)
    }

    @Override public boolean isReady() { return ready; }

    @Override public String getProviderType() { return "mongo"; }

    // ---- Notifications ----

    @Override
    public void notifyPersonChanged(Long personId) {
        // Whatever the right thing is for Mongo:
        // - if Mongo IS the source of truth, this is a no-op (the document already changed)
        // - if Mongo is a cache in front of another DB, invalidate / re-read here
    }

    // ... other notify methods
}
```

Two design questions to settle before you write this class:

1. **Is your backend a runtime cache or a primary store?** The in-memory backend is a runtime snapshot loaded from `SecurityQueryProvider` - the application database remains the canonical source of truth, and the in-memory cache is rebuilt on `notifyXxxChanged`. The Redis backend is a distributed cache without an authoritative reload path. A custom MongoDB backend can be either: a cache fronting your application database, or the canonical store of OrgSec data on its own. The semantics of `notifyXxxChanged` follow from this choice - a cache's notify clears stale entries; a canonical store's notify is typically a no-op (the backing collection already changed).
2. **Does `updateXxx` write to your backend?** If yes, application code can use it for immediate-freshness flows (see [Cookbook / Cache invalidation](./04-cache-invalidation.md)). If no, leave the default implementation that throws `UnsupportedOperationException`.

### 4. The auto-configuration

```java
package com.example.orgsec.mongo;

import com.nomendi6.orgsec.storage.SecurityDataStorage;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@AutoConfiguration
@ConditionalOnClass(com.mongodb.client.MongoClient.class)
@ConditionalOnProperty(prefix = "orgsec.storage.mongo", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MongoStorageProperties.class)
public class MongoStorageAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "mongoSecurityDataStorage")
    public SecurityDataStorage mongoSecurityDataStorage(MongoStorageProperties properties) {
        return new MongoSecurityDataStorage(properties);
    }
}
```

Notes:

- `@ConditionalOnClass(MongoClient.class)` keeps the auto-config dormant if the Mongo driver is not on the classpath.
- `@ConditionalOnProperty` activates it through the explicit flag.
- `@Primary` ensures Spring picks your backend over the in-memory backend that the starter pulls in transitively.
- `@ConditionalOnMissingBean(name = "...")` lets the application override your backend with a more specialized one.

Register the class in `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
com.example.orgsec.mongo.MongoStorageAutoConfiguration
```

### 5. Activate it

```yaml
orgsec:
  storage:
    primary: mongo                          # or "memory", with hybrid mode and data-sources routing
    mongo:
      enabled: true
      connection-string: ${MONGO_URI}
      database: orgsec
```

`StorageFeatureFlags` does not have a `features.mongo-enabled` flag. The application can still pick your backend by setting `primary: mongo` (which the starter does not validate against an enum) or by registering it via `data-sources`. If you want to integrate cleanly with the existing routing flags, add a custom configuration that inspects `StorageFeatureFlags` and decorates accordingly.

## Testing

OrgSec does not ship a contract-test artifact for storage backends; the test patterns are conventional. Three classes of tests pay off:

### a) Per-method unit tests

Verify each `getXxx` returns the expected domain object for known inputs. Run with a mocked driver or an embedded Mongo / H2 / etc. instance.

```java
@Test
void getPerson_returnsPersonDef_whenDocumentExists() {
    // given a PersonDocument in Mongo
    persons.insertOne(new PersonDocument(42L, "Alice", ...));
    // when
    PersonDef actual = storage.getPerson(42L);
    // then
    assertThat(actual).isNotNull();
    assertThat(actual.personId).isEqualTo(42L);
}
```

### b) Notify and update round-trips

Run a notify followed by a read; verify the read returns the new value (or `null`, depending on backend semantics).

### c) Conformance with the in-memory backend

For a cache-style backend, a useful smoke test is to load the same dataset into both your backend and the in-memory backend, then run the same `PrivilegeChecker.checkOrganizationPrivilege` calls against each. The two should agree on every decision. Where they disagree, your backend has either richer semantics (fine) or a bug in the read/aggregation path (not fine).

## Hybrid mode

If you want your backend to participate in hybrid mode with `data-sources.person`, `.organization`, `.role`, `.privilege` routing, you need to integrate with the storage facade more deeply than the simple `@Primary` registration above. The 1.0.x facade hard-codes the backend names (`primary`, `memory`, `redis`, `jwt`) in `StorageFeatureFlags.getDataSource(...)` consumers. To plug a fourth backend cleanly, you have two options:

1. **Run as primary only.** Make your backend the `@Primary` `SecurityDataStorage` and ignore the routing flags. The simplest approach.
2. **Wrap the existing backends.** Implement `SecurityDataStorage` as a *delegating facade* that reads from your backend or one of the existing ones based on per-data-type configuration. More flexible but requires more code.

For OrgSec 2.0.x the routing layer is expected to become extensible; until then, treat hybrid-mode integration as opt-in additional work.

## Operating contract

Whatever backend you ship, the runtime contract OrgSec depends on is:

- `getXxx` returns `null` cleanly for unknown ids; it does not throw.
- `initialize` runs once at startup and is idempotent if called again.
- `refresh` repopulates the cache without breaking concurrent reads. It is acceptable to lock briefly.
- `isReady` returns `false` until `initialize` finishes; reads called before that point should also return `null`.
- `notifyXxxChanged` does not throw on unknown ids; it logs and returns.

If your backend can be slow (network round-trip on every read), consider wrapping it in an L1 cache the way the Redis backend does. The `L1Cache<K, V>` class in `orgsec-storage-redis` is a small, dependency-free LRU; you can copy it or use it directly if you take a dependency on the Redis module.

## Distribution

Publish your backend as a Maven artifact alongside `orgsec-core` and `orgsec-common`:

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>orgsec-storage-mongo</artifactId>
    <version>1.0.0</version>
</dependency>
```

Application code adds your dependency, sets `orgsec.storage.mongo.enabled: true` and `orgsec.storage.primary: mongo`, and OrgSec runs against your backend. The rest of the application code - `PrivilegeChecker`, `RsqlFilterBuilder`, `@PreAuthorize` integration - does not change.

If your backend is generally useful, consider proposing it as an OrgSec add-on. OrgSec's storage SPI is intentionally small enough that a community-maintained `orgsec-storage-jdbc` or `orgsec-storage-mongo` is feasible.

## Where to go next

- [Architecture / Overview](../architecture/overview.md) - how the SPI fits in.
- [Architecture / Cache architecture](../architecture/cache-architecture.md) - the Redis backend's structure for reference.
- [Storage / Overview](../storage/01-overview.md) - the user-facing comparison your backend will join.
- [Reference / API](../reference/api.md) - the SPI surface.
