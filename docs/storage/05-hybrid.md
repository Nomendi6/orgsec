# Hybrid Storage

> **Per-data-type routing does not exist in 1.0.x.** `orgsec.storage.hybrid-mode-enabled` and
> everything under `orgsec.storage.data-sources.*` bind onto `StorageFeatureFlags` and are then read
> by nothing - `getDataSource(...)` has no call sites outside that class. Setting them changes no
> behaviour. Earlier versions of this page described them as a working router; they never were.
>
> What *does* work is the one hybrid topology OrgSec actually implements: the JWT backend answering
> `Person` from the token and forwarding every other type to a single delegate storage. That is what
> the rest of this page documents.

## What the JWT backend actually does

`JwtSecurityDataStorage` is a wrapper, not a router. It has exactly one delegate, used for every
data type it does not serve itself:

| Data type | Served by | Notes |
| --- | --- | --- |
| `person` | the JWT claim on the current request | Parsed per request; a principal cache with a TTL sits in front of it. |
| `organization` | the delegate | Also the authority for `companyId`, name and hierarchy anchors - the token's values are not trusted for these. |
| `role` | the delegate | Position roles named by the claim are looked up here. |
| `privilege` | the delegate | Registered at startup by the application. |

There is no per-type choice: whatever bean is registered as the delegate answers all three.

## Enabling it

```yaml
orgsec:
  storage:
    features:
      jwt-enabled: true
```

`orgsec-storage-jwt` must be on the classpath; if it is not, startup fails with an explicit message
rather than silently falling back.

The delegate defaults to the in-memory storage, published as the bean named
`delegateSecurityDataStorage`. To put your own store behind JWT, declare a bean of that name:

```java
@Bean("delegateSecurityDataStorage")
SecurityDataStorage delegateSecurityDataStorage() {
    return myOwnStorage;
}
```

## Redis behind JWT

Redis is **never** selected as the JWT delegate automatically, even when the Redis backend is
active. It has to be opted into by name:

```java
@Bean("jwtDelegateStorage")
SecurityDataStorage jwtDelegateStorage(RedisSecurityDataStorage redis) {
    return redis;
}
```

Think twice before doing so. The Redis backend does not read through to a database on a miss: it
returns `null`. The JWT backend treats a missing organization as "this membership is not proven" and
drops it. A cold cache after a deployment, or an entry that has simply aged out, therefore does not
degrade into slower authorization - it degrades into **denied** authorization, for every user, until
the cache is repopulated. If you take this route, register `CacheWarmer` loaders and treat cache
population as a startup dependency.

## Missing data

None of these paths create read-through behaviour. If the delegate does not have an organization,
OrgSec receives `null` and denies. If the token carries no valid OrgSec claim, the request is not
authorized. Plan warm-up and invalidation for whichever backend serves the delegate.

Next: [Spring Boot starter](../spring/01-spring-boot-starter.md).
