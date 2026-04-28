# Production Checklist

This page is the pre-deployment checklist for an OrgSec-backed application. Each row is a *Yes / No* item with a short rationale; the deeper material is one click away.

The checklist mirrors the security review that 1.0.1 went through. If your deployment fails any of the **Mandatory** rows, treat that as a release blocker. The **Recommended** rows are not blockers, but skipping them shifts incident-response cost on to you.

Use this list as a PR-template checkbox set or as a sign-off document during a deployment review.

## Mandatory

| # | Item | Rationale | Reference |
| --- | ---- | --------- | --------- |
| 1 | `JwtDecoder` bean is present **before** turning on `orgsec.storage.features.jwt-enabled` | Without one, OrgSec fails fast at startup. Worse: if you bypass the fail-fast, every request gets through unauthenticated. | [Storage / JWT](../storage/04-jwt.md#configuring-the-jwtdecoder) |
| 2 | `JwtDecoder` enforces `iss`, `exp`, and `aud` | Spring Security validates `iss` and `exp` automatically. Configure audience validation explicitly - it is not on by default. | [Storage / JWT - Defense in depth](../storage/04-jwt.md#defense-in-depth) |
| 3 | `orgsec.storage.redis.ssl: true` in any non-dev environment | Authorization data on the wire in plaintext is a single packet capture away from being a breach. | [Storage / Redis - Connection settings](../storage/03-redis.md#connection-settings) |
| 4 | Redis password supplied through environment variable, not committed YAML | Committed credentials end up in CI logs, container images, and forks. | [Storage / Redis](../storage/03-redis.md) |
| 5 | If Person API is enabled (`orgsec.api.person.enabled: true`), Spring Security authority `ROLE_<requiredRole>` is required by your filter chain | Default chain uses `hasRole(...)`, which prepends `ROLE_`. An IdP that emits unprefixed authorities will return 403 on legitimate calls and 200 if you also disable the chain. | [Keycloak Person API](../spring/03-keycloak-person-api.md) |
| 6 | If Person API is enabled, the endpoint is reachable only from your IdP's network (firewall / network policy) | The endpoint exposes person and organization data; treat it like a service-to-service API. | [Storage / JWT](../storage/04-jwt.md#issuing-the-claim-from-keycloak) |
| 6a | If Person API is consumed by Keycloak's mapper with `bearer` auth, you have a documented credential-rotation plan | The mapper does not refresh OAuth2 tokens itself; an expired bearer breaks every login until rotated. Prefer `api-key` with infrastructure-managed rotation, or operate a daily-rotation process for the bearer token. | [Keycloak Person API - Choosing the auth type](../spring/03-keycloak-person-api.md#choosing-the-auth-type) |
| 7 | Spring Boot, Spring Security, and Lettuce on currently supported, patched versions | OrgSec inherits CVE exposure from these. Subscribe to GitHub Dependabot alerts. | [`pom.xml` parent versions](https://github.com/Nomendi6/orgsec/blob/main/pom.xml) |
| 8 | `PropertiesDocumentationCoverageTest` (or your own equivalent integration test) passes against the deployment configuration | Catches drift between `@ConfigurationProperties` fields and any property reference your team relies on. | [Properties Reference](../reference/properties.md) |

## Recommended

| # | Item | Rationale | Reference |
| --- | ---- | --------- | --------- |
| 9 | Audit logging enabled. With the Redis backend, set `orgsec.storage.redis.audit.enabled: true`; without Redis, supply your own `SecurityAuditLogger` bean. (Note: `orgsec.security.audit-logging` is reserved in 1.0.x and does not control audit logging.) | Authorization decisions are observable post-incident through `DefaultSecurityAuditLogger` and MDC keys. | [Operations / Monitoring](./monitoring.md) |
| 10 | Audit log routed to a dedicated appender / log stream | Prevents audit events from being lost in the noise of application INFO logs. | [Operations / Monitoring](./monitoring.md#audit-logging) |
| 11 | Multi-instance deployments enable Pub/Sub invalidation (`orgsec.storage.redis.invalidation.enabled: true`) on a unique channel name | Without it, L1 caches on different instances drift after `notifyXxxChanged` calls. | [Storage / Redis - Pub/Sub invalidation](../storage/03-redis.md#pubsub-invalidation) |
| 12 | `orgsec.storage.redis.preload.enabled: true` *and* `CacheWarmer` data loaders registered | First request after deploy does not pay the cold-cache cost. Without registered loaders, preload is a no-op. | [Storage / Redis - Preload strategies](../storage/03-redis.md#preload-strategies) |
| 13 | Spring Boot Actuator on the classpath, `/actuator/health` wired into your readiness probe | Surfaces Redis connectivity through the OrgSec health indicator. | [Storage / Redis - Health and monitoring](../storage/03-redis.md#health-and-monitoring) |
| 14 | A regression test that loads the production-like configuration and exercises one privilege check end-to-end | Catches typos in `application.yml`, missing beans, and broken `SecurityQueryProvider` queries. | [First Working Example](../start-here/04-first-working-example.md) |
| 15 | Domain code invalidates or updates OrgSec caches on every authorization-relevant write. **For Redis specifically: use `updateXxx(id, freshDef)` when immediate freshness is required (revocations); reserve `notifyXxxChanged` for cases where TTL-bounded L2 staleness is acceptable.** | Stale cache after revocation is the most common authorization bug in OrgSec deployments. On Redis, `notify` alone leaves the old value in L2 until TTL expiry. | [Usage / Load security data](../usage/08-load-security-data.md) |
| 16 | Path columns (`*_PATH`) on `SecurityEnabledEntity` rows are denormalized on write | A null path with a hierarchical privilege fails closed (correct) but produces "manager cannot see new document" support tickets. | [Usage / Security-enabled entity - path denormalization](../usage/01-security-enabled-entity.md) |

## Optional but useful

| # | Item | Rationale | Reference |
| --- | ---- | --------- | --------- |
| 17 | Token TTLs on the IdP are short (5-15 min), refresh tokens longer | Reduces the window in which a revoked role still passes JWT-backed authorization. | [Storage / JWT - Limitations](../storage/04-jwt.md#limitations) |
| 18 | Distinct Pub/Sub channel per service if Redis is shared (`orgsec.storage.redis.invalidation.channel`) | Avoids accidental cross-service cache drops. | [Storage / Redis - Pub/Sub invalidation](../storage/03-redis.md#pubsub-invalidation) |
| 19 | `obfuscate-keys: true` on shared Redis | Prevents Redis namespace inspection from leaking organizational metadata. | [Storage / Redis - L1 cache](../storage/03-redis.md#l1-cache) |
| 20 | Documented runbook for "stale authorization data" incidents | When a revoked role still passes a check, the first reaction is to flush caches; document which `notifyXxxChanged` or `updateXxx` call (or service-flow path) to use, including the Redis-specific guidance that `update` is required for immediate L2 freshness. | [Operations / Troubleshooting](./troubleshooting.md) |

## Sign-off template

A PR description that uses this list:

```markdown
## OrgSec production-readiness sign-off

Mandatory:
- [ ] 1. JwtDecoder configured before jwt-enabled
- [ ] 2. iss / exp / aud validated by JwtDecoder
- [ ] 3. Redis SSL on
- [ ] 4. Redis password from env
- [ ] 5. Person API ROLE_ prefix verified
- [ ] 6. Person API network-restricted
- [ ] 6a. Person API mapper credential rotation plan documented
- [ ] 7. SB / Spring Sec / Lettuce on supported versions
- [ ] 8. Properties coverage / config integration test passes

Recommended:
- [ ] 9-16. (see docs/operations/production-checklist.md)

Optional:
- [ ] 17-20.

Sign-off: <name>, <date>
```

If any **Mandatory** box is unchecked, the deployment does not go to production.

## Where to go next

- [Operations / Monitoring](./monitoring.md) - what to watch in production.
- [Operations / Troubleshooting](./troubleshooting.md) - runbook for common problems.
- [Storage / Redis](../storage/03-redis.md) - Redis-specific tuning.
- [Storage / JWT](../storage/04-jwt.md) - JWT-specific hardening.
- [Security Policy](../../SECURITY.md) - vulnerability disclosure.
