# JWT Example App

This walkthrough builds a Spring Boot application that authenticates with an OAuth2 resource server and resolves the current user's `PersonDef` from a JWT claim. As with the Redis example, the application code is mostly the same as the in-memory walkthrough; what changes is the storage configuration, the security configuration, and how the test scaffolding produces tokens.

The example offers two paths:

- **Path A - Mock JwtDecoder for learning.** A `JwtDecoder` bean assembled in code accepts hand-crafted tokens. No IdP is required. Best for understanding what the JWT backend reads and how Spring Security validates the token.
- **Path B - Real IdP.** Spring Security's resource-server auto-configuration pulls the JWKS from Keycloak (or any compliant IdP) and validates real tokens. The IdP must be configured to inject the OrgSec claim - see [Cookbook / Keycloak mapper](../cookbook/05-keycloak-mapper.md).

If you have not run the in-memory example, do that first. This page lists *only the differences*.

## Project layout (additions)

```
in-memory-example/                  (now: jwt-example)
|-- pom.xml                         <- modified
`-- src
    `-- main
        |-- java/com/example/docs
        |   |-- SecurityConfig.java          <- new
        |   `-- TestJwtTooling.java          <- new (Path A)
        `-- resources
            `-- application.yml              <- modified
```

## `pom.xml` (additions)

The JWT module already pulls in `spring-boot-starter-oauth2-resource-server` transitively; no separate Spring Security dependency is needed.

```xml
<dependency>
    <groupId>com.nomendi6.orgsec</groupId>
    <artifactId>orgsec-storage-jwt</artifactId>
    <version>${orgsec.version}</version>
</dependency>
```

## `application.yml` (Path A - mock decoder)

```yaml
orgsec:
  storage:
    primary: jwt
    features:
      jwt-enabled: true
      memory-enabled: true
      hybrid-mode-enabled: true
    data-sources:
      person: jwt
      organization: primary
      role: primary
      privilege: memory
    jwt:
      claim-name: orgsec
      claim-version: "1.0"
      cache-parsed-person: true
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
    reader:
      supported-fields: [COMPANY, COMPANY_PATH]

logging:
  level:
    com.nomendi6.orgsec: INFO
    com.example.docs: INFO
```

For Path A there is **no** `spring.security.oauth2.resourceserver.jwt.issuer-uri` - we supply the `JwtDecoder` bean ourselves.

## `application.yml` (Path B - real IdP)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OIDC_ISSUER:http://keycloak:8080/realms/myrealm}

orgsec:
  storage:
    primary: jwt
    features:
      jwt-enabled: true
      memory-enabled: true
      hybrid-mode-enabled: true
    data-sources:
      person: jwt
      organization: primary
      role: primary
      privilege: memory
    jwt:
      claim-name: orgsec
      claim-version: "1.0"
      cache-parsed-person: true
  business-roles:
    owner:
      supported-fields: [COMPANY, COMPANY_PATH, ORG, ORG_PATH, PERSON]
    reader:
      supported-fields: [COMPANY, COMPANY_PATH]
```

Spring Security pulls the JWKS from `${OIDC_ISSUER}/.well-known/openid-configuration`, builds a `JwtDecoder` automatically, and OrgSec piggy-backs on it. No code changes from Path A are required - only the YAML.

## `SecurityConfig.java`

A minimal filter chain that requires authentication on the application endpoints (we leave the OrgSec Person API filter chain - if any - alone):

```java
package com.example.docs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain appSecurity(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(rs -> rs.jwt(jwt -> {}))
            .build();
    }
}
```

`oauth2ResourceServer(rs -> rs.jwt(jwt -> {}))` tells Spring Security to validate JWTs using whatever `JwtDecoder` bean is in the context. For Path B that bean is auto-configured from `issuer-uri`. For Path A, we supply it.

## Path A - Mock JwtDecoder for learning

Add a configuration class that registers a `JwtDecoder` accepting tokens signed with a known HMAC secret. This is **not** for production; it makes the example runnable without a real IdP.

