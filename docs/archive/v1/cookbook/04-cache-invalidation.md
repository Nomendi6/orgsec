# Cache Invalidation

OrgSec caches `PersonDef`, `OrganizationDef`, and `RoleDef` instances so privilege checks do not pay a database round-trip on every request. The trade-off is that a cache becomes wrong the moment your domain code changes the underlying data and *does not tell OrgSec*. This cookbook covers the four notify hooks, where to call them from, and how to make the call propagate across instances when you run more than one.

## The four notify hooks

`SecurityDataStorage` exposes four method-level notifications. Each is `default` no-op on the interface; the in-memory and Redis backends override them to refresh / invalidate caches.

| Method                                       | Call after                                                              |
| -------------------------------------------- | ----------------------------------------------------------------------- |
| `notifyPersonChanged(Long personId)`         | A person row, their default org / company, or their position-role assignment changes |
| `notifyOrganizationChanged(Long orgId)`      | An organization row changes - including a path move, name change, or business-role assignment |
| `notifyPartyRoleChanged(Long roleId)`        | A party-level role definition or its privilege set changes              |
| `notifyPositionRoleChanged(Long roleId)`     | A position-level role definition or its privilege set changes           |

There is **no** `notifyPrivilegeChanged` in 1.0.x - privilege definitions come from your `PrivilegeDefinitionProvider` at startup and do not change at runtime in the supported flow.

Behavior per backend:

- **In-memory.** Each notify reloads the affected entity from `SecurityQueryProvider` and updates the in-memory store. Aggregated views (`BusinessRoleDef.resourcesMap` on each affected `OrganizationDef`) are recomputed.
- **Redis.** Each notify *invalidates* the local L1 entry and publishes an invalidation event on `orgsec:invalidation` (when `invalidation.enabled: true`) so other instances drop their L1 entries. **It does *not* re-read from your database, and it does *not* write a new value to L2.** The next read on any instance falls through to L2; if L2 still has the old value, callers see it until the L2 TTL expires or someone writes a fresh value through `updateXxx`.
- **JWT.** No-op for `Person` (it comes from the token). The other notifies are forwarded to whichever storage backend is delegated to under hybrid mode.

### `notifyXxxChanged` vs. `updateXxx` (Redis)

The Redis backend exposes a second family of methods that *do* write to the cache:

| Method                          | L1 effect          | L2 effect                                              | Pub/Sub                  |
| ------------------------------- | ------------------ | ------------------------------------------------------ | ------------------------ |
| `notifyPersonChanged(id)`       | Invalidate L1      | **No write** - the L2 entry is left as-is until next `update` or TTL expiry | Publish invalidation     |
| `updatePerson(id, personDef)`   | Put into L1        | Write-through with the configured per-type TTL         | Publish invalidation     |

The same split applies to `notifyOrganizationChanged` / `updateOrganization`, and to the role variants. **For Redis deployments, the choice between the two is load-bearing for authorization freshness:**

- `notifyXxxChanged` - drops the local L1 entry and publishes an invalidation so other instances drop theirs. **It does not refresh L2.** The next read on any instance falls through to L2 and returns whatever value L2 holds, which is typically the *old* value until L2's TTL expires or someone calls `updateXxx`. Use this when an L1 invalidation is what you want and a TTL-bounded staleness window for L2 is acceptable.
- `updateXxx(id, def)` - writes the new entity into both L1 and L2 with the configured TTL and publishes an invalidation. Other instances drop their L1 (so they re-read from L2) and the L2 they read holds the fresh value. Use this whenever you have the new `PersonDef` / `OrganizationDef` / `RoleDef` in hand and need every instance to see it immediately - the typical revocation flow.

### How `updateXxx` is defined across backends

`updateXxx` is an **optional capability** on the storage SPI. Knowing what each backend does with it matters when you write backend-agnostic application code:

- **`SecurityDataStorage` interface (default).** The default implementations of `updatePerson` / `updateOrganization` / `updateRole` throw `UnsupportedOperationException`. A custom backend that does not override them cannot be used with the `update` flow.
- **In-memory backend.** Overrides `updatePerson` and `updateOrganization` as a *direct write* of the supplied `*Def` into the in-memory store - it does **not** reload from `SecurityQueryProvider`. (`updateRole` is currently implemented as a full `refresh()`; treat it as a more expensive call.) The in-memory equivalent of "reload from the database" is `notifyPersonChanged` / `notifyOrganizationChanged`, which calls into `SecurityQueryProvider.syncPersonByPersonId(...)` (or the equivalent) and rebuilds the cached entry.
- **Redis backend.** Overrides `updatePerson` / `updateOrganization` / `updateRole` as a write-through to L1 + L2 plus a Pub/Sub invalidation, as described in the table above.
- **JWT backend.** `Person` comes from the token, so the `Person` updates are not meaningful; the other types are forwarded to the delegate backend (memory or Redis) under hybrid mode.

