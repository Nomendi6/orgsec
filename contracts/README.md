# Person API contracts (OrgSec 2.x)

Canonical copies of the D1-WIRE Person artefacts. Bytes must match
`orgsec-keycloak-mapper/contracts/` for the three Person files. See `SHA256SUMS`.

- `person-api-v1.schema.json` — 200 body, `"version": "1.0"`
- `person-api-error-v1.schema.json` — `{ "code": ... }`
- `person-api-security-v1.contract.json` — path, JwtDecoder, role, status+code map
