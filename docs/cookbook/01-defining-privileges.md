# Defining Privileges

This cookbook collects the most common privilege-definition patterns. Each recipe is a complete, drop-in example that you can adapt for your own resources. They all assume you have read [Privileges and Business Roles](../guide/05-privileges-and-business-roles.md) and that you are using a `PrivilegeDefinitionProvider` to register your privileges.

## Recipe 1: read access on the exact organization

A clerk works inside one specific organization. They can read the documents that belong to that organization - nothing under it, nothing above it.

```text
DOCUMENT_ORG_R
```

Mapped on the position role `BACK_OFFICE_CLERK`. The role is tagged with the `owner` business role; the entity exposes its `ORG` and `ORG_PATH` fields under that role.

```java
@Component
public class ClerkPrivileges implements PrivilegeDefinitionProvider {

    private final PrivilegeLoader loader;

    public ClerkPrivileges(PrivilegeLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    public void register() {
        loader.initializePrivileges(this);
    }

    @Override
    public Map<String, PrivilegeDef> getPrivilegeDefinitions() {
        return Map.of("DOCUMENT_ORG_R", createPrivilegeDefinition("DOCUMENT_ORG_R"));
    }

    @Override
    public PrivilegeDef createPrivilegeDefinition(String identifier) {
        return PrivilegeLoader.createPrivilegeDefinition(identifier);
    }
}
```

You will rarely register a single privilege per provider; group them by feature module instead. The remaining recipes show the privilege identifier alone - treat each as one entry in the same provider's `IDENTIFIERS` list.

## Recipe 2: write restricted to one's own organization

The clerk can also update those documents. Write implies read, so adding `_W` is enough - you do not need a separate `_R` privilege.

```text
DOCUMENT_ORG_W
```

When `PrivilegeDef.add(...)` aggregates privileges for `READ` operations, it accepts both `READ` and `WRITE` privileges, so a user holding only `_W` will still pass a `READ` check.

## Recipe 3: hierarchy-down for managers

A regional manager wants to see every document attached to their company sub-tree.

```text
DOCUMENT_COMPHD_R
```

The `_COMPHD` suffix sets `company = HIERARCHY_DOWN, org = NONE, person = false`. At evaluation time, OrgSec compares the entity's `COMPANY_PATH` against the manager's company path: a match is a `startsWith`. Any descendant company is included automatically. The manager's entity does not need to know about the hierarchy - the path comparison is OrgSec's job.

For write access on the same scope, use `DOCUMENT_COMPHD_W`. Keep in mind that this is a powerful privilege - it grants write across an entire sub-tree. Most teams reserve hierarchy-down writes for a small set of trusted roles.

## Recipe 4: hierarchy-up (rare)

A subordinate needs to see context from a parent organization - for example, a desk that handles tickets for the whole region but lives in one branch.

```text
TICKET_ORGHU_R
```

The `_ORGHU` suffix sets `company = NONE, org = HIERARCHY_UP, person = false`. The entity's `ORG_PATH` must be an ancestor of the caller's org path (or the caller must be at exactly that org).

Hierarchy-up privileges are uncommon in practice because most domains model "this entity is owned by org X, descendants of X can see it" rather than "ancestors of X can see entities owned at X." Use `_ORGHU` only when the data layout justifies it.

## Recipe 5: super-user "all" access

An auditor reads every document everywhere, including organizations they have no relationship with.

```text
DOCUMENT_ALL_R
```

The `_ALL` suffix sets `all = true`, bypassing the cascade. Use this sparingly - the privilege does not respect any organizational boundary. Reserve it for:

- Read-only auditors with strict UI-side restrictions on what they can do with the data.
- Support staff with case-by-case access during incidents.
- Internal reporting jobs that aggregate across all organizations.

When aggregated with any other privilege, `_ALL` wins permanently. Do not assume that "another more restrictive privilege" will somehow narrow the user's access - the aggregation step never narrows.

## Recipe 6: person-scoped privilege

A user can edit the documents that they personally own (their `PERSON` field on the entity matches their id).

```text
DOCUMENT_EMP_W
```

The cascade falls through `company` (NONE) and `org` (NONE) and lands on `person = true`. OrgSec compares the entity's `PERSON` value against the caller's id; the entity does not need to expose `COMPANY_PATH` or `ORG_PATH` for this privilege to evaluate.

