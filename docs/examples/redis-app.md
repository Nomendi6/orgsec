# Redis Example App

This walkthrough takes the [in-memory example app](./in-memory-app.md) and switches the storage backend to Redis. The application code is identical - the changes are confined to the Maven dependencies, the YAML configuration, and a small bit of runtime infrastructure (Docker Compose for Redis). If you have not built the in-memory example yet, do that first; this page only describes the *differences*.

## What you will need

- The same prerequisites as the in-memory example: Java 17, Maven 3.6+.
- **Docker** (or Podman) with `docker compose` - we run Redis in a container.

The example assumes you are following the in-memory walkthrough's project layout. Add the files described below alongside the existing ones.

## Project layout (additions)

```
in-memory-example/                  (now: redis-example)
|-- docker-compose.yml              <- new
|-- pom.xml                         <- modified
`-- src
    `-- main
        `-- resources
            `-- application.yml     <- modified
```

## `docker-compose.yml`

```yaml
services:
  redis:
    image: redis:7-alpine
    container_name: orgsec-redis
    command:
      - redis-server
      - --requirepass
      - localdev
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "localdev", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  redis-data:
```

Start it:

```bash
docker compose up -d redis
docker compose ps          # verify the healthcheck reports healthy
```

This is a *local-development* configuration. Plain text password and no TLS would be unacceptable in production; see the production block at the end.

## `pom.xml` (additions)

Add the Redis storage dependency. The in-memory module is already pulled in transitively by the starter and continues to coexist as a delegate target.

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-storage-redis</artifactId>
    <version>${orgsec.version}</version>
</dependency>
```

The Redis module pulls in `spring-boot-starter-data-redis` and the Lettuce client; you do not need to declare them separately.

## `application.yml`

Change `primary` to `redis`, set the three flags Redis needs to activate, and supply connection details. Keep the same business-role declarations.

```yaml
orgsec:
  storage:
    primary: redis
    features:
      memory-enabled: true
      redis-enabled: true
    redis:
      enabled: true                               # gates RedisStorageAutoConfiguration
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:localdev}
      ssl: false                                  # local dev only; set true in production
      ttl:
        person: 3600
        organization: 7200
        role: 7200
        privilege: 7200
      cache:
        l1-max-size: 1000
      invalidation:
        enabled: false                            # single instance; turn on for multi-instance
      preload:
        enabled: false                            # default true, but our example has no real loaders wired
      circuit-breaker:
        enabled: true
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

Two things to notice:

- **All three flags are required for Redis.** `primary: redis` selects the backend, `features.redis-enabled: true` tells the storage facade Redis is active, and `redis.enabled: true` gates the auto-configuration through `@ConditionalOnProperty`. Setting only one or two of them silently leaves Redis disabled.
- **`preload.enabled: false` is for this example.** The Redis preload step calls `CacheWarmer` data loaders that the application registers. Our example uses the stub `SecurityQueryProvider` from the in-memory walkthrough, which has no loaders to register, so preload would just log "Loader or store not configured, skipping warmup". For a real application, register loaders and turn preload back on. See [Storage / Redis - Preload strategies](../storage/03-redis.md#preload-strategies).

## Java code

**No changes** from the in-memory example. `Document`, `DocumentPrivileges`, `StubSecurityQueryProvider`, `DemoRunner`, and `Application` are byte-for-byte identical. That is the whole point of the storage SPI.

## Running it

Bring up Redis, then start the app:

```bash
docker compose up -d redis
mvn spring-boot:run
```

Look for these lines in the log, in order:

```
... Creating Redis connection factory: localhost:6379, ssl=false
... Creating RedisSecurityDataStorage as @Primary storage backend
... Initializing business roles from N providers
... Business roles initialization completed. Active roles: [...]
... CacheWarmer initialized with eager strategy
... Loader or store not configured, skipping warmup     <- expected with our stub
```

The active-roles list resolves the same way as in the in-memory example: with the narrow scan above, the starter's `DefaultBusinessRoleProvider` contributes `owner`, `customer`, `contractor`, and the YAML overrides `owner` and adds `reader`, producing `[owner, customer, contractor, reader]`.

The privilege registration path does not log in 1.0.x - the demo runner output below is what confirms the three privileges are in the registry:

```
Registered 3 privileges:
  - DOCUMENT_ALL_R
  - DOCUMENT_COMPHD_R
  - DOCUMENT_ORG_W
Sample document: id=1, title=Annual report
```

If you stop the app and start it again, the second startup is faster on parts that read Redis (Redis itself does not lose its data between app restarts).

## Verifying the cache

A privilege check from this stub example does not produce a meaningful Redis hit (the stub provider returns no rows, so there is nothing to cache). Once you replace the stub with real JPA queries, you will see entries appear in Redis:

```bash
docker compose exec redis redis-cli -a localdev keys 'orgsec:*'
```

You should see keys like `orgsec:person:42`, `orgsec:organization:22`, etc. The `obfuscate-keys: true` option (off in our config) hashes the keys for privacy - turn it on if Redis is shared with other applications.

## Cleanup

```bash
docker compose down -v        # -v removes the volume so the next start is clean
```

## What changes for production

The local-development config above is **not** production-ready. Before deploying:

| Property                                | Local                  | Production                         |
| --------------------------------------- | ---------------------- | ---------------------------------- |
| `redis.ssl`                             | `false`                | `true` - **mandatory**        |
| `redis.password`                        | `localdev` (committed) | sourced from environment / secret  |
| `redis.invalidation.enabled`            | `false`                | `true` if you run multiple instances |
| `redis.preload.enabled`                 | `false` (no loaders)   | `true` once `CacheWarmer` is wired |
| `orgsec.storage.redis.audit.enabled`    | `false`                | `true`                             |
| `redis.invalidation.channel`            | default                | service-specific if Redis is shared |

The full list with rationale is in [Operations / Production checklist](../operations/production-checklist.md).

## Where to go next

- [Storage / Redis](../storage/03-redis.md) - the full configuration reference.
- [Cookbook / Cache invalidation](../cookbook/04-cache-invalidation.md) - calling `notifyXxxChanged` correctly.
- [Operations / Production checklist](../operations/production-checklist.md) - deploying for real.
- [Examples / In-memory app](./in-memory-app.md) - the baseline this example builds on.
