# OrgSec Storage Redis

Redis-based storage implementation for OrgSec Security Library providing high-performance distributed caching with automatic invalidation.

## Features

- **2-Level Cache Architecture**
  - L1: In-memory LRU cache (fast, local)
  - L2: Redis distributed cache (shared across instances)
- **High Performance Batch Operations**
  - MGET/MSET for bulk loading
  - Pipeline operations for reduced network round-trips
  - Connection pooling with Lettuce
- **Flexible Cache Warming Strategies**
  - Eager: Load all at startup
  - Progressive: Load in batches with delays
  - Lazy: Load on first access
  - Async warming support
- **Distributed Cache Invalidation** via Redis Pub/Sub
- **TTL-Based Expiration** per entity type
- **Circuit Breaker** for resilience (Resilience4j)
- **Security Features**
  - Configurable key obfuscation (SHA-256)
  - Strict mode for Jackson deserialization
  - Audit logging support
- **Health Checks** and metrics for monitoring
- **Spring Boot Auto-Configuration** for easy integration

## Requirements

- Java 17 or higher
- Spring Boot 3.4.5+
- Redis 6.0+ (tested with Redis 8.4.0)
- Maven 3.8+

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-storage-redis</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### 1. Configuration

Add to your `application.yml`:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379

orgsec:
  storage:
    redis:
      enabled: true
      ttl:
        person: 3600        # 1 hour
        organization: 3600
        role: 7200
        privilege: 7200
      cache:
        l1-max-size: 1000
      invalidation:
        enabled: true
        channel: orgsec:invalidation
```

### 2. Usage

Spring Boot will auto-configure `RedisSecurityDataStorage` bean:

```java
@Service
public class MySecurityService {

    @Autowired
    private SecurityDataStorage storage;

    public PersonDef getPerson(Long personId) {
        // Check cache first (L1 → L2)
        PersonDef person = storage.getPerson(personId);

        if (person == null) {
            // Load from database
            person = personRepository.findById(personId).orElse(null);

            // Update cache
            if (person != null) {
                storage.updatePerson(personId, person);
            }
        }

        return person;
    }

    public void updatePerson(Long personId, PersonDef person) {
        // Update database
        personRepository.save(person);

        // Update cache + publish invalidation event to other instances
        storage.updatePerson(personId, person);
    }

    public void refreshAllCaches() {
        // Clear all L1 caches and publish global refresh event
        storage.refresh();
    }
}
```

## Architecture

### 2-Level Cache

```
┌─────────────────┐
│   Application   │
└────────┬────────┘
         │
         ▼
    ┌────────┐
    │   L1   │  In-memory LRU cache (fast, local)
    │ Cache  │  - LinkedHashMap with access-order
    └────┬───┘  - Configurable max size
         │      - No TTL (evicted by LRU)
         │ miss
         ▼
    ┌────────┐
    │   L2   │  Redis distributed cache
    │ Cache  │  - Shared across instances
    └────┬───┘  - TTL-based expiration
         │      - JSON serialization
         │ miss
         ▼
  ┌──────────┐
  │ Database │
  └──────────┘
```

**Cache Flow:**
1. **Read**: Check L1 → L2 → Database
2. **Write**: Update L1 + L2 + Publish invalidation event
3. **Invalidation**: Redis Pub/Sub notifies other instances to evict from L1

### Cache Invalidation

When data is updated on **Instance A**:

```
Instance A                    Redis Pub/Sub                 Instance B
─────────────────────────────────────────────────────────────────────
updatePerson(123)
    │
    ├─ Update L1 cache
    ├─ Update L2 cache
    │
    └─ Publish event ──────────► Channel ──────────► Listener
       {type: "person",                                  │
        id: 123,                                         │
        instanceId: "A"}                                 │
                                                         ▼
                                                    Evict from L1
                                                    (if instanceId != B)
```

**Instance ID Pattern**: Each instance ignores its own invalidation events to avoid unnecessary L1 evictions.

## Configuration Reference

### Complete Configuration

See [application-redis-example.yml](src/main/resources/application-redis-example.yml) for complete example.

### Core Properties

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.enabled` | `false` | Enable Redis storage |
| `orgsec.storage.redis.host` | `localhost` | Redis host |
| `orgsec.storage.redis.port` | `6379` | Redis port |
| `orgsec.storage.redis.password` | - | Redis password (optional) |
| `orgsec.storage.redis.ssl` | `false` | Enable TLS for Redis connections |

### TTL Settings (seconds)

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.ttl.person` | `3600` | Person cache TTL |
| `orgsec.storage.redis.ttl.organization` | `3600` | Organization cache TTL |
| `orgsec.storage.redis.ttl.role` | `7200` | Role cache TTL |
| `orgsec.storage.redis.ttl.privilege` | `7200` | Privilege cache TTL |
| `orgsec.storage.redis.ttl.default` | `3600` | Default TTL |

### Cache Settings

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.cache.l1-max-size` | `1000` | Max entries in L1 cache |
| `orgsec.storage.redis.cache.obfuscate-keys` | `false` | Hash Redis keys with SHA-256 |

