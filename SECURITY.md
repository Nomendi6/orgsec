# Security Policy

OrgSec is a security-related library — it implements organizational role-based access control. Vulnerabilities in OrgSec can lead to authorization bypass in any application that depends on it. We treat security reports as our highest priority.

## Supported Versions

The table below shows which OrgSec versions currently receive security fixes. Older versions do not receive backports; users should upgrade to a supported line.

| Version | Supported          | Notes                                                                                                   |
| ------- | ------------------ | ------------------------------------------------------------------------------------------------------- |
| 2.0.x   | :white_check_mark: | Current GA for Spring Boot 4.x and Java 21.                                                             |
| 1.1.x   | :white_check_mark: | Current GA for Spring Boot 3.5.x and Java 17. Redis deployments on `<= 1.0.5` must upgrade here. Mixed 1.0.x / 1.1.x Redis processes are unsupported. |
| 1.0.x   | :x:                | Superseded. **Upgrade to 1.1.0** (or at least 1.0.5 for non-Redis). 1.0.5 is not drop-in - read its migration notes first. |
| < 1.0.0 | :x:                | Pre-release / never published. No support.                                                              |

The 1.1.x line continues to receive security fixes for at least six months after 2.0.0 to give Spring Boot 3 applications time to migrate.

## Reporting a Vulnerability

**Please do not open a public GitHub issue, pull request, or discussion for security problems.** Public reports give attackers a head start before a fix is available.

Use one of the following private channels:

