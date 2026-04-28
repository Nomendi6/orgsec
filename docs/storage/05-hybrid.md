# Hybrid Storage

Hybrid storage routes different OrgSec data types to different backends.

The common setup is:

- current person from JWT
- organizations and roles from Redis or in-memory
- privilege definitions from in-memory

This keeps tokens small while still making user identity stateless.

## Enable Hybrid Mode

```yaml
orgsec:
  storage:
    primary: redis
    features:
      memory-enabled: true
      redis-enabled: true
      jwt-enabled: true
      hybrid-mode-enabled: true
    data-sources:
      person: jwt
      organization: primary
      role: primary
      privilege: memory
```

`hybrid-mode-enabled` is the switch that makes `data-sources` matter. When it is false, `primary` handles every data type.

## Common Topologies

| Topology | Configuration idea |
| --- | --- |
| OAuth2 app, one JVM | `person: jwt`, `organization: memory`, `role: memory`, `privilege: memory` |
| OAuth2 app, many JVMs | `person: jwt`, `organization: redis`, `role: redis`, `privilege: memory` |
| Migration from memory to Redis | `primary: redis`, temporarily route selected data types to `memory` |

## How Routing Is Evaluated

For each read, OrgSec first checks whether hybrid mode is enabled. If it is not, the `primary` backend receives every call. If it is enabled, the `data-sources` entry for the requested data type is used:

| Data type | Typical route | Reason |
| --- | --- | --- |
| `person` | `jwt` | Current user data travels with the request. |
| `organization` | `redis` or `memory` | Organization hierarchy is shared reference data. |
| `role` | `redis` or `memory` | Role assignments need cache invalidation after changes. |
| `privilege` | `memory` | Privilege definitions are usually application startup data. |

`primary` inside `data-sources` means "use whatever `orgsec.storage.primary` names", not "always use memory".

## Missing Data

Hybrid mode does not create read-through behavior. If `organization: redis` and Redis does not contain an organization, OrgSec receives `null` and denies. If `person: jwt` and the token has no valid OrgSec claim, the request is not authorized.

Plan warmup and invalidation for every backend used in the route.

Next: [Spring Boot starter](../spring/01-spring-boot-starter.md).