### Invalidation Settings

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.invalidation.enabled` | `false` | Enable Pub/Sub invalidation |
| `orgsec.storage.redis.invalidation.channel` | `orgsec:invalidation` | Redis channel name |
| `orgsec.storage.redis.invalidation.async` | `true` | Publish events async |

### Preload Settings

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.preload.enabled` | `true` | Enable cache warmup |
| `orgsec.storage.redis.preload.strategy` | `all` | What to load: `all`, `persons`, `organizations`, `roles` |
| `orgsec.storage.redis.preload.mode` | `eager` | How to load: `eager`, `progressive`, `lazy` |
| `orgsec.storage.redis.preload.batch-size` | `100` | Items per batch (progressive mode) |
| `orgsec.storage.redis.preload.batch-delay-ms` | `50` | Delay between batches (ms) |
| `orgsec.storage.redis.preload.async` | `false` | Non-blocking warmup |
| `orgsec.storage.redis.preload.parallelism` | `2` | Threads for async warmup |

### Connection Pool Settings

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.pool.enabled` | `true` | Enable connection pooling |
| `orgsec.storage.redis.pool.min-idle` | `5` | Minimum idle connections |
| `orgsec.storage.redis.pool.max-idle` | `10` | Maximum idle connections |
| `orgsec.storage.redis.pool.max-active` | `20` | Maximum active connections |
| `orgsec.storage.redis.pool.max-wait` | `2000` | Max wait for connection (ms) |

### Serialization Settings

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.serialization.fail-on-unknown-properties` | `false` | Strict JSON parsing |
| `orgsec.storage.redis.serialization.strict-mode` | `false` | Enable all strict settings |

### Audit Settings

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.audit.enabled` | `false` | Enable audit logging |
| `orgsec.storage.redis.audit.log-cache-access` | `false` | Log cache hits/misses |
| `orgsec.storage.redis.audit.log-privilege-checks` | `true` | Log privilege checks |

### Circuit Breaker Settings

| Property | Default | Description |
|----------|---------|-------------|
| `orgsec.storage.redis.circuit-breaker.enabled` | `true` | Enable circuit breaker |
| `orgsec.storage.redis.circuit-breaker.failure-rate-threshold` | `50` | Failure % to open circuit |
| `orgsec.storage.redis.circuit-breaker.wait-duration-in-open-state` | `60000` | Wait time in ms |
| `orgsec.storage.redis.circuit-breaker.sliding-window-size` | `100` | Calls to track |

## Batch Operations

For better performance when loading multiple entities, use batch operations:

```java
@Service
public class BulkSecurityService {

    @Autowired
    private RedisSecurityDataStorage storage;

    // Fetch multiple persons in a single Redis call
    public Map<Long, PersonDef> getPersons(List<Long> personIds) {
        return storage.getPersons(personIds);
    }

    // Store multiple persons in a single Redis pipeline
    public void updatePersons(Map<Long, PersonDef> persons) {
        storage.updatePersons(persons);
    }

    // Same for organizations and roles
    public Map<Long, OrganizationDef> getOrganizations(List<Long> orgIds) {
        return storage.getOrganizations(orgIds);
    }

    public Map<Long, RoleDef> getRoles(List<Long> roleIds) {
        return storage.getRoles(roleIds);
    }
}
```

Batch operations automatically:
- Check L1 cache first for each key
- Fetch missing keys from L2 in a single MGET call
- Populate L1 cache with fetched values
- Use pipeline for MSET with TTL

## Cache Warming Strategies

### Eager (Default)

Loads all data at startup in a single batch. Best for small to medium datasets.

```yaml
preload:
  mode: eager
```

### Progressive

Loads data in smaller batches with configurable delays. Reduces startup time and memory pressure for large datasets.

```yaml
preload:
  mode: progressive
  batch-size: 100
  batch-delay-ms: 50
  async: true
  parallelism: 4
```

### Lazy

No preloading - data is cached on first access. Best when only a subset of data is typically accessed.

```yaml
preload:
  mode: lazy
```

### Configuring Data Loaders

To enable cache warming, configure data loaders:

```java
@Configuration
public class CacheWarmingConfig {

    @Autowired
    private RedisSecurityDataStorage storage;

    @Autowired
    private PersonRepository personRepository;

    @PostConstruct
    public void configureLoaders() {
        CacheWarmer warmer = storage.getCacheWarmer();

        warmer.setPersonLoader(() ->
            personRepository.findAll().stream()
                .collect(Collectors.toMap(
                    p -> p.personId,
                    Function.identity()
                ))
        );

        // Similar for organizations and roles
    }
}
```

## Monitoring

### Health Check

Access health check at `/actuator/health`:

```json
{
  "status": "UP",
  "components": {
    "redisStorage": {
      "status": "UP",
      "details": {
        "ready": true,
        "providerType": "redis",
        "personL1CacheSize": 42,
        "organizationL1CacheSize": 15
      }
    }
  }
}
```

### Cache Statistics

Get L1 cache statistics:

```java
@Autowired
private RedisSecurityDataStorage storage;

