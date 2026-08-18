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

**Cause.** `orgsec.storage.redis.enabled` is not `true`. It is the only flag that activates the backend, and it is easy to confuse with the similarly named `features.redis-enabled`.

**Fix.** Set both, to the same value:

```yaml
orgsec:
  storage:
    features:
      redis-enabled: true         # in-memory stands down from @Primary
    redis:
      enabled: true               # gates RedisStorageAutoConfiguration
```

`redis.enabled` gates `RedisStorageAutoConfiguration` through `@ConditionalOnProperty`; without it no Redis bean is created. `features.redis-enabled` activates nothing on its own - it only stops the in-memory storage claiming `@Primary`. Since 1.0.5 a disagreement between them refuses startup. See [Storage / Redis - Activation](../storage/03-redis.md#activation).

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
3. **The authorization view is stale.** On Redis 1.1, a mutation that skipped the dataset fence or that notified before commit can leave the previous READY snapshot in place. Call `SecurityEventPublisher` after commit and increment the fence in the same database transaction (see [Usage / Load security data](../usage/08-load-security-data.md)).
4. **`personId` mismatch.** If `SecurityContextProvider.getCurrentUserLogin()` returns the OAuth2 `sub` (a UUID) and your `PersonDataProvider` looks up by login (a username), the lookup may fail silently and yield no `PersonDef`. Add a debug log to check what is actually being passed.
5. **`anonymousUser` slipped through.** Spring Security populates `Authentication` with principal `"anonymousUser"` for `permitAll()` paths. `SpringSecurityContextProvider` filters this out, but a custom provider may not. Replicate the filter (`!"anonymousUser".equals(principal)`).

### `AccessDeniedException` from `RsqlFilterBuilder` on a list endpoint

**Cause 1 - the caller genuinely has no privilege.** The fail-closed behavior added in 1.0.1: a caller with no read privileges on the resource can no longer get an empty / over-permissive filter.

**Fix.** This is intentional. Check the privilege definitions and role assignments. If you see it on a known-good user, look for a stale `PersonDef` in the cache (see "Stale auth data" below).

**Cause 2 - the `ResourceDef` has an aggregate but no privileges list.** Since 1.0.4 / 2.0.0 the filter is built from `ResourceDef.getPrivilegesList()`; an empty or absent list fails closed even when an aggregated privilege is set. Every path inside the library populates both, so this only appears when application code constructs a `ResourceDef` itself.

**Fix.** Populate the list, not just the aggregate - see the Migration Notes for 1.0.4 / 2.0.0. On the 1.0.x line a compatibility fallback still honours an aggregate-only object and logs a warning naming the resource; that fallback is gone in 2.x.

**Cause 3 - the query was restricted to business roles the caller does not hold.** `buildRsqlFilterForPrivileges` honours its `allowedBusinessRoles` argument since 1.0.4 / 2.0.0; it was previously accepted and ignored. `buildRsqlFilterForBasicPrivileges` restricts to `owner`, so a caller whose privilege sits on another business role is now correctly denied.

**Fix.** Confirm the caller holds the privilege on one of the allowed roles, or call `buildRsqlFilterForPrivileges` with a wider list (`null` means "any business role").

### `_COMPHD` privilege grants too much

**Cause.** The caller's `companyParentPath` is shorter than expected, so the prefix matches more rows than you intended.

**Fix.** Verify the caller's `OrganizationDef.companyParentPath` in the cached `PersonDef`. Hierarchy-down means "this organization and any descendant" - if the caller is anchored at the root company, that is the entire tree by design.

## Snapshot / notify issues

### A peer instance stays NOT_READY

**Cause.** It could not adopt the READY snapshot (wrong `security-dataset-id`, Redis unreachable, or the fence version is newer than the published snapshot) or another instance still holds the writer lease.

**Fix.** Confirm the same dataset id and Redis primary, then follow [Redis recovery](./redis-recovery.md). Do not delete the lease key.

### Remote instances still grant a revoked role

**Cause.** The mutation did not increment the dataset fence, or notify ran inside a transaction that rolled back, or a peer has not refreshed since the new generation was published.

**Fix.** Increment the fence in the same database transaction as the source change and call `SecurityEventPublisher` after commit. Bounce a writer if the fence moved and no snapshot was published. `update*` is rejected on managed Redis.

### `notifyXxxChanged` not called on a domain change

**Symptom.** A role assignment does not show up until an instance is restarted.

**Fix.** Find the place in domain code that mutates the underlying data and call the matching `SecurityEventPublisher` producer method after commit. The most common offenders are admin endpoints that bypass the service layer and bulk-import jobs.

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

### GET/LIST empty after Redis restart

**Cause.** The READY snapshot is gone. 1.1.0 does not fall through to L1/L2 leftovers.

**Fix.** [Redis recovery](./redis-recovery.md): start one writer so it publishes from the database, then start peers.

## Person API issues

### Person API returns `404 Not Found`

**Cause.** Either the endpoint is not enabled or the `userId` you passed has no `Person` row mapped to it.

**Fix.**

1. Verify `orgsec.api.person.enabled: true` and `PersonApiController` is in the bean graph (look for `Mapped "{[/api/orgsec/person/by-user/{userId}],methods=[GET]}"` in the startup log).
2. Check that `PersonDataProvider.findByUserId(<keycloakUuid>)` returns a value for the user. Log the lookup if needed.
3. Confirm the URL is correct: `/api/orgsec/person/by-user/{userId}`, not `/api/orgsec/persons/{userId}`.

### Person API returns `403 Forbidden` from Keycloak's mapper

**Cause.** The mapper's service-account principal does not carry the authority `ROLE_<requiredRole>`.

**Fix.** Since 1.0.5 the OrgSec chain maps Keycloak's `realm_access.roles` to `ROLE_*` itself, so no converter is needed on your side - a `403` means the token genuinely lacks the role. Assign the realm role (default `ORGSEC_API_CLIENT`) to the mapper client's **service account**, not just to the client, and confirm it appears in `realm_access.roles` of the issued token. See [Keycloak Person API](../spring/03-keycloak-person-api.md).

### Person API returns `401 Unauthorized`

**Cause.** No bearer token, or one your application's `JwtDecoder` rejects - expired, wrong issuer, or wrong audience.

**Fix.** The Person API chain validates the token with **your application's** decoder, so a token the rest of your API accepts is accepted here too, and one it rejects is rejected here too. Decode the mapper's token and compare `iss` and `aud` against what your decoder enforces (with JHipster, that is `jhipster.security.oauth2.audience`, not `spring.security.oauth2.resourceserver.jwt.audiences`). If the token has simply expired, check that the mapper is using `client_credentials` rather than a static bearer pasted into its configuration.

### Application fails to start: `no JwtDecoder bean is present`

**Cause.** `orgsec.api.person.enabled: true` without a resource server. Since 1.0.5 this is refused at startup rather than leaving the endpoint's protection to whichever chain happens to match it.

**Fix.** Add `org.springframework.boot:spring-boot-starter-oauth2-resource-server` (the OrgSec starter declares it as `optional`) and configure a `JwtDecoder` - either your own bean or `spring.security.oauth2.resourceserver.jwt.issuer-uri`. If the Person API is not actually in use, set `orgsec.api.person.enabled: false`.

### Application fails to start: `ORGSEC_STORAGE_REDIS_ACTIVATION_MISMATCH`

**Cause.** `orgsec.storage.redis.enabled` and `orgsec.storage.features.redis-enabled` disagree. Applications generated against 1.0.4 emit exactly this combination, so it commonly appears on the first boot after upgrading.

**Fix.** Set both flags to the same value. Only `orgsec.storage.redis.enabled` activates the backend; `features.redis-enabled` decides whether the in-memory storage keeps `@Primary`. There is no property that softens this check - it was never a working configuration, and continuing would leave a different storage serving authorization than you selected.

### Application fails to start: `ORGSEC_STORAGE_JWT_REDIS_UNSUPPORTED`

**Cause.** `orgsec.storage.features.jwt-enabled` and `orgsec.storage.redis.enabled` are both `true`.

**Fix.** Enable exactly one. The JWT backend forwards organizations and roles to its delegate and reads a missing organization as an unproven membership, so a cache behind it denies every request until it is warm. See [Hybrid storage](../storage/05-hybrid.md).

### Application fails to start: `ORGSEC_STORAGE_REDIS_INVALID_PROPERTY`

**Cause.** A value under `orgsec.storage.redis.*` is outside its permitted range - the message names the property and the requirement.

**Fix.** Correct the value. Note that before 1.0.5 these were Bean Validation constraints, which required a validation provider on the classpath; an application without one failed with `NoProviderFoundException` instead. If you added `spring-boot-starter-validation` only to work around that, you can drop it again.

### Application fails to start: `ORGSEC_STORAGE_JWT_MODULE_REQUIRED` / `ORGSEC_STORAGE_REDIS_MODULE_REQUIRED`

**Cause.** A backend is enabled by property but its module is not on the classpath.

**Fix.** Add `com.nomendi6.orgsec:orgsec-storage-jwt` or `com.nomendi6.orgsec:orgsec-storage-redis`, or turn the flag off. Before 1.0.5 this started up with no primary storage at all, or with a placeholder bean that denied every lookup.

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
