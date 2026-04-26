# Securing Entities

This cookbook covers the entity side of OrgSec: how your domain class advertises which company / organization / person owns it, how it surfaces those values to `SecurityEnabledEntity`, and how to wire the resulting check into a service or controller. Read [Privileges and Business Roles](../guide/05-privileges-and-business-roles.md) first - this page assumes the privilege model is familiar.

## Recipe 1: single business role on a JPA entity

The simplest entity exposes one set of fields under one business role.

```java
@Entity
@Table(name = "document")
public class Document implements SecurityEnabledEntity {

    @Id
    @GeneratedValue
    private Long id;

    private String title;

    @Column(name = "owner_company_id")
    private Long ownerCompanyId;

    @Column(name = "owner_company_path", length = 1000)
    private String ownerCompanyPath;

    @Column(name = "owner_org_id")
    private Long ownerOrgId;

    @Column(name = "owner_org_path", length = 1000)
    private String ownerOrgPath;

    @Column(name = "owner_person_id")
    private Long ownerPersonId;

    @Override
    public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
        if ("owner".equalsIgnoreCase(businessRole)) {
            return switch (fieldType) {
                case COMPANY -> ownerCompanyId;
                case COMPANY_PATH -> ownerCompanyPath;
                case ORG -> ownerOrgId;
                case ORG_PATH -> ownerOrgPath;
                case PERSON -> ownerPersonId;
            };
        }
        return null;
    }

    @Override
    public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
        if ("owner".equalsIgnoreCase(businessRole)) {
            switch (fieldType) {
                case COMPANY -> ownerCompanyId = (Long) value;
                case COMPANY_PATH -> ownerCompanyPath = (String) value;
                case ORG -> ownerOrgId = (Long) value;
                case ORG_PATH -> ownerOrgPath = (String) value;
                case PERSON -> ownerPersonId = (Long) value;
            }
        }
    }

    // standard JPA accessors omitted
}
```

Notes:

- The interface is in `com.nomendi6.orgsec.interfaces`. The package name is intentional; the file lives under an `api/` directory but the import is `interfaces`.
- The path columns are `length = 1000` to match the `@Size(max = 1000)` annotation OrgSec puts on its own `OrganizationDef.pathId` / `parentPath` / `companyParentPath` fields. The annotation is informational on the OrgSec model; OrgSec does not run Bean Validation on the values returned by your `getSecurityField`. Keep your column widths aligned and clamp the path length on the write side of your application.
- Using `Long` for the entity ids keeps the contract simple. Returning a JPA-managed entity (e.g. `Party`) also works - OrgSec extracts the id via reflection - but adds the risk of triggering a lazy-loading proxy on a hot path.

## Recipe 2: multi-business-role entity

A `Document` is owned by some company and *also* visible to a customer company (the company it was issued to). The entity exposes both sets of fields.

```java
@Override
public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
    if ("owner".equalsIgnoreCase(businessRole)) {
        return switch (fieldType) {
            case COMPANY -> ownerCompanyId;
            case COMPANY_PATH -> ownerCompanyPath;
            case ORG -> ownerOrgId;
            case ORG_PATH -> ownerOrgPath;
            case PERSON -> ownerPersonId;
        };
    }
    if ("customer".equalsIgnoreCase(businessRole)) {
        return switch (fieldType) {
            case COMPANY -> customerCompanyId;
            case COMPANY_PATH -> customerCompanyPath;
            default -> null;        // customer scope is company-only
        };
    }
    return null;
}
```

The `default -> null` branch is important: returning a non-null value for a field type the role does not support causes inconsistent behavior. Match the YAML's `supported-fields` list precisely.

## Recipe 3: hierarchical entities (path denormalization)

The `_COMPHD` and `_ORGHD` directions need the entity's `pathId` columns to be present at the moment the check happens. For JPA entities, denormalize the path on write so the read path does not have to traverse the organizational tree:

```java
@PrePersist
@PreUpdate
public void denormalizePaths() {
    if (ownerCompany != null) {
        ownerCompanyId = ownerCompany.getId();
        ownerCompanyPath = ownerCompany.getPathId();
    }
    if (ownerOrg != null) {
        ownerOrgId = ownerOrg.getId();
        ownerOrgPath = ownerOrg.getPathId();
    }
}
```

Two practical points:

- **Re-denormalize on org moves.** When an organization moves in the tree (its `pathId` changes), every entity that references it needs its denormalized `*_PATH` updated. A nightly job that re-runs the denormalization is a robust complement to per-write hooks.
- **Treat path columns as `NOT NULL` for hierarchical roles.** A null path on a `_COMPHD` privilege fails closed (post-1.0.1 security review), but a column that *should* be populated is better caught at the database level than at authorization time.

## Recipe 4: programmatic check inside a service

Inject `PrivilegeChecker` and use it to gate a service method:

```java
@Service
public class DocumentService {

    private final PrivilegeChecker checker;
    private final SecurityDataStore store;
    private final SecurityContextProvider context;
    private final DocumentRepository documents;

    public DocumentService(
        PrivilegeChecker checker,
        SecurityDataStore store,
        SecurityContextProvider context,
        DocumentRepository documents
    ) {
        this.checker = checker;
        this.store = store;
        this.context = context;
        this.documents = documents;
    }

    public Document read(Long id) {
        Document doc = documents.findById(id).orElseThrow(NotFound::new);
        Long callerId = context.getCurrentPersonId();
        ResourceDef resource = buildResourceFor(callerId, doc);
        PrivilegeDef granted = checker.getResourcePrivileges(resource, PrivilegeOperation.READ);
        if (!checker.hasRequiredOperation(granted, PrivilegeOperation.READ)) {
            throw new AccessDeniedException("READ on Document " + id);
        }
        return doc;
    }
}
```

