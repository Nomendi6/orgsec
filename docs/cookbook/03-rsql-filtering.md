# RSQL Filtering

A privilege check on a single entity is one method call. A privilege check on a list of 10 000 entities is 10 000 method calls - that does not scale. The fix is to push the check into the database query: select only the rows the caller is allowed to see, instead of selecting everything and filtering in Java.

`RsqlFilterBuilder` produces an [RSQL](https://github.com/jirutka/rsql-parser) expression that encodes the caller's aggregated privileges. You combine that expression with whatever else your list endpoint filters by, parse the result through an RSQL-aware library (such as `rsql-jpa-specification`), and let the database do the work.

## What `RsqlFilterBuilder` produces

For a given resource (`Document`), the active person, and an operation (`READ` or `WRITE`), the builder walks the person's `OrganizationDef`s, picks the aggregated privileges per business role, and emits one of three results:

| Caller's effective privilege          | Builder output                                                                              |
| ------------------------------------- | ------------------------------------------------------------------------------------------- |
| `_ALL` grant (super-user)             | An empty string (and an internal `__ORGSEC_ALL_GRANT__` flag - see below)             |
| Concrete scoped privileges            | A parenthesised RSQL string - for example `(ownerCompany.id==10 or customerCompanyPath=*'\|1\|*')` |
| No matching privilege at all          | Throws `AccessDeniedException`                                                              |

The empty-string output for `_ALL` is intentional: an empty WHERE clause matches everything. The exception result is the **fail-closed** behavior added in the 1.0.1 security review - earlier versions silently produced an over-permissive filter when the caller had no privileges.

### Predicate shapes per privilege scope

The builder produces RSQL predicates whose field names follow a consistent pattern: `<alias><businessRole><FieldType>`. With no alias and the `owner` business role, the shapes are:

| Privilege scope | RSQL predicate the builder emits                                                                 |
| --------------- | ------------------------------------------------------------------------------------------------ |
| `_COMP` (EXACT) | `ownerCompany.id==<companyId>`                                                                   |
| `_COMPHD`       | `ownerCompanyPath=*'<companyParentPath>*'` - RSQL "starts-with" (`=*`) over the company path |
| `_COMPHU`       | `ownerCompanyPath=*'*<companyParentPath>'` - "ends-with" form                              |
| `_ORG` (EXACT)  | `ownerOrg.id==<orgId>`                                                                           |
| `_ORGHD`        | `ownerOrgPath=*'<orgParentPath>*'`                                                               |
| `_ORGHU`        | `ownerOrgPath=*'*<orgParentPath>'`                                                               |
| `_EMP`          | `ownerPerson.id==<personId>`                                                                     |

Two things to know when wiring the parser:

- **The `*Company.id` / `*Org.id` / `*Person.id` predicates are dotted paths.** The builder assumes the entity holds a JPA-managed reference (e.g., `Document.ownerCompany` of type `Party`) and that you are filtering through `id` on that relationship. If your entity stores a flat `ownerCompanyId` column instead, configure your RSQL parser's field map to translate `ownerCompany.id` -> `ownerCompanyId`.
- **`=*'...'` is the RSQL "like" operator.** Most RSQL JPA libraries map it to `LIKE`. The builder writes `'|1|*'` for hierarchy-down (path starts with the prefix) and `'*|1|'` for hierarchy-up (path ends with the suffix). Path values are escaped through `PathSanitizer` before substitution.

Multiple business roles with concurrent privileges are joined with RSQL OR (`,`); the result is wrapped in parentheses by `buildRsqlFilterForRead/WritePrivileges`.

## Recipe 1: read filter on a list endpoint

```java
@Service
public class DocumentQueryService {

    private final RsqlFilterBuilder filterBuilder;
    private final DocumentRepository repo;
    private final PersonDataProvider personDataProvider;
    private final SecurityContextProvider context;

    public DocumentQueryService(RsqlFilterBuilder filterBuilder,
                                DocumentRepository repo,
                                PersonDataProvider personDataProvider,
                                SecurityContextProvider context) {
        this.filterBuilder = filterBuilder;
        this.repo = repo;
        this.personDataProvider = personDataProvider;
        this.context = context;
    }

    public Page<Document> findVisible(Pageable pageable) {
        PersonData caller = context.getCurrentUserLogin()
            .flatMap(personDataProvider::findByRelatedUserLogin)
            .orElseThrow(() -> new AccessDeniedException("No authenticated person"));

        // Throws AccessDeniedException if the caller has no read privilege on Document.
        String securityRsql = filterBuilder.buildRsqlFilterForReadPrivileges("Document", null, caller);

        return repo.findAll(toSpecification(securityRsql), pageable);
    }

    private Specification<Document> toSpecification(String rsql) {
        if (rsql == null || rsql.isBlank()) {
            // _ALL grant; no security filter.
            return Specification.where(null);
        }
        Node node = new RSQLParser().parse(rsql);
        return node.accept(new JpaCriteriaQueryVisitor<>());
    }
}
```

The `RSQLParser` and `JpaCriteriaQueryVisitor` come from `rsql-jpa-specification`; any RSQL parser that produces JPA criteria works the same way. OrgSec stays agnostic about which RSQL library you use.

## Recipe 2: combining with a user-supplied filter

List endpoints typically accept a user-supplied RSQL filter (`?filter=status==active`). Combine it with the security filter using AND:

```java
public Page<Document> find(String userFilter, Pageable pageable) {
    PersonData caller = currentCallerOrThrow();
    String securityRsql = filterBuilder.buildRsqlFilterForReadPrivileges("Document", null, caller);

    String combined = combine(securityRsql, userFilter);
    return repo.findAll(toSpecification(combined), pageable);
}

private String combine(String securityRsql, String userRsql) {
    if (isBlank(securityRsql) && isBlank(userRsql)) return "";
    if (isBlank(securityRsql)) return userRsql;
    if (isBlank(userRsql))     return securityRsql;
    return securityRsql + ";" + userRsql;   // RSQL AND
}
```

`;` is the RSQL AND operator. Always put the security filter on the *left*; some RSQL libraries optimize left-most predicates first, which can short-circuit user-supplied predicates that would otherwise be expensive on protected rows.

## Recipe 3: write-side filter

The same pattern works for write privileges - for example, when bulk-updating only the rows you are allowed to write:

```java
String writeRsql = filterBuilder.buildRsqlFilterForWritePrivileges("Document", null, caller);
List<Document> writable = repo.findAll(toSpecification(writeRsql));
writable.forEach(this::doUpdate);
```

If the caller has only `READ` on `Document`, the call throws `AccessDeniedException` - you cannot accidentally widen a read into a write.

## Fail-closed semantics

After the 1.0.1 security review, three pieces of behavior changed and are worth knowing about explicitly:

1. **No privilege at all -> `AccessDeniedException`.** A caller with zero read privileges on `Document` cannot get a list endpoint that returns "everything except their own org" or similar. The check is at the query-build step, not at the row-evaluation step.
2. **Hierarchical privilege with a null path -> `AccessDeniedException`.** A `_COMPHD_R` grant evaluated against an organization whose `companyParentPath` is `null` previously produced an over-permissive RSQL string. Now it fails closed. If you see this exception unexpectedly, check that your `OrganizationDef` rows have well-formed `pathId` / `companyParentPath` strings.
3. **Empty filter is *only* possible on `_ALL`.** A blank string from the builder means the caller has the `__ORGSEC_ALL_GRANT__` shortcut; it is never a "we could not figure out the privilege so we will not filter." If the caller has nothing, you get an exception, not an empty filter.

## The `__ORGSEC_ALL_GRANT__` sentinel

When the caller's aggregated privileges include the `all = true` shortcut (see [Privileges and Business Roles - The privilege model](../guide/05-privileges-and-business-roles.md#axis-3-cascade)), the builder marks the result with the internal sentinel `__ORGSEC_ALL_GRANT__`. The sentinel is *not* visible in the returned RSQL string - the builder strips it before returning - but it is what causes the empty-string output instead of an exception.

You do not need to handle the sentinel in your code. The contract is:

- Empty / blank string returned: no security filter needed; the caller sees everything.
- Non-empty string returned: apply it as a query predicate.
- `AccessDeniedException`: stop, return 403 to the caller.

If you ever see the literal `__ORGSEC_ALL_GRANT__` in your generated SQL, that is a bug - please [open an issue](https://github.com/Nomendi6/orgsec/issues).

## Recipe 4: business-role-restricted filter

By default, `buildRsqlFilterForReadPrivileges` aggregates across every business role the caller has on the resource. Sometimes you want to restrict the filter to a specific business role - for example, "only show documents I own, never the ones I am a customer for." Use the lower-level method:

```java
String filter = filterBuilder.buildRsqlFilterForPrivileges(
    "Document",
    null,
    List.of("owner"),                      // only the owner business role
    PrivilegeOperation.READ,
    caller
);
```

This is rare; the default aggregation is correct for most list endpoints.

## What happens with `parentField`

The `parentField` argument lets you express "the privilege check applies through a parent relationship." For a `DocumentVersion` whose privileges are inherited from the parent `Document`, pass `parentField = "document"` and OrgSec scopes the generated filter to the parent's columns. The detail of how this composes with JPA path expressions is in the source of `RsqlFilterBuilder`; for most direct entities, leave `parentField` at `null`.

## Performance notes

- The builder runs in O(roles x privileges) per call - small in practice. The cost is dominated by the database query the resulting RSQL drives, not by the build step.
- For very wide users (a person with hundreds of memberships), the produced RSQL can be large. JPA criteria handles that without trouble; raw SQL with very large `IN (...)` clauses might run into database-specific limits. If you hit one, narrow the user's set or partition the query.
- The builder reads the cached `PersonDef` once per call. There is no separate database round-trip on top of whatever the storage backend does.

## Where to go next

- [Cookbook / Securing entities](./02-securing-entities.md) - the entity side of the same picture.
- [Cookbook / Defining privileges](./01-defining-privileges.md) - the privilege patterns the filter encodes.
- [Privileges and Business Roles](../guide/05-privileges-and-business-roles.md) - the conceptual reference.
- [Spring Security Integration](../guide/06-spring-security-integration.md) - controller-side wiring.
