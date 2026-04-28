# Privileges

Privileges are application-defined identifiers that OrgSec parses or registers at startup. They describe the resource, the scope, and the operation.

## Naming Convention

The common format is:

```text
RESOURCE_SCOPE_OPERATION
```

Examples:

| Identifier | Meaning |
| --- | --- |
| `DOCUMENT_ORGHD_R` | Read documents owned by this organization or descendants. |
| `DOCUMENT_ORG_W` | Write documents owned by the exact organization. |
| `DOCUMENT_EMP_R` | Read documents owned by the current person. |
| `DOCUMENT_ALL_R` | Read all documents. |

## Scope Suffixes

| Scope | Meaning |
| --- | --- |
| `ALL` | All records for the resource. |
| `COMP` | Exact company. |
| `COMPHD` | Company hierarchy down. |
| `COMPHU` | Company hierarchy up. |
| `ORG` | Exact organization. |
| `ORGHD` | Organization hierarchy down. |
| `ORGHU` | Organization hierarchy up. |
| `EMP` | Person-owned records. |

Operations are `R` for read, `W` for write, and `E` for execute.

## Register Privileges

```java
import com.nomendi6.orgsec.api.PrivilegeDefinitionProvider;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.storage.inmemory.loader.PrivilegeLoader;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DocumentPrivileges implements PrivilegeDefinitionProvider {
    private final PrivilegeLoader loader;

    public DocumentPrivileges(PrivilegeLoader loader) {
        this.loader = loader;
    }

    @PostConstruct
    public void register() {
        loader.initializePrivileges(this);
    }

    @Override
    public Map<String, PrivilegeDef> getPrivilegeDefinitions() {
        return Map.of(
            "DOCUMENT_ORGHD_R", createPrivilegeDefinition("DOCUMENT_ORGHD_R"),
            "DOCUMENT_ORG_W", createPrivilegeDefinition("DOCUMENT_ORG_W"),
            "DOCUMENT_ALL_R", createPrivilegeDefinition("DOCUMENT_ALL_R")
        );
    }

    @Override
    public PrivilegeDef createPrivilegeDefinition(String identifier) {
        return PrivilegeLoader.createPrivilegeDefinition(identifier);
    }
}
```

For non-standard privilege shapes, register a `PrivilegeDef` directly through `PrivilegeRegistry`.

## Programmatic Privileges

Use `PrivilegeRegistry` directly when the standard `RESOURCE_SCOPE_OPERATION` convention does not describe the permission clearly enough.

```java
import com.nomendi6.orgsec.api.PrivilegeRegistry;
import com.nomendi6.orgsec.constants.PrivilegeDirection;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.model.PrivilegeDef;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class WorkflowPrivileges {
    private final PrivilegeRegistry registry;

    public WorkflowPrivileges(PrivilegeRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        PrivilegeDef approve = new PrivilegeDef("DOCUMENT_APPROVE", "DOCUMENT")
            .allowOperation(PrivilegeOperation.EXECUTE)
            .allowOrg(PrivilegeDirection.NONE, PrivilegeDirection.EXACT, false);

        registry.registerPrivilege("DOCUMENT_APPROVE", approve);
    }
}
```

Keep this path rare. A project is easier to review when most privileges follow the same naming convention.

## Multi-Resource Provider

One provider can register privileges for a feature area that spans several resources:

```java
private static final List<String> IDENTIFIERS = List.of(
    "DOCUMENT_ORGHD_R",
    "DOCUMENT_ORG_W",
    "ATTACHMENT_ORGHD_R",
    "ATTACHMENT_ORG_W"
);
```

This is usually cleaner than one provider per entity when the same module owns the lifecycle of related resources.

## Common Patterns

| Pattern | Privilege |
| --- | --- |
| Manager reads org subtree | `DOCUMENT_ORGHD_R` |
| Employee edits own org records | `DOCUMENT_ORG_W` |
| User reads personal drafts | `DOCUMENT_EMP_R` |
| Auditor reads all rows | `DOCUMENT_ALL_R` |
| Processor executes an action | `DOCUMENT_ORG_E` |

`EXECUTE` is independent. It does not imply `READ` or `WRITE`, and `READ`/`WRITE` do not imply `EXECUTE`.

## Naming Hygiene

- Keep identifiers uppercase and stable.
- Avoid underscores inside the `RESOURCE` segment because the parser splits on underscores.
- Store privilege ids in constants or an enum in your application code.
- Test every new privilege id; malformed shape throws, but unknown scope or operation tokens can register a privilege that grants nothing.
- Reserve `_ALL_*` for narrowly reviewed administrator or auditor roles.

## Test The Privilege

For each new privilege family, load a small fixture and assert at least one positive and one denied case.

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

PersonDef alice = storage.getPerson(1L);
ResourceDef document = alice.organizationsMap.get(22L)
    .businessRolesMap.get("owner")
    .resourcesMap.get("DOCUMENT");

PrivilegeDef read = checker.getResourcePrivileges(document, PrivilegeOperation.READ);
assertThat(checker.hasRequiredOperation(read, PrivilegeOperation.READ)).isTrue();
```

For full aggregation, cascade, and fail-closed truth tables, see [Privilege model reference](../reference/privilege-model.md).

Next: [Check a single entity](./06-check-single-entity.md).