**Practical consequence.** The `update` family is the right tool when you have a freshly-built `*Def` in hand and want it to *be* the cached value. The `notify` family is the right tool when you want the backend to figure out the new value itself (in-memory) or you only need an L1 invalidation with TTL-bounded L2 staleness (Redis). Backend-agnostic code that calls `updateXxx` should be ready for `UnsupportedOperationException` if it might run against a backend that does not implement it.

## Recipe 1a: notify from a service method (in-memory backend, minimal example)

The simplest place to call a notify hook is the service method that performs the change. The method already has the entity ids; one extra call adds the invalidation. For the in-memory backend, `notifyPersonChanged` reloads the affected `PersonDef` from `SecurityQueryProvider`.

```java
@Service
public class RoleAssignmentService {

    private final RoleAssignmentRepository repository;
    private final SecurityDataStorage storage;

    public RoleAssignmentService(RoleAssignmentRepository repository, SecurityDataStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Transactional
    public void assignRole(Long personId, Long orgId, Long positionRoleId) {
        repository.save(new RoleAssignment(personId, orgId, positionRoleId));
        storage.notifyPersonChanged(personId);
    }

    @Transactional
    public void revokeRole(Long personId, Long orgId, Long positionRoleId) {
        repository.deleteAssignment(personId, orgId, positionRoleId);
        storage.notifyPersonChanged(personId);
    }
}
```

A revoked role that does not trigger a notify is the most common authorization bug in OrgSec deployments - for the in-memory backend, **call `notify` when in doubt**.

> **Transactional caveat.** This recipe runs the notify *inside* the JPA transaction, which has the same class of problem as Recipe 2's JPA listener:
>
> - If the transaction rolls back, the in-memory backend may have already reloaded a `PersonDef` that includes uncommitted state, leaving a stale cache until the next invalidation.
> - If the JPA persistence context has not flushed before the reload, `SecurityQueryProvider` may read the *old* state and overwrite a more recent value with stale data.
>
> Treat Recipe 1a as a **minimal example**, not the recommended production pattern. For authorization-relevant writes, use the `AFTER_COMMIT` event approach from **Recipe 3** - it is the same shape that Redis requires and removes the rollback / flush-ordering risk on in-memory too. Direct `notify` from inside a `@Transactional` method is acceptable only when you can prove the transaction will commit (or when the cost of a rare temporary inconsistency is acceptable).

## Recipe 1b: the Redis revocation pattern (concept)

For Redis, calling `notifyPersonChanged` alone is **not enough** to revoke an old role immediately: it drops L1 entries, but the L2 cache still holds the previous `PersonDef` until its TTL expires. Until then, instances reading through L2 continue to see the revoked role.

The reliable revocation flow on Redis is *reload + update*: after the database write commits, build a fresh `PersonDef` from the post-commit state and call `updatePerson(id, fresh)` so L1, L2, and remote L1s all see the new value at once.

There is one important constraint: **the `updatePerson` call must run after the JPA transaction commits, not inside it.** If you call `updatePerson` from inside an `@Transactional` method that later rolls back, Redis ends up holding a value that never reached the database. `updatePerson` writes directly through `RedisTemplate`, which does not participate in the JPA transaction.

The cleanest implementation is therefore an `ApplicationEventPublisher` event published from the writing service and consumed by an `@TransactionalEventListener(phase = AFTER_COMMIT)`. **Recipe 3 below shows the complete code.**

If reloading the entity is expensive, you can call `notifyPersonChanged` (also after commit) and accept the L2 TTL window of staleness. That is a deliberate trade-off, not the default. The same `update` vs. `notify` choice applies to `updateOrganization` / `notifyOrganizationChanged` and to the role variants.

## Recipe 2: JPA `@PostUpdate` listener (use with caution)

For entities you cannot easily route through a service, you can attach a JPA listener - but only when you can tolerate the in-transaction caveat described below. For most projects, **prefer Recipe 3 (after-commit event)** as the default JPA-side pattern.

```java
@Component
public class PartyRoleAuditListener {

    private static SecurityDataStorage storage;   // resolved lazily; JPA listeners are not Spring beans

    @Autowired
    public void setStorage(SecurityDataStorage storage) {
        PartyRoleAuditListener.storage = storage;
    }

    @PostUpdate
    @PostPersist
    @PostRemove
    public void afterChange(PartyRole entity) {
        if (storage != null) {
            storage.notifyPartyRoleChanged(entity.getId());
        }
    }
}
```

