# First Working Example

This example uses the in-memory fixture API to load one company, one organization tree, one person, one role, and one privilege. The goal is a real positive check, not only a Spring context that starts.

## Dependency

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

## Business Role

```yaml
orgsec:
  storage:
    primary: memory
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
      rsql-fields:
        COMPANY: ownerCompanyId
        COMPANY_PATH: ownerCompanyPath
        ORG: ownerOrgId
        ORG_PATH: ownerOrgPath
        PERSON: ownerPersonId
```

## Protected Resource

```java
import com.nomendi6.orgsec.constants.SecurityFieldType;
import com.nomendi6.orgsec.interfaces.SecurityEnabledEntity;

public class Document implements SecurityEnabledEntity {
    Long ownerCompanyId = 1L;
    String ownerCompanyPath = "|1|";
    Long ownerOrgId = 22L;
    String ownerOrgPath = "|1|10|22|";
    Long ownerPersonId = 1L;

    @Override
    public Object getSecurityField(String role, SecurityFieldType field) {
        if (!"owner".equalsIgnoreCase(role)) {
            return null;
        }
        return switch (field) {
            case COMPANY -> ownerCompanyId;
            case COMPANY_PATH -> ownerCompanyPath;
            case ORG -> ownerOrgId;
            case ORG_PATH -> ownerOrgPath;
            case PERSON -> ownerPersonId;
        };
    }

    @Override
    public void setSecurityField(String role, SecurityFieldType field, Object value) {
        // Omitted in this minimal read-only example.
    }
}
```

The document is owned by organization `Shop-22` with path `|1|10|22|`.

## Load Fixture Data

```java
import com.nomendi6.orgsec.storage.inmemory.fixtures.OrgsecInMemoryFixtures;

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

`OrgsecInMemoryFixtures` is intended for tests, examples, and demos. Production data should usually flow through `SecurityQueryProvider`.

## Positive Check

```java
import static org.assertj.core.api.Assertions.assertThat;

import com.nomendi6.orgsec.common.service.PrivilegeChecker;
import com.nomendi6.orgsec.constants.PrivilegeOperation;
import com.nomendi6.orgsec.dto.PersonData;
import com.nomendi6.orgsec.model.BusinessRoleDef;
import com.nomendi6.orgsec.model.PersonDef;
import com.nomendi6.orgsec.model.PrivilegeDef;
import com.nomendi6.orgsec.model.ResourceDef;

PersonDef alice = storage.getPerson(1L);
BusinessRoleDef owner = alice.organizationsMap.get(22L).businessRolesMap.get("owner");
ResourceDef documentResource = owner.resourcesMap.get("DOCUMENT");

PrivilegeDef readPrivilege = checker.getResourcePrivileges(documentResource, PrivilegeOperation.READ);

assertThat(readPrivilege).isNotNull();
assertThat(checker.hasRequiredOperation(readPrivilege, PrivilegeOperation.READ)).isTrue();
```

This verifies that Alice has a readable `DOCUMENT` privilege through the `owner` business role.

## RSQL Filter

For a list endpoint, use the same fixture data to build a query filter:

```java
String filter = rsqlFilterBuilder.buildRsqlFilterForReadPrivileges(
    "DOCUMENT",
    null,
    new PersonData(1L, "Alice")
);

assertThat(filter).isEqualTo("(ownerOrgPath=*'|1|10|*')");
```

For `DOCUMENT_ORGHD_R`, the generated filter targets `ownerOrgPath` with the `|1|10|` hierarchy anchor from the loaded organization data.

Next: [Security-enabled entity](../usage/01-security-enabled-entity.md).
