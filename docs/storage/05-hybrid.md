# Hybrid Storage

`orgsec.storage.primary`, `fallback`, `features.hybrid-mode-enabled` and `data-sources.*` **bind onto `StorageFeatureFlags` and are inert**. No per-type router exists. JWT always delegates org/role/privilege to `jwtDelegateStorage` (in-memory by default). JWT+Redis is refused at startup.

The YAML below is what older generated apps still emit. It is harmless (the keys bind) and has no effect on backend selection.

```yaml
orgsec:
  storage:
    primary: jwt
    features:
      memory-enabled: true
      jwt-enabled: true
      hybrid-mode-enabled: true
    data-sources:
      person: jwt
      organization: primary
      role: primary
      privilege: memory
```

Live switches:

| Goal | Set |
| --- | --- |
| In-memory | defaults |
| Redis snapshot | `orgsec.storage.redis.enabled=true` and `orgsec.storage.features.redis-enabled=true` plus `security-dataset-id`, fence store and loader |
| JWT Person | `orgsec.storage.features.jwt-enabled=true` |

## Next

- [Choose storage](./01-choose-storage.md)
- [JWT storage](./04-jwt.md)
- [Redis storage](./03-redis.md)
