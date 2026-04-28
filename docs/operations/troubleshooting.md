# Troubleshooting

This page is the runbook for the operational problems that come up most often with OrgSec deployments. Each entry has a symptom, the most likely cause, and a fix. The order matters: the items at the top are the ones we have seen most often in real applications.

If your problem is not here, open a [GitHub Discussion](https://github.com/Nomendi6/orgsec/discussions) with the symptom, the OrgSec version, the active backend, and a redacted excerpt of your `application.yml`.

## Application fails to start

### `IllegalStateException: orgsec.storage.features.jwt-enabled=true requires a JwtDecoder bean`

**Cause.** You enabled the JWT backend without supplying a `JwtDecoder`. This is the deliberate fail-fast added in 1.0.1 - an OrgSec deployment that accepts unverified tokens is a critical security regression.

**Fix.** Either:

- Configure Spring Security's resource server: `spring.security.oauth2.resourceserver.jwt.issuer-uri: <your IdP>` (Spring Security creates the `JwtDecoder` automatically), or
- Provide a `JwtDecoder` bean manually (typical in tests with `NimbusJwtDecoder.withSecretKey(...)`).

See [Storage / JWT - Configuring the JwtDecoder](../storage/04-jwt.md#configuring-the-jwtdecoder).

### `IllegalArgumentException: Malformed privilege name: ...`

**Cause.** The privilege identifier passed to `PrivilegeLoader.createPrivilegeDefinition` is missing the structural shape the parser requires: two underscore separators producing `RESOURCE_SCOPE_OPERATION`. The parser is **permissive about content** - unknown scope tokens, unknown operation suffixes, and lowercase identifiers do **not** trigger this exception. They are silently accepted and produce a privilege that grants nothing (scope tokens that no `case` matches leave the directions at `NONE`; unknown operation suffixes resolve to `PrivilegeOperation.NONE`). The exception fires only on *structural* problems - for example, a literal `DOCUMENTREAD` (no underscore) or `DOCUMENT_R` (only one underscore).

**Fix for the structural exception.** Add the missing underscore(s) so the identifier has the `A_B_C` shape.

**Fix for "I changed an identifier and now privilege checks silently fail".** This is the more common production symptom. Check that the scope token is exactly one of `ALL`, `COMP`, `COMPHD`, `COMPHU`, `ORG`, `ORGHD`, `ORGHU`, `EMP` (uppercase, exact match) and the operation suffix is exactly `R`, `W`, or `E`. Anything else is accepted at registration but produces a privilege that never matches.

If the identifier intentionally diverges from the convention, register the `PrivilegeDef` directly through `PrivilegeRegistry.registerPrivilege(...)` rather than through the loader's parser.

### Redis backend not active despite the JAR being on the classpath

**Cause.** Redis activation requires three flags. Setting only one or two leaves Redis disabled.

**Fix.** Verify all three are present in your configuration:

```yaml
orgsec:
  storage:
    primary: redis
    features:
      redis-enabled: true
    redis:
      enabled: true
```

The `redis.enabled: true` flag gates `RedisStorageAutoConfiguration` through `@ConditionalOnProperty`; without it no Redis bean is created. See [Storage / Redis - Activation](../storage/03-redis.md#activation).

### Two `SecurityDataStorage` beans and `NoUniqueBeanDefinitionException`

**Cause.** OrgSec's bundled backends pick `@Primary` based on the active feature flags (`primaryInMemoryStorage` is `@Primary` only when JWT and Redis are both disabled; the Redis and JWT backends become `@Primary` when their flags activate). With only the bundled backends, this exception is rare. The realistic triggers are:

- A custom `SecurityDataStorage` bean defined in your application without `@Primary` while one of OrgSec's backends is also active.
- Two custom `SecurityDataStorage` beans, both without `@Primary`.
- Mixing a community-contributed storage backend with an OrgSec-bundled one without coordinating which is `@Primary`.

**Fix.** Mark exactly one `SecurityDataStorage` bean as `@Primary`, or constrain the others with `@Qualifier("...")` so they are not eligible for primary injection. If you want OrgSec's bundled backend to win, leave it as the only `@Primary` bean and remove `@Primary` from the others. See [Choose storage](../storage/01-choose-storage.md) and [Architecture / Auto-configuration](../architecture/auto-configuration.md#override-patterns).

## Privilege checks return `false` unexpectedly

### Caller has the role but the check still denies

Most common causes, in order:

1. **The path columns on the entity are null.** A `_COMPHD` / `_ORGHD` privilege fails closed when the entity returns `null` for `COMPANY_PATH` / `ORG_PATH`. Check that your `getSecurityField(role, COMPANY_PATH)` returns the pipe-delimited path (`|1|10|22|`), not `null` - and that you denormalize the path on entity write.
2. **The business role's `supported-fields` list is wrong.** A role declared as `supported-fields: [COMPANY]` cannot evaluate hierarchical privileges - OrgSec will not even ask for `COMPANY_PATH`. See [Business roles](../usage/04-business-roles.md).
3. **The cache holds a stale `PersonDef`.** Especially likely on Redis - calling `notifyPersonChanged` only invalidates L1, not L2. For revocations use `updatePerson` after commit (see [Usage / Load security data](../usage/08-load-security-data.md)).
4. **`personId` mismatch.** If `SecurityContextProvider.getCurrentUserLogin()` returns the OAuth2 `sub` (a UUID) and your `PersonDataProvider` looks up by login (a username), the lookup may fail silently and yield no `PersonDef`. Add a debug log to check what is actually being passed.
5. **`anonymousUser` slipped through.** Spring Security populates `Authentication` with principal `"anonymousUser"` for `permitAll()` paths. `SpringSecurityContextProvider` filters this out, but a custom provider may not. Replicate the filter (`!"anonymousUser".equals(principal)`).

### `AccessDeniedException` from `RsqlFilterBuilder` on a list endpoint

**Cause.** The fail-closed behavior added in 1.0.1: a caller with no read privileges on the resource can no longer get an empty / over-permissive filter.

**Fix.** This is intentional - the user genuinely has no privileges. Check the privilege definitions and role assignments. If you see this on a known-good user, look for a stale `PersonDef` in the cache (see "Stale auth data" below).

### `_COMPHD` privilege grants too much

**Cause.** The caller's `companyParentPath` is shorter than expected, so the prefix matches more rows than you intended.

**Fix.** Verify the caller's `OrganizationDef.companyParentPath` in the cached `PersonDef`. Hierarchy-down means "this organization and any descendant" - if the caller is anchored at the root company, that is the entire tree by design.

## Cache invalidation issues

### Cache invalidation does not propagate across instances

**Cause.** `invalidation.enabled: false` (the default), or different channel names, or no Pub/Sub listener wired.

**Fix.** On every instance:

```yaml
orgsec:
  storage:
    redis:
      invalidation:
        enabled: true
        channel: orgsec:invalidation     # must be identical across instances
```

Then turn on `DEBUG` logging for `com.nomendi6.orgsec.storage.redis.invalidation` and verify the receiving instance logs `Received invalidation message` after the publishing instance's `notify` / `update`.

### Remote instances see stale data after `notifyPersonChanged`

**Cause.** `notify` clears L1 across instances but does **not** refresh L2. Remote instances re-read from L2 and see the *old* value.

**Fix.** For revocations and other immediate-freshness changes, use `updatePerson` (write-through) after the database commit. See [Usage / Load security data - Recipe 3 Redis variant](../usage/08-load-security-data.md).

### `notifyXxxChanged` not called on a domain change

**Symptom.** A role assignment does not show up until the cache is restarted or TTL expires.

**Fix.** Find the place in domain code that mutates the underlying data and add the corresponding notify (or, for Redis with immediate freshness, an `update` after commit). The most common offenders are admin endpoints that bypass the service layer and bulk-import jobs.

## Redis-specific issues

### Circuit breaker stuck in `OPEN`

**Cause.** Redis was unreachable long enough for the circuit to open, and either the network has not recovered or the circuit has not retried yet.

**Fix.**

1. Verify Redis is reachable (`redis-cli -h <host> -p <port> ping`).
2. Wait for `wait-duration` (default 30 seconds); the circuit transitions to half-open and probes.
3. If the probes succeed, the circuit closes automatically. If they fail, the circuit re-opens for another `wait-duration`.

If the circuit stays open forever, your Redis password / TLS / network is misconfigured - check the application logs for the underlying exception.

### `Could not acquire connection in time` under load

**Cause.** Lettuce connection pool exhausted.

**Fix.** Increase `orgsec.storage.redis.pool.max-active` to match your peak concurrent OrgSec calls. Default is 20; high-traffic services typically run 50-200.

### L1 is bigger than expected (memory pressure)

**Cause.** `cache.l1-max-size` is set per cache type. Four caches (persons, organizations, roles, privileges) each at the configured size means a JVM with `l1-max-size: 10000` can hold 40 000 entries total.

**Fix.** Lower `l1-max-size`, or increase JVM heap. The defaults (1000 per type) are sized for small-to-medium deployments.

## Person API issues

### Person API returns `404 Not Found`

**Cause.** Either the endpoint is not enabled or the `userId` you passed has no `Person` row mapped to it.

**Fix.**

1. Verify `orgsec.api.person.enabled: true` and `PersonApiController` is in the bean graph (look for `Mapped "{[/api/orgsec/person/by-user/{userId}],methods=[GET]}"` in the startup log).
2. Check that `PersonDataProvider.findByUserId(<keycloakUuid>)` returns a value for the user. Log the lookup if needed.
3. Confirm the URL is correct: `/api/orgsec/person/by-user/{userId}`, not `/api/orgsec/persons/{userId}`.

### Person API returns `403 Forbidden` from Keycloak's mapper

**Cause.** The mapper's service-account principal does not carry the authority `ROLE_<requiredRole>`.

**Fix.** Spring Security's `hasRole(...)` prepends `ROLE_` automatically. With the default `required-role: ORGSEC_API_CLIENT`, the principal must have `ROLE_ORGSEC_API_CLIENT`. Configure a `JwtAuthenticationConverter` that produces `ROLE_*` authorities from your IdP's claims (typically by reading `realm_access.roles` and prefixing). See [Keycloak Person API](../spring/03-keycloak-person-api.md) and [Spring Security Integration - Authority vs role](../spring/02-spring-security.md#authority-vs-role).

### Person API returns `401 Unauthorized`

**Cause.** No valid JWT on the request.

**Fix.** Verify the mapper's static bearer token is still valid (the mapper does not refresh it). If it has expired, paste a fresh token into the mapper config. For long-running deployments consider switching to `api-key` auth with infrastructure-managed rotation - see [Keycloak Person API - Choosing the auth type](../spring/03-keycloak-person-api.md#choosing-the-auth-type).

## JWT-specific issues

### `JwtException: Signed JWT rejected`

**Cause.** The token's signature does not validate against the configured key set.

**Fix.**

- For Spring Security's auto-configured decoder: confirm `spring.security.oauth2.resourceserver.jwt.issuer-uri` points at your IdP and the JWKS endpoint is reachable.
- For a manual `JwtDecoder` (test setups): confirm the secret / key matches the one used to sign the token.
- Clock drift - very large skew between client and IdP makes `nbf` / `exp` checks fail. Sync the JVM clock.

### `Missing OrgSec claims in token`

**Cause.** Spring Security accepted the token but the OrgSec `orgsec` claim is not present.

**Fix.**

1. Decode the JWT (`echo $TOKEN | cut -d '.' -f 2 | base64 -d | jq .`) and confirm the `orgsec` claim is in the payload.
2. If the claim is missing, the OrgSec mapper either failed (check Keycloak server logs for `OrgSecProtocolMapper` warnings) or is not attached to the active client scope.
3. Verify `orgsec.storage.jwt.claim-name` matches the mapper's "Claim Name" config.

## Stale authorization data

### A revoked role still passes the check after the database commit

**Most common path:**

1. The cache (in-memory or Redis) still holds the old `PersonDef`.
2. For Redis, the L1 was invalidated but L2 was not refreshed.
3. The revocation flow did not call any notify or update.

**Fix sequence:**

- For **in-memory**, a single `notifyPersonChanged(personId)` call after commit (Recipe 3 from Cache invalidation) reloads the entity.
- For **Redis**, replace the notify with a reload + `updatePerson(personId, fresh)` call from an `AFTER_COMMIT` listener. The reload should happen *after* the JPA transaction commits to avoid pre-commit / rollback races. See [Usage / Load security data - Redis variant](../usage/08-load-security-data.md).
- For an immediate emergency fix without a code change, restart the affected JVM (in-memory) or wait for the L2 TTL (Redis); both are last-resort options.

### After role change, only some instances see the new value

**Cause.** Pub/Sub invalidation is off, or the channel name differs across instances, or one instance is calling `notify` and another expects `update` semantics.

**Fix.** See "Cache invalidation does not propagate across instances" above.

## Where to go next

- [Operations / Production checklist](./production-checklist.md) - preventive items.
- [Operations / Monitoring](./monitoring.md) - what to watch.
- [Usage / Load security data](../usage/08-load-security-data.md) - the canonical reference for `notify` / `update` semantics.
- [Configuration](../reference/properties.md) - full property reference.