```java
package com.example.docs;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Configuration
public class TestJwtTooling {

    public static final String HMAC_SECRET = "dev-secret-please-do-not-use-in-prod-32bytes!!";

    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(HMAC_SECRET.getBytes(), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Helper used from a CLI runner to mint a token with the OrgSec claim.
     * Not wired in any HTTP path; call from a CommandLineRunner during local exploration.
     */
    public static String mintToken() throws Exception {
        Map<String, Object> orgsecClaim = Map.of(
            "version", "1.0",
            "person", Map.of(
                "id", 42L,
                "name", "Alice Smith",
                "relatedUserId", "alice-uuid",
                "relatedUserLogin", "alice@example.com",
                "defaultCompanyId", 1L,
                "defaultOrgunitId", 22L
            ),
            "memberships", List.of(
                Map.of(
                    "organizationId", 22L,
                    "companyId", 1L,
                    "pathId", "|1|10|22|",
                    "positionRoleIds", List.of(101L, 205L)
                )
            )
        );

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("alice-uuid")
            .issueTime(java.util.Date.from(Instant.now()))
            .expirationTime(java.util.Date.from(Instant.now().plusSeconds(3600)))
            .claim("orgsec", orgsecClaim)
            .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(HMAC_SECRET.getBytes()));
        return jwt.serialize();
    }
}
```

`com.nimbusds.jose.*` is on the classpath through Spring Security's resource-server starter; you do not need to add it explicitly.

### Mint and use a token (Path A)

A small CLI helper so you can copy a fresh token into curl. The snippet shows only the body; add the imports `org.springframework.boot.CommandLineRunner` and `org.springframework.stereotype.Component` at the top of the file.

```java
@Component
public class TokenPrinter implements CommandLineRunner {
    @Override
    public void run(String... args) throws Exception {
        if (args.length > 0 && "print-token".equals(args[0])) {
            System.out.println(TestJwtTooling.mintToken());
        }
    }
}
```

Run:

```bash
# Print a fresh token
mvn spring-boot:run -Dspring-boot.run.arguments=print-token

# Use it
TOKEN=<paste from previous command>
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/documents/1
```

OrgSec's JWT backend reads the `orgsec` claim, builds a `PersonDef` for Alice with one membership at organization 22, and `PrivilegeChecker` evaluates the request against your privilege definitions.

## Path B - Real IdP

For Path B you need:

1. A running Keycloak (or other compliant IdP) at the URL named in `OIDC_ISSUER`.
2. The OrgSec mapper deployed and attached to the client scope - see [Cookbook / Keycloak mapper](../cookbook/05-keycloak-mapper.md).
3. The OrgSec service exposing the Person API with `ROLE_ORGSEC_API_CLIENT` enforced - see [Configuration - Person API](../guide/04-configuration.md#top-level-toggles).

Then the same controller and same OrgSec configuration work; only the token comes from a real IdP instead of the test tooling. The application **cannot tell the difference** between a Path A token and a Path B token, which is the point of the JWT backend.

## Domain code

Identical to the in-memory example. The `Document`, `DocumentPrivileges`, `StubSecurityQueryProvider`, `DemoRunner`, and `Application` classes do not change. The JWT backend reads `Person` from the token; the in-memory backend continues to serve `Organization`, `Role`, and `Privilege`. With the stub provider returning empty lists, your privilege checks will deny access until you replace the stub with real loaders - the example is wiring-only, just like the in-memory version.

## What changes for production

| Concern                   | Path A (mock)                       | Production                                                    |
| ------------------------- | ----------------------------------- | ------------------------------------------------------------- |
| `JwtDecoder` source       | HMAC secret in code                 | JWKS pulled from `issuer-uri`                                 |
| Token issuer              | Local helper                        | Real IdP (Keycloak with the OrgSec mapper)                    |
| Audience validation       | None in this example                | Configure `JwtDecoder` to require expected `aud`              |
| Token TTL                 | 1 hour for convenience              | Short access tokens (5-15 min) plus refresh                   |
| Person API on application | Off                                 | On, restricted to the mapper's service account                |
| Memberships in claim      | Hand-crafted                        | Produced by the mapper from the application's database        |

## Where to go next

- [Storage / JWT](../storage/04-jwt.md) - the JWT backend reference.
- [Cookbook / Keycloak mapper](../cookbook/05-keycloak-mapper.md) - build, deploy, and configure the mapper.
- [Operations / Production checklist](../operations/production-checklist.md) - pre-deployment items for JWT-backed deployments.
- [Examples / In-memory app](./in-memory-app.md) - the baseline this example builds on.
