# Critical Design Review: 2026-05-28-phase-4-organization-service-design (Round 1)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-28-phase-4-organization-service-design.md`
**Verified Assumptions section:** present

## 1. Verified-assumptions cross-check

Spot-checks of the most load-bearing items from §17 against the cited evidence (fresh-read sanity check, not re-litigation):

- **A4** (SINGLE_TABLE schema with `class` discriminator at line 1607) — **reconfirmed**. `grails-app/migrations/install/changelog-create-tables.groovy:1607` declares `column(name: "class", type: "VARCHAR(255)") { constraints(nullable: "false") }` inside the `createTable(tableName: "party")` block (lines 1586-1620), with `code`, `description`, `name`, `default_location_id`, and `active` columns all in the same `party` table. No separate `organization` table exists. SINGLE_TABLE conclusion stands.
- **A7** (OrganizationApiController has 3 actions) — **reconfirmed**. `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy` declares exactly `list()`, `read()`, `create(Organization)` — 38 lines total.
- **A11** (only 3 React files use `/api/organizations`) — **reconfirmed**. `grep -rnE "/api/organizations\b" src/js/` returns exactly 4 hits across 3 files: `option-utils.jsx:191`, `option-utils.jsx:225`, `actions/index.js:561`, `AddOrganizationModal.jsx:59`.
- **A12** (AddOrganizationModal POST body is `{name, description}` only) — **reconfirmed**. `AddOrganizationModal.jsx:59-64` shows literal `payload = { name: values.name, description: values.description }`.
- **A20** (port 8084 unused) — **reconfirmed**. `docker/docker-compose-base.yml` only references 8080-8083 (lines 9-86).
- **A22** (`services/organization-service` module doesn't exist) — **reconfirmed**. `ls services/` shows only `document-service`, `identity-service`, `location-service`, plus root files.

All spot-checked assumptions still hold.

## 2. Literal-wrongness findings

### 2.1 `RoleTypeCode` enum subset is dangerously incomplete; `@Enumerated(STRING)` + `EAGER` fetch propagates failure to GET reads

**Description.** Spec §5.3 (line 207) defines `RoleTypeCode` as "a Java enum mirror of the Grails RoleType enum subset used for Party roles (e.g., ROLE_SUPPLIER, ROLE_MANUFACTURER, ROLE_BUYER, ROLE_DISTRIBUTOR)" — 4 example values, no exhaustive list. Spec §5.3 also maps `PartyRole.roleType` as `@Enumerated(EnumType.STRING)`, and spec §5.1 declares `Party.roles` with `fetch = FetchType.EAGER`. Hibernate throws `IllegalArgumentException` on `Enum.valueOf` when it encounters a stored value not in the Java enum's value set. Because the fetch is EAGER, that throw fires on every `GET /api/organization/{id}` and on every list query — not lazily later.

The actual Grails `RoleType` enum at `src/main/groovy/org/pih/warehouse/core/RoleType.groovy` has **60+ values**. The "Organization role types" section alone (lines starting at the comment `// Organization role types`) defines 8 values: `ROLE_ORGANIZATION`, `ROLE_CARRIER`, `ROLE_SUPPLIER`, `ROLE_MANUFACTURER`, `ROLE_DISTRIBUTOR`, `ROLE_DONOR`, `ROLE_SHIPPING_AGENT`, `ROLE_CLEARING_AGENT`. Plus `ROLE_BUYER` from the "Purchasing roles" section. Any of these (and others, since `Organization.addToRoles` accepts any `RoleType`) may legitimately appear in `party_role.role_type`. The spec's "e.g., 4 values" enum will throw on any of the other ~5+ "organization-flavored" values, and many more if non-org-flavored types have been added to organizations historically.

The spec's §13 mitigation ("`@Enumerated(STRING)` throws loudly on unknown values, surfacing the drift at boot or first read") is post-hoc detection, not prevention — and the seed.sql described in §11.1 only includes `ROLE_SUPPLIER`/`ROLE_BUYER`, so integration tests will not catch the gap. The first production read of an Organization with `ROLE_CARRIER` or `ROLE_MANUFACTURER` returns 500.

(PartyRole is Organization-only — verified: `User.groovy:38` uses `hasMany = [roles: Role, locationRoles: LocationRole]`, a different `Role` entity. So the failure surface is bounded to org reads, but that *is* the asked-for behavior.)

**Evidence.**
- `src/main/groovy/org/pih/warehouse/core/RoleType.groovy` (60+ enum values)
- spec §5.1 line 137 (`fetch = FetchType.EAGER` on `roles`)
- spec §5.3 line 200 (`@Enumerated(EnumType.STRING)`)
- spec §5.3 line 207 (4-value "e.g." subset)
- spec §11.1 lines 469 (seed.sql only seeds `ROLE_SUPPLIER`/`ROLE_BUYER`)

**Proposed fix.** Drop the `RoleTypeCode` enum entirely. Change `PartyRole.roleType` to `@Column(name = "role_type", nullable = false, length = 255) private String roleType;` (raw string). The DTOs in §5.6 already expose `String roleType` (line 271), so external consumers are unaffected. Update `PartyRoleService.findBy(String partyId, String roleType)` and the `roleType[]` query parameter to pass strings end-to-end. Drop the §13 "RoleTypeCode synchronization debt" known issue — it disappears with the enum. Risk goes from "design ships broken on day 1" to "service accepts any string, including typos — caught at integration test time when the test queries by a misspelled role".

### 2.2 `@DiscriminatorValue("org.pih.warehouse.core.Organization")` is an unverified load-bearing claim

**Description.** Spec §5.2 line 166 hardcodes `@DiscriminatorValue("org.pih.warehouse.core.Organization")` — asserting that Grails GORM writes the fully-qualified class name to the `party.class` column. §17 A4 only verifies the *column's existence and shape*, not what value is stored in it.

Verified: neither `Party.groovy:23-25` (mapping block) nor `Organization.groovy:31-35` (mapping block) declares a `discriminator` override. So the actual value Grails writes is whatever Hibernate's default produces for Grails GORM's `tablePerHierarchy: true` mode — and that default is **not knowable from grep alone**. Per the JPA spec the default is the entity's simple name (`"Organization"`); some Hibernate/Grails-GORM configurations have historically used the FQCN instead. Without DB inspection we cannot tell which applies here. The spec picks one (FQCN) without empirical evidence.

If the actual stored value is anything other than `"org.pih.warehouse.core.Organization"`:
- **Reads break.** JPA's polymorphic queries inject `WHERE class = 'org.pih.warehouse.core.Organization'` for Organization, returning zero rows for every existing organization. `GET /api/organization/{id}` returns 404 for all real data. `GET /api/organization` returns an empty list.
- **Writes break interop.** JPA's `POST /api/organization` writes new rows with `class = 'org.pih.warehouse.core.Organization'`. Grails GORM, expecting `"Organization"` (or whatever the actual value is), cannot reify those rows as the `Organization` domain class — the Grails-internal readers in §7.2 (OrderService, InvoiceApiController, etc.) lose visibility into newly-created orgs.

This is the spec's single largest unverified load-bearing assumption. The session handoff already flagged it as a T1 watch-item, but the spec's verified-assumptions table did not.

**Evidence.**
- spec §5.2 line 166
- `grails-app/domain/org/pih/warehouse/core/Party.groovy:23-25` (no discriminator override in mapping)
- `grails-app/domain/org/pih/warehouse/core/Organization.groovy:31-35` (no discriminator override in mapping)
- `grails-app/domain/org/pih/warehouse/core/Person.groovy:28` — only `tablePerHierarchy false` override in the entire core/ package; nothing for Party/Organization

**Proposed fix.** Block T2 (bootstrap) on an empirical check: run `SELECT DISTINCT class FROM party;` against a Grails-bootstrapped DB (LoadDataService creates at least one Organization on first boot). Pin §5.2's `@DiscriminatorValue` to the observed value verbatim. Add a new row to §17:

> A28 — `party.class` discriminator value Grails writes: `<observed>`. Evidence: `SELECT DISTINCT class FROM party;` against Grails-bootstrapped DB on YYYY-MM-DD.

If the observed value is the simple name `"Organization"`, update §5.2 accordingly and update the `seed.sql` snippet in §11.1 (it currently embeds `class='org.pih.warehouse.core.Organization'` in the prose).

### 2.3 `Organization.active` has no default initializer; POST CREATE persists `active = NULL`, breaking the round-trip with `GET ?active=true`

**Description.** Physical schema at `grails-app/migrations/install/changelog-create-tables.groovy:1620`:
```
column(defaultValueBoolean: "true", name: "active", type: "BIT(1)")
```
No `constraints(nullable: "false")` — the column is **nullable with a DB-side `DEFAULT TRUE`**. Grails `Organization.groovy:22` then initializes the field at the application layer: `Boolean active = true`, with `constraint active(nullable: false)` at line 89.

Spec §5.2 lines 180-181:
```java
@Column(columnDefinition = "BIT(1)")
private Boolean active;
```
No field initializer, no `nullable = false` annotation, no @PrePersist default.

Hibernate's default INSERT generation includes every mapped column with the value held by the entity. The unset `Boolean` is `null`, so Hibernate writes `INSERT INTO party (..., active, ...) VALUES (..., NULL, ...)`. The DB-side `defaultValueBoolean: "true"` does **not** fire — the column-default only applies when the column is omitted from the INSERT. The POST handler at §6 doesn't accept `active` (request body is `{name: required, description: optional, code: optional}`), so the just-created Organization is persisted with `active = NULL`.

Now the round-trip fails:
- `POST /api/organization {name: "Acme"}` → 201 with `{data: {id: "X"}}`, but persisted with `active = NULL`.
- `GET /api/organization?active=true` — the new org is excluded. The asked-for list endpoint's `active` filter returns stale data.
- `GET /api/organization?active=false` — also excluded.
- Only unfiltered `GET /api/organization` shows it.

Existing Grails-created orgs have `active = 1` (Grails sets the application-layer default), so this only manifests for orgs created via the new POST endpoint. The bug is invisible on a fresh DB before any POST, and surfaces immediately after AddOrganizationModal's first submit.

**Evidence.**
- spec §5.2 lines 180-181 (no init, no `nullable=false`, no @PrePersist default)
- spec §6 line 294 (POST body schema; `active` not accepted)
- `grails-app/migrations/install/changelog-create-tables.groovy:1620` (DB column is nullable with `defaultValueBoolean: "true"`)
- `grails-app/domain/org/pih/warehouse/core/Organization.groovy:22, 89` (Grails app-layer default `true` + nullable=false constraint)

**Proposed fix.** Set the application-layer default in the JPA entity, mirroring Grails:
```java
@Column(nullable = false, columnDefinition = "BIT(1)")
private Boolean active = true;
```
Update §5.2 to show the initializer explicitly. The `nullable = false` annotation makes the mismatch with the DB column (which is currently nullable) explicit and lets `ddl-auto: validate` flag any future drift — JPA `nullable = false` validates against the metadata's `nullable=true` and will fail at boot, which is a useful tripwire for a future schema fix. Optionally also accept `active` in the POST body (`active: optional, default true`) so admin tooling can create inactive rows.

## 3. Forced decisions

No forced decisions found.

## 5. Recommendation

⚠️ **Approve with literal-wrongness fixes.** Address §2.1, §2.2, and §2.3 in the spec (via `update-design-doc`) before proceeding to `thorough-writing-plans`. §2.2 is the highest-stakes — it gates correctness of every endpoint and needs an empirical verification step pinned into §17 before T2 lands.