public void printStats() {
    L1Cache.CacheStats stats = storage.getPersonL1Stats();

    System.out.println("Person L1 Cache Stats:");
    System.out.println("  Size: " + stats.getSize());
    System.out.println("  Hits: " + stats.getHitCount());
    System.out.println("  Misses: " + stats.getMissCount());
    System.out.println("  Hit Rate: " + stats.getHitRate() + "%");
}
```

### Metrics

Enable Prometheus metrics in `application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

Metrics available:
- `orgsec.cache.l1.size{entity="person"}` - L1 cache size
- `orgsec.cache.l1.hits{entity="person"}` - L1 cache hits
- `orgsec.cache.l1.misses{entity="person"}` - L1 cache misses
- `orgsec.cache.l1.hit_rate{entity="person"}` - L1 hit rate %

## Multi-Instance Setup

For distributed deployments with multiple application instances:

### Configuration

Each instance should have:
- **Same Redis connection** (host, port, database)
- **Same invalidation channel** (e.g., `orgsec:invalidation`)
- **Unique instance ID** (auto-generated if not specified)

```yaml
orgsec:
  storage:
    redis:
      invalidation:
        enabled: true
        channel: orgsec:invalidation  # Same for all instances
        instance-id: ${HOSTNAME}      # Unique per instance (optional)
```

### How It Works

1. **Instance A** updates person ID 123
   - Updates L1 + L2 cache
   - Publishes invalidation event to Redis channel

2. **Instance B, C, D** receive event
   - Check `instanceId` in event
   - If different from own ID → evict from L1 cache
   - Next read will fetch fresh data from L2

3. **Result**: All instances have consistent data

## Troubleshooting

### Redis Connection Issues

**Problem**: `Unable to connect to Redis at localhost:6379`

**Solutions**:
1. Check Redis is running: `redis-cli ping` → should return `PONG`
2. Verify connection settings in `application.yml`
3. Check firewall/network settings
4. Review logs: `logging.level.org.springframework.data.redis: DEBUG`

### Cache Misses

**Problem**: High cache miss rate

**Solutions**:
1. Enable cache warmup: `orgsec.storage.redis.preload.enabled: true`
2. Increase L1 cache size: `orgsec.storage.redis.cache.l1-max-size: 5000`
3. Increase TTL values for frequently accessed entities
4. Check invalidation events aren't too frequent

### Memory Issues

**Problem**: High memory usage on Redis server

**Solutions**:
1. Reduce TTL values to expire data faster
2. Enable key obfuscation to reduce key size: `obfuscate-keys: true`
3. Reduce L1 cache size
4. Monitor Redis memory: `redis-cli info memory`
5. Configure Redis maxmemory policy: `maxmemory-policy allkeys-lru`

### Invalidation Not Working

**Problem**: Instances not receiving invalidation events

**Solutions**:
1. Verify invalidation enabled: `orgsec.storage.redis.invalidation.enabled: true`
2. Check all instances use same channel name
3. Verify Redis Pub/Sub working: `redis-cli SUBSCRIBE orgsec:invalidation`
4. Check instance IDs are unique
5. Review logs for listener errors

## Performance Tips

### 1. Optimize L1 Cache Size

Set based on your workload:
- **Small apps** (< 1000 users): `l1-max-size: 500`
- **Medium apps** (1000-10000 users): `l1-max-size: 2000`
- **Large apps** (> 10000 users): `l1-max-size: 5000+`

### 2. Tune TTL Values

Set longer TTL for rarely-changing data:
```yaml
ttl:
  person: 3600        # Changes occasionally
  organization: 7200  # Rarely changes
  role: 14400         # Very stable
  privilege: 28800    # Almost never changes
```

### 3. Enable Async Publishing

Reduces latency on write operations:
```yaml
invalidation:
  async: true  # Don't wait for Pub/Sub confirmation
```

### 4. Redis Connection Pool

For high-throughput apps:
```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 20
          max-idle: 10
          min-idle: 5
```

### 5. Circuit Breaker Tuning

Prevent cascading failures:
```yaml
circuit-breaker:
  failure-rate-threshold: 50      # Aggressive
  wait-duration-in-open-state: 30000  # Retry faster
```

## Testing

### Unit Tests

```bash
mvn test -pl orgsec-storage-redis -Dtest="*Test"
```

209 tests covering:
- L1 Cache (LRU eviction, stats)
- L2 Redis Cache (CRUD, batch operations, serialization)
- RedisSecurityDataStorage (get/update, batch operations, lifecycle)
- Cache warming strategies (eager, progressive, lazy)
- Cache invalidation (publisher, listener)
- Configuration properties
- Exception handling

### Integration Tests

```bash
mvn test -pl orgsec-storage-redis -Dtest="*IntegrationTest"
```

27 integration tests using **Testcontainers**:
- Real Redis 8.4.0-alpine container
- End-to-end cache operations
- Distributed invalidation scenarios
- Multi-instance coordination

## License

Copyright © 2025 Nomendi6

## Support

For issues and questions:
- GitHub Issues: [orgsec issues](https://github.com/nomendi6/orgsec/issues)
- Documentation: [OrgSec Wiki](https://github.com/nomendi6/orgsec/wiki)
