# FAQ

## How does OrgSec compare to Casbin, Cerbos, OPA, OpenFGA, or SpiceDB?

OrgSec is narrower. It is strongest when the dominant problem is organization-tree authorization inside a Spring Boot application: company/org/person scopes, hierarchy-down or hierarchy-up privileges, business roles on protected entities, and RSQL list filtering.

Use a broader policy or relationship system when you need a general policy language, centralized PDP, relationship graph across many object types, policy administration UI, or non-Spring enforcement across a large platform.

See [Glossary](./reference/glossary.md) for RBAC, ABAC, ReBAC, PDP, PEP, RSQL, JWT, IdP, and JPA terminology.

| Tool | Strongest fit | OrgSec difference |
| --- | --- | --- |
| Casbin | Flexible local authorization models. | OrgSec is less general but gives a ready-made org hierarchy and RSQL filtering model. |
| Cerbos / OPA | External policy-as-code PDP. | OrgSec runs in the JVM and avoids a network decision call. |
| OpenFGA / SpiceDB | Zanzibar-style relationship graphs. | OrgSec focuses on person -> organization tree -> protected row, not arbitrary graph permissions. |
| Spring Security roles | Coarse endpoint/service gates. | OrgSec adds row-level company/org/person scope and list filtering. |

A grab-bag of questions that come up on the issue tracker. The answers are short by design. For each topic, the deeper material is one or two clicks away - follow the links.

## What problem does OrgSec solve that Spring Security does not?

Spring Security handles *authentication* and *coarse* authorization (`hasRole(...)`, `hasAuthority(...)`, method-level annotations). It does not model:

- Resources owned by an organization in a hierarchy.
- The same user holding different roles in different organizations.
- Privilege scopes that cascade *exactly* -> *down the sub-tree* -> *up the ancestors*.
- A pluggable storage layer for the authorization data.

OrgSec sits on top of Spring Security to fill that gap. The two are designed to compose - OrgSec's `SpringSecurityContextProvider` reads the current user from `SecurityContextHolder`, and `PrivilegeChecker` is callable from `@PreAuthorize` expressions. See [Spring Security Integration](./spring/02-spring-security.md).

## How does OrgSec compare to Spring Security ACL?

| Aspect                | Spring Security ACL              | OrgSec                                                      |
| --------------------- | -------------------------------- | ----------------------------------------------------------- |
| Model unit            | Per-row ACL entry                | Per-privilege grant scoped to an organization               |
| Storage               | JDBC ACL tables                  | Pluggable: in-memory / Redis / JWT                          |
| Hierarchy             | Optional, ACL inheritance        | Required, encoded in pipe-delimited path strings            |
| Read amplification    | One ACL lookup per row           | One privilege evaluation per request, plus cache lookups    |
| Write amplification   | High (every row mutation writes) | Low (privilege definitions change rarely)                   |
| Common deployment     | Document-management style apps   | Multi-tenant SaaS with organizational hierarchy             |

ACL is a good fit when you need per-row control and you do not have an organizational tree. OrgSec is a better fit when "who can see what" can be expressed as "person X holds business role Y inside organization Z, and the entity belongs to organization Z (or a descendant / ancestor)."

## When is OrgSec better than Casbin, Cerbos, OPA, OpenFGA, or SpiceDB?

OrgSec is better when your authorization problem is mostly **organizational data filtering inside a Spring application**:

- the protected data belongs to company/org/person scopes,
- organizations form a hierarchy,
- users have different business roles in different organizations,
- list endpoints need a query predicate, not only an `allow` / `deny` answer,
- you want checks to run locally in the JVM and reuse Spring Boot storage/configuration.

Casbin is more flexible as a general local authorization engine. Cerbos, OPA, and Cedar are stronger when you need centralized policy-as-code and governance. OpenFGA and SpiceDB are stronger when the core model is a relationship graph with complex sharing. OrgSec is stronger when the graph is mainly an organization tree and the application needs a practical, tested way to turn privileges into entity checks and RSQL filters.

## Can I use OrgSec without Spring Security?

Partially. The `orgsec-spring-boot-starter` ships a `SpringSecurityContextProvider` as the default `SecurityContextProvider` bean, but you can override it with your own implementation that resolves the current user from any source. The other modules (`orgsec-core`, `orgsec-common`, `orgsec-storage-*`) do not depend on Spring Security at all.

The exception is `orgsec-storage-jwt`, which uses Spring Security's `JwtDecoder` to validate tokens. If you do not want Spring Security, do not use the JWT backend.

## Does OrgSec work with reactive (WebFlux)?

Not in 1.0.x. The library targets servlet-stack Spring Security and uses `ThreadLocal`-based context (`SecurityContextHolder`, `RequestAttributes`). Porting it to reactive would require replacing the `SecurityContextProvider` SPI with a reactive variant and rethinking the JWT request scope. Reactive support is not on the 2.0.x roadmap either; if you need it, open an issue and describe the use case.

