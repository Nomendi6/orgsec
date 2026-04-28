# Check A Single Entity

Use a single-entity check when the service has loaded one protected record and must decide whether the current user may read, write, or execute an action.

## Service-Layer Pattern

```java
@Service
public class DocumentService {
    private final DocumentRepository documents;
    private final DocumentAuthorization authorization;

    public Document read(Long id) {
        Document document = documents.findById(id).orElseThrow(NotFoundException::new);
        authorization.checkRead(document);
        return document;
    }

    public Document update(Long id, DocumentPatch patch) {
        Document document = documents.findById(id).orElseThrow(NotFoundException::new);
        authorization.checkWrite(document);
        patch.applyTo(document);
        authorization.refreshSecurityContextIfNeeded(document);
        return documents.save(document);
    }
}
```

Keep the check in the service layer when possible. The entity is already loaded, exceptions are easy to test, and controller code stays thin.

For updates, check the currently persisted security context before applying the patch. Otherwise a malicious request could first change ownership/security fields and then be evaluated against the modified record instead of the record the user was originally trying to update.

## Create Flow

For new records, initialize before checking:

```java
Document document = mapper.from(request);
securityContextManager.setDefaultOwnerContext(document);
authorization.checkWrite(document);
documents.save(document);
```

Checking before Resource Security Context initialization is not meaningful because OrgSec has no company/org/person fields to evaluate.

## `@PreAuthorize`

`PrivilegeChecker` can be wrapped by an application bean and called from SpEL:

```java
@PreAuthorize("@documentAuth.canRead(#id)")
@GetMapping("/documents/{id}")
public DocumentDTO get(@PathVariable Long id) {
    return documentService.read(id);
}
```

This works best for simple controller gates. For complex flows, prefer explicit service checks. For list endpoints, use RSQL filtering instead of per-row checks.

## Parent-Derived Checks

If a child record inherits security from a parent, check the parent context:

```java
Contract contract = contracts.findById(contractId).orElseThrow(NotFoundException::new);
authorization.checkWrite(contract);

ContractLine line = new ContractLine();
line.copySecurityFields(contract, "owner");
lines.save(line);
```

The important rule is that the entity checked by OrgSec must carry the same security context that your domain says controls the operation.

Next: [Filter a list endpoint](./07-filter-list-endpoint.md).