The `buildResourceFor` helper aggregates the caller's privileges for the document's resource type (`"Document"`). OrgSec's `SecurityDataStore` exposes the data needed to assemble it; the inmemory and Redis backends both produce the aggregated view through `BusinessRoleDef.resourcesMap`.

For a more complete integration with `@PreAuthorize`, see [Spring Security Integration](../guide/06-spring-security-integration.md).

## Recipe 5: list filtering with `RsqlFilterBuilder`

For list endpoints, evaluating the privilege per row is wasteful. Instead, ask `RsqlFilterBuilder` to produce an RSQL expression that the repository can apply as a `WHERE` clause:

```java
String filter = rsqlFilterBuilder.buildRsqlFilterForReadPrivileges(
    "Document",
    null,                                     // optional pre-filter
    currentPersonData
);
// pass `filter` to your RSQL-aware repository (e.g., RSQL-JPA-Specification)
```

The builder evaluates the caller's aggregated privileges and produces an expression that selects only rows where the caller has at least `READ`. After the 1.0.1 security review, the builder fails closed when a hierarchical privilege has a null parent path - the resulting filter denies access rather than returning every row.

The full recipe with end-to-end Spring Data JPA integration is in [Cookbook / RSQL filtering](./03-rsql-filtering.md).

## Recipe 6: securing a DTO that crosses to the frontend

`SecurityEnabledDTO` mirrors `SecurityEnabledEntity` for objects that travel over HTTP. Implement it on the DTO that your controller returns:

```java
public class DocumentDTO implements SecurityEnabledDTO {

    private Long id;
    private String title;
    private Long ownerCompanyId;
    private String ownerCompanyPath;
    // ... rest as on the entity

    @Override
    public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
        // mirror the entity's mapping
        return /* ... */;
    }

    @Override
    public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
        // mirror the entity's setter
    }
}
```

This is useful when the privilege check needs to happen on the controller side - for example, when the entity has already been mapped to a DTO and you want to verify a write privilege before applying changes. Keep the entity and DTO mappings consistent; a divergence is an authorization bug waiting to happen.

## Recipe 7: a single source of truth for security columns

Repeating the `if ("owner") ... else if ("customer") ...` pattern across many entities is error-prone. A shared base class or composition helper keeps it DRY:

```java
@MappedSuperclass
public abstract class OwnerSecuredEntity implements SecurityEnabledEntity {

    @Column(name = "owner_company_id")
    protected Long ownerCompanyId;

    @Column(name = "owner_company_path", length = 1000)
    protected String ownerCompanyPath;

    @Column(name = "owner_org_id")
    protected Long ownerOrgId;

    @Column(name = "owner_org_path", length = 1000)
    protected String ownerOrgPath;

    @Column(name = "owner_person_id")
    protected Long ownerPersonId;

    @Override
    public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
        if (!"owner".equalsIgnoreCase(businessRole)) return null;
        return switch (fieldType) {
            case COMPANY -> ownerCompanyId;
            case COMPANY_PATH -> ownerCompanyPath;
            case ORG -> ownerOrgId;
            case ORG_PATH -> ownerOrgPath;
            case PERSON -> ownerPersonId;
        };
    }

    @Override
    public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
        if (!"owner".equalsIgnoreCase(businessRole)) return;
        switch (fieldType) {
            case COMPANY -> ownerCompanyId = (Long) value;
            case COMPANY_PATH -> ownerCompanyPath = (String) value;
            case ORG -> ownerOrgId = (Long) value;
            case ORG_PATH -> ownerOrgPath = (String) value;
            case PERSON -> ownerPersonId = (Long) value;
        }
    }
}
```

Concrete entities extend `OwnerSecuredEntity` and add their domain-specific columns. For multi-role entities, compose helpers per role rather than building one giant base class.

## Common mistakes

- **Returning a non-null value for an unsupported field type.** If the YAML says `owner` supports `[COMPANY, COMPANY_PATH]`, do not return an org id under the `owner` role. The `default -> null` branch in the switch is your friend.
- **Forgetting to denormalize the path on write.** A new row without `*_PATH` populated will fail closed under hierarchical privileges. The denormalization hook in Recipe 3 catches it; without one, you have a class of "manager cannot see new document for an hour" bugs.
- **Comparing strings instead of paths.** When you write a custom check, use the `OrganizationDef.pathId` value as a string - OrgSec already encodes the boundary characters (`|`). Doing your own string slicing is a path-confusion vulnerability waiting to happen.
- **Treating the entity ID and the security field as the same thing.** They are not. The entity ID identifies the row; the security field identifies the *organization the row belongs to*. The distinction matters when a single row is owned by a hierarchy you do not control.

## Where to go next

- [Cookbook / Defining privileges](./01-defining-privileges.md) - the privilege side of the same picture.
- [Privileges and Business Roles](../guide/05-privileges-and-business-roles.md) - the conceptual reference.
- [Cookbook / RSQL filtering](./03-rsql-filtering.md) - list-endpoint filtering.
- [Spring Security Integration](../guide/06-spring-security-integration.md) - controller-side checks.
