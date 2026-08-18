# Redis Recovery

This is the 2.0.0 runbook for a managed Redis authorization dataset that is empty, NOT_READY, or serving no GET/LIST. It is not a general Redis DBA guide.

The source database is the authority. Redis holds one published READY snapshot. If that snapshot is gone or unreadable, rebuild it from the database. Do not reconstruct protocol keys by hand.

## Preconditions

- One standalone Redis primary. Cluster, Sentinel and replicas are unsupported.
- `maxmemory-policy noeviction`.
- Every application instance uses the same `orgsec.storage.redis.security-dataset-id`.
- Exactly one `SecurityDatasetFenceStore` and one `RedisSnapshotLoader` in the application.
- You can take a brief write pause on security-data mutations if the fence or Redis is unhealthy.

## Symptoms

| Symptom | Typical cause |
| --- | --- |
| `isReady()` is false and GET/LIST are empty | No READY snapshot, generation mismatch, or Redis unreachable |
| Startup fails with `RedisStorageMigrationRequiredException` | Missing or duplicate fence/loader beans; dependency-only upgrade from `<= 1.0.5` |
| One instance is ready, a peer is not | Peer could not adopt (lease held, fence version newer than published snapshot) |
| All instances deny after a Redis restart | Empty Redis; no snapshot to adopt |
| `LEASE_CHANGED` / staging fence errors in logs | Writer lease expired or another writer published during staging |

## Restore Redis first, if you have a backup

1. Stop or isolate writers if Redis is flapping.
2. Restore the standalone data directory / RDB / AOF with the Redis operator procedure you already use.
3. Confirm `PING` and that `maxmemory-policy` is still `noeviction`.
4. Start **one** application instance. It will either adopt the restored READY snapshot or publish a new one from the database under the fence.
5. Confirm `isReady()` and one known GET (person / privilege).
6. Start the remaining instances. They should adopt without taking the writer lease.

Do not start every instance at once against an empty Redis if you can avoid it. One writer is enough; peers adopt.

## Empty or unreadable Redis, no usable backup

1. Confirm the source database is the version you want to authorize against.
2. Leave Redis empty (or `FLUSHDB` only the OrgSec keyspace if you are certain no other data shares that database).
3. Start one instance. Bootstrap initializes control + FREE lease, loads all six families, and publishes READY.
4. Confirm GET/LIST on that instance.
5. Start peers.

If bootstrap fails closed, fix the fence/loader/database error in the log and retry that single instance. A failed publish does not install a partial view.

## Peer will not adopt

1. Check the peer can reach the same Redis primary and uses the same `security-dataset-id`.
2. Check the source fence version. Adopt requires the published snapshot's content version to match the peer's current fence. If the database moved ahead and no writer published, bounce one writer so it refreshes.
3. If another instance still holds an ACTIVE lease past its expiry, wait for Redis TIME to pass `expiresAtRedisMillis`, then retry. Do not delete the lease key.

## After a revoke that still appears granted

1. Confirm the source row actually changed and that the mutation incremented the fence in the same transaction.
2. Confirm `SecurityEventPublisher` ran after commit (a rollback leaves the previous snapshot).
3. Confirm every instance refreshed or adopted the new generation. A peer that was down during publish adopts on its next bootstrap.
4. If the fence was bypassed (direct SQL, bulk import), increment the fence once under `withLockedFence` and notify so a writer publishes.

## What not to do

- Do not run 1.0.x and 2.0.0 against the same keys.
- Do not enable JWT with Redis.
- Do not call `storage.update*` on managed Redis.
- Do not treat Pub/Sub or L1/L2 TTL as the recovery mechanism.
- Do not lower Redis memory and rely on eviction. Eviction of a protocol key is a deny until rebuild.

## Where to go next

- [Storage / Redis](../storage/03-redis.md)
- [Operations / Troubleshooting](./troubleshooting.md)
- [Operations / Production checklist](./production-checklist.md)
