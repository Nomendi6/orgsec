# Glossary

This page explains the abbreviations used in the OrgSec documentation and in comparisons with other authorization tools.

## Authorization Models

| Abbreviation | Full name | Meaning in practice | OrgSec relation |
| --- | --- | --- | --- |
| **RBAC** | Role-Based Access Control | Access is granted through roles, such as `ADMIN`, `MANAGER`, or `SHOP_EMPLOYEE`. | OrgSec uses role-like grants, but scopes them to organizations and business roles instead of treating every role as global. |
| **ABAC** | Attribute-Based Access Control | Access depends on attributes of the user, resource, action, or environment, such as department, risk level, document status, or time. | OrgSec has ABAC-like parts because entity fields (`COMPANY`, `ORG`, `PERSON`, paths) affect decisions, but it is not a general ABAC policy language. |
| **ReBAC** | Relationship-Based Access Control | Access is derived from relationships in a graph, such as user -> member of team -> owns folder -> contains document. | OrgSec models a narrower relationship shape: person -> organization -> organization tree -> protected entity. |
| **ACL** | Access Control List | A list of permissions attached to one object or row, often "principal X may read object Y". | OrgSec is usually better for broad org-subtree privileges; ACL is better for object-by-object exceptions. |
| **PBAC** | Policy-Based Access Control | A broad term for systems where policies, not only roles or ACL rows, decide access. | OrgSec has a fixed privilege model rather than a generic policy language. |

## Authorization Architecture

| Abbreviation | Full name | Meaning in practice | OrgSec relation |
| --- | --- | --- | --- |
| **PDP** | Policy Decision Point | The component that decides whether access should be allowed. In external policy systems, this may be a separate service. | OrgSec's `PrivilegeChecker` and `RsqlFilterBuilder` act as local decision/filter-building components inside the JVM, not as a standalone PDP service. |
| **PEP** | Policy Enforcement Point | The place that enforces a decision, such as a controller, service method, API gateway, or repository query. | In OrgSec applications this is usually `@PreAuthorize`, service code, or the repository query that receives the RSQL filter. |
| **PAP** | Policy Administration Point | The UI/API/process used to author and manage policies. | OrgSec does not ship a PAP; your application owns privilege and role administration. |
| **PIP** | Policy Information Point | A data source used during authorization, such as user attributes, organization membership, or resource metadata. | OrgSec storage backends and your `SecurityQueryProvider` provide this data. |

## OrgSec And Spring Terms

| Abbreviation | Full name | Meaning in practice |
| --- | --- | --- |
| **RSQL** | RESTful Service Query Language | A compact query/filter syntax. OrgSec generates RSQL so list endpoints can push authorization into database queries. |
| **JWT** | JSON Web Token | A signed token carrying claims about the caller. OrgSec's JWT backend can read the current person's memberships from an `orgsec` claim. |
| **IdP** | Identity Provider | The system that authenticates users and issues tokens, such as Keycloak, Auth0, Okta, or an internal OAuth2 provider. |
| **SPI** | Service Provider Interface | An interface applications or modules implement to plug behavior into a library. OrgSec uses SPIs such as `SecurityDataStorage`, `SecurityQueryProvider`, and `PrivilegeDefinitionProvider`. |
| **JPA** | Jakarta Persistence API | The Java persistence API used by many Spring applications. OrgSec does not require JPA internally, but its RSQL filters are often translated into JPA criteria. |
| **L1 / L2 cache** | Level 1 / Level 2 cache | L1 is a local in-process cache; L2 is a shared cache such as Redis. OrgSec's Redis backend uses both. |

## OrgSec Terms

| Term | Meaning |
| --- | --- |
| **Resource Security Context** | Company, organization, person, and path fields on a protected record. OrgSec compares these values with the current user's grants. |
| **Runtime user context** | Current person, default company/org, memberships, roles, and privileges for the authenticated caller. |
| **Business role** | Relationship name such as `owner` or `customer` that selects which fields on the protected resource are used. |
| **Position role** | Organizational duty such as `SHOP_MANAGER` that carries privileges. |
| **Fixture** | Programmatic test/demo data setup. `OrgsecInMemoryFixtures` populates the in-memory backend without a database provider. |
| **Resource** | Application resource name used in privilege identifiers, such as `DOCUMENT` in `DOCUMENT_ORGHD_R`. |

## Tool Names Often Mentioned In Comparisons

| Name | What it is |
| --- | --- |
| **Casbin / jCasbin** | A flexible authorization engine with RBAC, ABAC, ReBAC-style patterns, and local enforcement. |
| **Cerbos** | A policy-as-code authorization system with an external PDP model. |
| **OPA** | Open Policy Agent, a general-purpose policy engine using the Rego language. |
| **OpenFGA** | A Zanzibar-style ReBAC system for relationship-based permissions. |
| **SpiceDB / Authzed** | A permissions database and ReBAC system inspired by Google's Zanzibar model. |
| **Cedar** | A policy language used by Amazon Verified Permissions. |

## Short Rule Of Thumb

- Use **RBAC** language when roles are the main concept.
- Use **ABAC** language when attributes drive the decision.
- Use **ReBAC** language when graph relationships drive the decision.
- Use **PDP/PEP** language when discussing where authorization decisions are made and where they are enforced.
- Use **RSQL** language in OrgSec when discussing list filtering and repository queries.