1. **GitHub private vulnerability reporting (preferred).** Open the [Security tab](https://github.com/Nomendi6/orgsec/security) on the OrgSec repository and click *Report a vulnerability*. This creates a private advisory that only OrgSec maintainers can read.
2. **Email.** Send the report to `info@nomendi6.com` with the subject line `[OrgSec security]`. PGP-encrypted mail is welcome but not required.

Please include the following information so we can triage the report quickly:

- Affected version(s) of OrgSec (and Spring Boot, if relevant).
- Affected module (`orgsec-core`, `orgsec-common`, `orgsec-storage-inmemory`, `orgsec-storage-redis`, `orgsec-storage-jwt`, or `orgsec-spring-boot-starter`).
- A description of the vulnerability, its impact, and the conditions required to exploit it.
- A minimal reproduction (configuration snippet, code sample, or failing test) if possible.
- Any suggested mitigation or patch you have in mind.
- Whether you want public credit, and how you want to be named, when the advisory is published.

You should expect an acknowledgement within **3 business days**. If you do not hear back, please re-send the report — mail filters occasionally misroute messages.

## What We Treat as a Vulnerability

The following classes of issues are considered security vulnerabilities in OrgSec and are eligible for the process above:

- **Authorization bypass** — any condition under which a user can read or modify data for which they do not hold the appropriate privilege, business role, or organizational membership.
- **Privilege escalation** — any input that causes OrgSec to evaluate a higher operation level (`READ` -> `WRITE` / `EXECUTE`) or a wider direction (`EXACT` -> `HIERARCHY_DOWN` / `HIERARCHY_UP`) than the configuration grants.
- **Cross-tenant data leakage** — collisions, cache poisoning, or shared-state issues that allow one organization to observe or affect another.
- **Token mishandling** — JWT signature, issuer, audience, or expiry checks that can be bypassed in `orgsec-storage-jwt`, or token contents accepted before validation.
- **Cache / snapshot correctness** — a published authorization view that outlives a source revocation, including the 1.0.x L1/L2 + Pub/Sub path and a 1.1 snapshot that is adopted against the wrong fence version.
- **Insecure defaults** — out-of-the-box configuration that violates the production checklist in [`docs/operations/production-checklist.md`](./docs/operations/production-checklist.md) (once published) and is not loudly documented.
- **Vulnerable transitive dependencies** that OrgSec itself pins or recommends, when no upgrade path is available.

The following are **not** in scope and should be handled through normal GitHub issues:

- Vulnerabilities in your application code that are unrelated to OrgSec's authorization decisions.
- Vulnerabilities in Spring Boot, Spring Security, Redis, Keycloak, or any other upstream project. Report those to the upstream maintainers; if OrgSec needs to bump a dependency in response, open a normal issue here as well.
- Best-practice violations or hardening recommendations that are not exploitable. These are welcome as regular issues or pull requests.
- Denial-of-service through unbounded traffic against a properly sized deployment. We will, however, accept reports of algorithmic complexity attacks (e.g., a single crafted request that wedges the privilege evaluator).

## Our Response Process

Once we receive a credible report, we follow these steps:

1. **Acknowledge** within 3 business days.
2. **Triage** within 7 calendar days — confirm the vulnerability, classify severity using CVSS v3.1, and assign a tracking ID.
3. **Develop a fix** on a private branch. The reporter is kept informed and is invited to review patches before release.
4. **Coordinate disclosure.** We aim to publish a fix and a [GitHub Security Advisory](https://github.com/Nomendi6/orgsec/security/advisories) within **90 days** of the initial report, and sooner for high-severity issues. If a vulnerability is being actively exploited or has been independently disclosed, we will accelerate the timeline.
5. **Request a CVE** through GitHub's CNA, when the issue warrants one.
6. **Release** the fix as a patch version on the affected line (for example `1.0.5`), publish the advisory, and update the [CHANGELOG](./CHANGELOG.md) with a reference to the advisory ID.
7. **Credit the reporter** in the advisory unless they have asked to remain anonymous.

Steps 4-6 describe how **externally reported** vulnerabilities are handled.

Authorization defects the maintainers find during internal review follow a different path: they are fixed and documented in the [CHANGELOG](./CHANGELOG.md) under `### Security`, without a GitHub Security Advisory. No advisory has been published for any release so far, including the security fixes in 1.0.4 and 1.0.5. A GHSA feed or dependency scanner will therefore not flag older OrgSec versions - the CHANGELOG is the notice. If you need an advisory record for a compliance process, open an issue and one will be filed.

This does not apply to anything reported to us: a report from outside goes through the process above, advisory included.

We do not currently run a paid bug-bounty program.

## Hardening Guidance

Even when OrgSec itself is sound, deployment choices affect overall security. Consult [`docs/operations/production-checklist.md`](./docs/operations/production-checklist.md) before going to production. The non-negotiable items are:

- Configure a `JwtDecoder` bean when using `orgsec-storage-jwt` - the library fails fast at startup if one is missing, so this should never go to production without verification.
- Enable TLS for Redis (`orgsec.storage.redis.ssl: true`) and supply credentials via environment variables, not committed YAML.
- Restrict the OrgSec Person API (`orgsec.api.person.enabled: true`) so only Keycloak service accounts can call it (`requiredRole`). Since 1.0.5 the library's `orgsecApiSecurityFilterChain` both authenticates the caller - as a bearer token, validated by *your* application's `JwtDecoder` - and authorizes it with `hasRole(requiredRole)`, mapping Keycloak's `realm_access.roles` to `ROLE_*` itself. Enabling the Person API without a `JwtDecoder` bean now fails at startup rather than leaving the endpoint's protection to whichever chain happens to match it. Restrict the endpoint at the network layer as well.
- Keep Spring Boot, Spring Security, and the Redis client (Lettuce) on supported, patched versions. Subscribe to GitHub Dependabot alerts on your OrgSec-using repository.
- Turn on audit logging through `orgsec.storage.redis.audit.enabled: true` (when the Redis backend is active) or supply your own `SecurityAuditLogger` bean, so authorization decisions are observable post-incident.

## Out-of-Band Communication

If GitHub or the maintainer's email becomes unavailable for an extended period and you have a critical, time-sensitive report, you may post a brief, non-detailed notice on the project's GitHub Discussions asking maintainers to make contact through an alternative channel. Do not include exploit details in any public message.

---

*This policy is reviewed at every minor release. Last reviewed for OrgSec 1.1.0.*