In practice, `_EMP` is often combined with a wider read privilege so a user can see their own and their org's documents but write only their own:

```text
DOCUMENT_ORG_R           // read everything in own org
DOCUMENT_EMP_W           // write only own
```

The two are evaluated independently; the user passes whichever check matches the operation.

## Recipe 7: multi-resource privilege provider

Most providers register privileges for several resources at once. Use a list per resource and aggregate them in `getPrivilegeDefinitions`:

```java
@Component
public class CoreDomainPrivileges implements PrivilegeDefinitionProvider {

    private static final List<String> DOCUMENT_PRIVILEGES = List.of(
        "DOCUMENT_COMPHD_R",
        "DOCUMENT_ORG_W",
        "DOCUMENT_EMP_W",
        "DOCUMENT_ALL_R"
    );

    private static final List<String> INVOICE_PRIVILEGES = List.of(
        "INVOICE_COMPHD_R",
        "INVOICE_ORG_W",
        "INVOICE_EMP_R"
    );

    private static final List<String> CONTRACT_PRIVILEGES = List.of(
        "CONTRACT_COMP_R",
        "CONTRACT_ORG_W",
        "CONTRACT_COMPHD_R"
    );

    private final PrivilegeLoader loader;

    public CoreDomainPrivileges(PrivilegeLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    public void register() {
        loader.initializePrivileges(this);
    }

    @Override
    public Map<String, PrivilegeDef> getPrivilegeDefinitions() {
        Map<String, PrivilegeDef> defs = new HashMap<>();
        Stream.of(DOCUMENT_PRIVILEGES, INVOICE_PRIVILEGES, CONTRACT_PRIVILEGES)
            .flatMap(List::stream)
            .forEach(id -> defs.put(id, createPrivilegeDefinition(id)));
        return defs;
    }

    @Override
    public PrivilegeDef createPrivilegeDefinition(String identifier) {
        return PrivilegeLoader.createPrivilegeDefinition(identifier);
    }
}
```

Several providers can coexist - OrgSec calls each one at startup and pours all their definitions into the same registry. Splitting providers per feature module keeps the privilege list close to the code that uses it.

## Recipe 8: hand-rolled privilege definition

When the `RESOURCE_SCOPE_OPERATION` convention does not fit (you need a privilege whose name reads better in a different style, or you want to combine flags that the parser does not produce), build the `PrivilegeDef` directly:

```java
@PostConstruct
public void register() {
    PrivilegeDef approve = new PrivilegeDef("CONTRACT_APPROVE", "Contract")
        .allowOperation(PrivilegeOperation.WRITE)
        .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.EXACT, false);
    privilegeRegistry.registerPrivilege("CONTRACT_APPROVE", approve);
}
```

`allowOrg(company, org, person)` sets all three scope axes in one call. The `name` and `resourceName` fields are bookkeeping - OrgSec uses them in audit messages and aggregated views. Use this sparingly; the convention exists so that aggregation produces predictable identifier names.

> **Note on `EXECUTE`.** `PrivilegeOperation.EXECUTE` exists in the enum and `PrivilegeDef.allowOperation(EXECUTE)` is accepted, but `PrivilegeChecker.hasRequiredOperation(..., EXECUTE)` always returns `false` in 1.0.x - the method does not have an `EXECUTE` branch. Privileges built with `EXECUTE` (or registered through identifiers ending in `_E`) register cleanly but never satisfy a standard authorization check until that branch lands. Until then, prefer `WRITE` for any operation that should be authorized through the standard flow, or model execute-style operations as a separate resource with `READ` / `WRITE` privileges.

## Recipe 9: testing privilege evaluation

When you add a new privilege, it is worth a focused test that loads the privilege, attaches it to a role, attaches the role to a person, and verifies that `PrivilegeChecker.hasRequiredOperation` returns the expected boolean.

The test pattern is well established in `RsqlFilterBuilderTest` and the storage tests under `orgsec-storage-inmemory/src/test`. Adapt that scaffolding for your own privileges - supply a `PersonDef` whose `organizationsMap` carries the privilege through a `BusinessRoleDef`, then call the checker.

## Where to go next

- [Cookbook / Securing entities](./02-securing-entities.md) - the other half: how the entity advertises ownership.
- [Privileges and Business Roles](../guide/05-privileges-and-business-roles.md) - the conventions reference.
- [Privilege Model Reference](../reference/privilege-model.md) - truth tables and edge cases.
