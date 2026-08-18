# Load Security Data

Resource Security Context describes the protected record. Security data describes the current user's grants: person, organizations, position roles, business roles, and privileges.

## What OrgSec Needs

OrgSec evaluates access from these data sets:

- persons
- organizations and paths
- person-to-organization memberships
- position roles
- role-to-privilege assignments
- business role tags on roles
- registered privilege definitions

Your application schema can look different. The adapter/provider layer maps it into OrgSec's model.

## `SecurityQueryProvider`

The in-memory storage backend loads data through `SecurityQueryProvider`. Implementations usually query the application database and return rows for persons, organizations, memberships, roles, and privileges.

Use this when application data is authoritative in the local database and the service can load a snapshot at startup or refresh time.

## Fixture API

For tests and examples, `OrgsecInMemoryFixtures` can populate the in-memory stores programmatically:

```java
fixtures
    .load()
    .privilege("DOCUMENT_ORGHD_R")
    .company(1L, "Acme")
    .organization(10L, "EU Region", "|1|10|")
    .company(1L)
    .organization(22L, "Shop-22", "|1|10|22|")
    .company(1L)
    .role("SHOP_MANAGER")
    .grants("DOCUMENT_ORGHD_R")
    .asBusinessRole("owner")
    .person(1L, "Alice")
    .defaultCompany(1L)
    .defaultOrgunit(22L)
    .memberOf(22L)
    .withRole("SHOP_MANAGER")
    .apply();
```

Production data should usually come from providers or storage adapters, not fixtures.

## Storage Behavior

| Storage | What it does |
| --- | --- |
| In-memory | Loads a local snapshot through `SecurityQueryProvider`; refreshes through notify hooks. |
| Redis | Publishes a lease-fenced READY snapshot; GET/LIST use the local view after a generation recheck. |
| JWT | Reads the current person from a trusted token claim; other data is delegated to one other backend. |
| Hybrid | Only JWT-plus-delegate exists. Per-data-type routing properties are inert. JWT+Redis is refused. |

If storage cannot find required data, OrgSec fails closed. JWT storage does not magically load a missing organization from the database unless a configured delegate storage already has it.

## Notify Hooks

When security data changes, call `SecurityEventPublisher` producer methods (`partyRoleChanged`,
`personChanged`, `partyChanged`, ...) so the local apply and the Kafka publish attempt run once
after the surrounding transaction commits. A rollback notifies nobody. With no transaction the
notify runs immediately. Kafka consumers and other internal paths use the `apply*` methods, which
notify storage at once and never republish.

Do not call `storage.notify*` from an application service that still has an open transaction.

Common triggers are:

- person changed
- organization changed or moved
- membership changed
- role assignment changed
- privilege definition changed

Resource Security Context changes on ordinary protected records are usually handled by updating that record. Notify hooks are needed when the shared authorization data itself changes.

Next: [Choose storage](../storage/01-choose-storage.md).