## Which Java version is required?

| OrgSec line | Minimum Java |
| ----------- | ------------ |
| 1.0.x       | Java 17      |
| 2.0.x       | Java 21      |

OrgSec is built and tested against the *minimum*. Newer Java versions (Java 21, Java 25) work as long as your Spring Boot version supports them. There is no upper bound.

## Is OrgSec production-ready?

OrgSec 1.0.x is the first publicly released line. It went through a security review before the 1.0.1 release; the findings (fail-closed cascade, JWT decoder requirement, Person API authorization, Redis TLS guidance) are reflected in the code and in this documentation. Read the [Security Policy](../SECURITY.md) for the disclosure process.

The library does not ship a "production-ready" badge. Read the documentation, the [CHANGELOG](../CHANGELOG.md), and the [Production Checklist](./operations/production-checklist.md), and decide for yourself.

## What is the license?

[Apache License 2.0](../LICENSE). You can use OrgSec in commercial software without royalty. Patches under any license compatible with Apache 2.0 are welcome.

## How do I report a security issue?

Through GitHub's private vulnerability reporting tab on the OrgSec repository, or by email to `info@nomendi6.com` with subject line `[OrgSec security]`. Do not file a public issue. Full process in [SECURITY.md](../SECURITY.md).

## Does OrgSec require Redis or Keycloak?

No. The default in-memory backend has zero infrastructure dependencies. Redis is opt-in for multi-instance deployments. Keycloak is one of several IdPs you might use with the JWT backend; nothing about OrgSec requires Keycloak specifically - the [`orgsec` claim format](./storage/04-jwt.md#the-orgsec-claim) is documented and any IdP that lets you inject a custom claim can produce it.

## Can I add my own storage backend?

Yes. Implement `SecurityDataStorage` and provide a Spring Boot auto-configuration that wires your bean. The full recipe is in [Architecture / Custom storage backend](./architecture/custom-backend.md).

## Why are privileges strings rather than enums?

Enums force a closed set of values. OrgSec is a library; it cannot know in advance what privileges your application needs. String identifiers let each application define its own vocabulary while OrgSec stays generic. The naming convention (`RESOURCE_SCOPE_OPERATION`) gives you a parser that builds the `PrivilegeDef` for free, so the cost of strings vs. enums is small. See [Privileges](./usage/05-privileges.md#naming-convention).

## Why is `business-role` separate from `role`?

A *business role* (`owner`, `customer`, `contractor`) is a category of relationship between a person and an organization. A *position role* (`SHOP_MANAGER`, `BACK_OFFICE_CLERK`) is a concrete duty title that carries privileges. The same business role can be played by many position roles - for example, both `SHOP_MANAGER` and `REGION_DIRECTOR` are tagged as `owner`. The two concepts answer different questions: business role tells the entity which fields to expose; position role tells the person which privileges to carry. See [Core Concepts - Business role vs. position role](./reference/concepts.md#business-role-vs-position-role).

## Why does the privilege model have three direction values for *each* scope?

Real organizational structures have three meaningful relationships: at this exact node, at this node and any descendant, at this node and any ancestor. Most authorization decisions reduce to one of those three. Adding an "all" shortcut covers super-users; `NONE` covers "this scope does not apply." Five values per scope - `NONE`, `EXACT`, `HIERARCHY_DOWN`, `HIERARCHY_UP`, `ALL` - cover every case we found in real applications without making the model larger than needed.

## Does OrgSec write to my database?

No. OrgSec **reads** from your database through the `SecurityQueryProvider` you implement, and caches the results in memory or Redis. It does not own the schema; you are free to model `Person`, `Organization`, `Role` rows however your application needs them, as long as the loader can produce the JPA `Tuple` shapes OrgSec expects. The exact shapes are documented in [Storage / In-memory](./storage/02-in-memory.md).

## How do I keep the cache fresh when role assignments change?

The exact call depends on your storage backend:

- **In-memory.** Call `notifyXxxChanged(...)` from the place where the change happens. The backend reloads the affected entity through `SecurityQueryProvider`, so the cache is correct on the next request.
- **Redis.** `notifyXxxChanged` only invalidates L1 and publishes the invalidation event - it does *not* refresh L2. For immediate freshness (typical revocation flows), reload the entity from your database after commit and call `updateXxx(id, freshDef)` instead; the Redis backend writes through to L1 + L2 and publishes the invalidation. Reserve `notify` for cases where TTL-bounded staleness is acceptable.

There is no JPA listener wired by default. The recipes with concrete patterns - including the in-memory and Redis variants of the service-method approach - are in [Usage / Load security data](./usage/08-load-security-data.md).

## Where do I ask for help?

Open a [GitHub Discussion](https://github.com/Nomendi6/orgsec/discussions) for questions about usage. Open an [issue](https://github.com/Nomendi6/orgsec/issues) for bug reports and feature requests. Use the [Security Policy](../SECURITY.md) for security problems.
