# Security-Enabled Entity

A protected resource exposes its Resource Security Context through `SecurityEnabledEntity`. DTOs use the sibling `SecurityEnabledDTO` contract.

```java
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.interfaces.SecurityEnabledEntity;

public class Document implements SecurityEnabledEntity {
    private Long ownerCompanyId;
    private String ownerCompanyPath;
    private Long ownerOrgId;
    private String ownerOrgPath;
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
}
```

The interface package is `com.nomendi6.orgsec.interfaces`.

## Relationship Fields Or Flat Fields

OrgSec accepts either relationship-style values or flat ids.

| Entity shape | `getSecurityField` may return | RSQL selector |
| --- | --- | --- |
| JPA relationship | `ownerCompany` object with `getId()` | `ownerCompany.id` |
| Flat id column | `ownerCompanyId` as `Long` | `ownerCompanyId` |
| Path column | `ownerOrgPath` as `String` | `ownerOrgPath` |

If you use flat id columns, configure custom selectors with `orgsec.business-roles.<role>.rsql-fields` so single-record checks and list filters point to the same logical field.

## Match `supported-fields`

The role configuration and Java mapping must agree:

```yaml
orgsec:
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
```

If `ORG_PATH` is listed, `getSecurityField("owner", ORG_PATH)` should return a path whenever the record is initialized. If a hierarchical privilege needs a path and the value is missing, OrgSec denies.

## Multi-Role Entity

One entity can expose multiple business relationships:

```java
if ("owner".equalsIgnoreCase(businessRole)) {
    return ownerField(fieldType);
}
if ("customer".equalsIgnoreCase(businessRole)) {
    return customerField(fieldType);
}
return null;
```

Use this when the same row is protected through different relationships, for example owner organization and customer company.

## DTO Contract

Use `SecurityEnabledDTO` when the check happens after mapping to a DTO or when controller/service code receives a DTO before applying changes to an entity.

```java
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.interfaces.SecurityEnabledDTO;

public class DocumentDTO implements SecurityEnabledDTO {
    private Long ownerCompanyId;
    private String ownerOrgPath;

    @Override
    public Object getSecurityField(String businessRole, SecurityFieldType fieldType) {
        if (!"owner".equalsIgnoreCase(businessRole)) {
            return null;
        }
        return switch (fieldType) {
            case COMPANY -> ownerCompanyId;
            case ORG_PATH -> ownerOrgPath;
            default -> null;
        };
    }

    @Override
    public void setSecurityField(String businessRole, SecurityFieldType fieldType, Object value) {
        // Mirror the entity mapping when the DTO is used for write checks.
    }
}
```

Keep entity and DTO mappings consistent. If the entity returns `ownerOrgPath` and the DTO returns a different field for the same role/type pair, single-record checks can disagree depending on where they run.

Next: [Resource Security Context](./02-resource-security-context.md).