Then bind it to the entity:

```java
@Entity
@EntityListeners(PartyRoleAuditListener.class)
public class PartyRole { ... }
```

JPA listeners run inside the same transaction as the change, which means the notify happens *before* commit. This is the wrong default for authorization data:

- For the in-memory backend, the listener triggers a re-read from `SecurityQueryProvider`. That read sees the **pre-commit** state - perfect if the transaction commits, wrong if it rolls back. After a rollback, the cache holds a value that never reached the database.
- For the Redis backend, the L1 invalidation and the Pub/Sub publish happen inside the JPA transaction. If the transaction rolls back afterwards, you have invalidated caches across every instance for a change that did not happen, leading to a wave of unnecessary L2 reads.

The Redis L2 update through `RedisTemplate` is auto-commit and does not participate in the JPA transaction, but that does not save you - the *invalidation* is what causes the spurious work.

The safer default is **Recipe 3** below: publish a domain event from the writing service and run the notify on `AFTER_COMMIT`. Use Recipe 2 only when you can prove the transaction will commit (or when the cost of a rare spurious invalidation is acceptable).

## Recipe 3: notify from a domain event after commit

`ApplicationEventPublisher` plus `@TransactionalEventListener(phase = AFTER_COMMIT)` keeps the cache update out of the writing transaction:

```java
@Service
public class RoleAssignmentService {

    private final RoleAssignmentRepository repository;
    private final ApplicationEventPublisher events;

    public RoleAssignmentService(RoleAssignmentRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    public void assignRole(Long personId, Long orgId, Long positionRoleId) {
        repository.save(new RoleAssignment(personId, orgId, positionRoleId));
        events.publishEvent(new PersonAuthorizationChanged(personId));
    }

    public record PersonAuthorizationChanged(Long personId) {}
}

@Component
public class PersonAuthorizationChangedListener {

    private final SecurityDataStorage storage;

    public PersonAuthorizationChangedListener(SecurityDataStorage storage) {
        this.storage = storage;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(RoleAssignmentService.PersonAuthorizationChanged event) {
        // The `notify` variant suffices for in-memory and for Redis when
        // TTL-bounded L2 staleness is acceptable. See the `update` variant
        // below for Redis with immediate-freshness requirements.
        storage.notifyPersonChanged(event.personId());
    }
}
```

### Redis variant: `update` after commit

When the deployment is on Redis and the change is a revocation (or any other case where every instance must see the new value before the L2 TTL expires), replace the listener body with a *reload + `updatePerson`*:

```java
@Component
public class PersonAuthorizationChangedListener {

    private final SecurityDataStorage storage;
    private final PersonReloader personReloader;        // your code: builds PersonDef from DB

    public PersonAuthorizationChangedListener(SecurityDataStorage storage,
                                              PersonReloader personReloader) {
        this.storage = storage;
        this.personReloader = personReloader;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(RoleAssignmentService.PersonAuthorizationChanged event) {
        PersonDef fresh = personReloader.loadFresh(event.personId());
        storage.updatePerson(event.personId(), fresh);
    }
}
```

The event listener runs after the JPA transaction commits, so:

- the database holds the post-change state;
- `personReloader.loadFresh` reads that state through your repositories;
- `storage.updatePerson` writes the fresh `PersonDef` to L1 + L2 and publishes the invalidation, so other instances drop their stale L1 and re-read the fresh L2 value.

If the transaction rolls back, the event is never delivered - Redis stays consistent with the database.

The notify only happens if the transaction commits. If the transaction rolls back, the cache is not touched - which is the correct behavior, because the data did not actually change.

## Recipe 4: bulk import / migration

Bulk imports that touch thousands of rows generate thousands of notifies if you wire them per-row. For migrations and ETL jobs, prefer one full `refresh()` call after the import:

```java
@Service
public class OrganizationImporter {

    private final SecurityDataStorage storage;

    public void importAll(List<OrganizationCsvRow> rows) {
        // ... write all rows in a single transaction or batch ...

        // Reload everything once at the end.
        storage.refresh();
    }
}
```

`refresh()` re-runs the loaders and replaces the cache contents wholesale. For the in-memory backend this is one full re-read; for Redis it re-runs preload (so make sure your `CacheWarmer` data loaders are wired). It is more expensive than a notify, but cheaper than 10 000 individual notifies.

## Multi-instance: enable Pub/Sub invalidation

What `notifyXxxChanged` is sufficient for depends on both the backend and the deployment topology:

- **In-memory, single instance.** `notifyXxxChanged` reloads through `SecurityQueryProvider` and the cache is correct after the call. Nothing else is needed.
- **Redis, single instance.** `notifyXxxChanged` clears L1, but L2 still holds the previous value until its TTL expires. If you need the new value visible immediately, use `updateXxx` (see Recipes 1b and 3); otherwise the next read pays an L2 round-trip and serves the old value until TTL.
- **Redis, multiple instances.** Each instance has its own L1, and a notify or update on instance A does not by itself touch instance B's L1. Pub/Sub adds the cross-instance L1 invalidation hop on top of the local-cache behavior described above.

The Pub/Sub channel addresses the multi-instance case only:

When you turn on `invalidation.enabled: true`, every `notify` and every `update` call publishes a small message on `orgsec:invalidation`; subscribers (the other instances) drop their L1 entry for the affected key. Their next read falls through to L2. **The freshness of that read depends on what the originating instance did:**

- If the originating instance called `updateXxx(id, def)`, L2 holds the new value and the remote instance sees it.
- If the originating instance called only `notifyXxxChanged(id)`, L2 is unchanged. Remote instances see whatever was there before, until L2 TTL expires or someone writes a fresh value.

For role-assignment changes that you want every instance to see immediately, prefer `updateXxx` with the freshly-loaded `PersonDef` / `OrganizationDef` / `RoleDef`. Use `notifyXxxChanged` when forcing a TTL-bounded staleness window is acceptable.

```yaml
orgsec:
  storage:
    redis:
      invalidation:
        enabled: true
        async: true
        channel: orgsec:invalidation
```

The cost of Pub/Sub is one Redis publish per notify and one subscription per JVM, which is negligible compared to the cost of stale authorization decisions.

## Multi-tenant Redis: rename the channel

If multiple OrgSec-backed services share a Redis instance, every service hears every other service's invalidations on the default channel. That is wasteful and, in pathological cases, can drop entries that another service does not have.

Rename the channel per service:

```yaml
# Service A
orgsec:
  storage:
    redis:
      invalidation:
        enabled: true
        channel: serviceA.orgsec.invalidation

# Service B
orgsec:
  storage:
    redis:
      invalidation:
        enabled: true
        channel: serviceB.orgsec.invalidation
```

Now each service publishes and subscribes only to its own channel.

## Verifying invalidation works

There are two distinct properties to test, and they need different setups. Test each one separately.

### Test A: L1 invalidation propagates across instances

Confirms that a `notify` on instance A causes instance B to drop its local L1 entry. **Does not** confirm freshness of the value the next read returns; that depends on what L2 holds.

1. Run two instances of your service against the same Redis. Set `com.nomendi6.orgsec.storage.redis.cache` and `com.nomendi6.orgsec.storage.redis.invalidation` to `DEBUG` on both.
2. Hit instance A with a request that loads a `PersonDef` into A's L1.
3. Hit instance B with the same `personId` so it also has the value in its L1.
4. Call `notifyPersonChanged(personId)` from instance A (or trigger the service flow that does).
5. Verify instance B's logs show `Received invalidation message` on the OrgSec invalidation listener and a subsequent L1 invalidation log line for that key.

If instance B does **not** receive the invalidation, check that:

- `invalidation.enabled: true` in both configurations.
- The channel name (`invalidation.channel`) is the same in both.
- The Pub/Sub listener bean is wired (visible in startup logs).

### Test B: an `updateXxx` propagates a fresh value

Confirms that immediate revocation (or any same-cycle freshness requirement) works. This is the test you actually run when validating role-revocation flows.

1. Same setup as Test A (two instances, DEBUG logging, both have the entry in L1).
2. From instance A, call `updatePerson(personId, freshlyLoadedPersonDef)` with a `PersonDef` constructed from the post-change database state.
3. Hit instance B with the same `personId` again. The DEBUG log should show: L1 miss on B (because the invalidation arrived), then an L2 hit, then a populated L1 holding the **fresh** value.
4. Verify the fresh value carries the change you made in step 2 (e.g., the revoked role is gone from `personDef.organizationsMap[orgId].businessRolesMap`).

If instance B's L2 read returns the *old* value, instance A wrote only `notify`, not `update` - revisit Recipe 1b.

## Where to go next

- [Storage / Redis - Pub/Sub invalidation](../storage/03-redis.md#pubsub-invalidation) - the configuration knobs in detail.
- [Cookbook / Securing entities](./02-securing-entities.md) - the entity-side patterns these notifies invalidate.
- [Operations / Production checklist](../operations/production-checklist.md) - multi-instance items.
- [Operations / Monitoring](../operations/monitoring.md) - how to observe notify volume in production.
