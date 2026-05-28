# Phase 4 Organization Slice — Design Spec

**Date**: 2026-05-28
**Parent design**: `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` §6 row 4 (Organization slice), §4.3 (entity ownership), §8 (per-slice template)
**Predecessor retrospectives**: Phase 1 (`docs/retrospectives/2026-05-26-phase-1-document-retrospective.md`), Phase 2 (`docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md`), Phase 3 (`docs/retrospectives/2026-05-28-phase-3-location-retrospective.md`)
**Tag**: `phase-4-organization`

## 1. TL;DR

Phase 4 stands up `organization-service` as a new Spring Boot 3.x / Java 21 module that owns the React-facing HTTP surface for the Party hierarchy (Organization + Party base + PartyRole + PartyType + Address). It serves 7 GET endpoints under a new `/api/organization/*` (singular) prefix AND a write endpoint `POST /api/organization` that replaces the equivalent Grails action. The slice deletes `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy` and migrates 3 React files from `/api/organizations` (plural) → `/api/organization` (singular). Grails `OrganizationController` (GSP admin, 12 actions + 5 GSP views), `OrganizationService` (Grails-internal cross-context callers from LoadDataService / MigrationService / LocationImportDataService), and all the JPA-mapped Grails domain entities (`Organization`, `Party`, `PartyRole`, `PartyType`, `Address`) stay alive for cross-context Grails consumers and are deferred to Phase 7+ (sagas) or Phase 12 (GSP cleanup). This is Phase 4's "real strangler bite": ~38 lines of Grails API controller deleted, 3 React URL migrations, first React-facing POST endpoint in an extracted service.

## 2. Done state (one paragraph)

`organization-service` runs in compose as the 7th container at port 8084 (`expose:` only, not `ports:`); validates `obx_token` cookies via shared HMAC HS256 secret; serves `GET /api/organization/{id}`, `GET /api/organization?q=&roleType[]=&active=&max=&offset=`, `POST /api/organization`, `GET /api/organization/party/{id}`, `GET /api/organization/partyType`, `GET /api/organization/partyType/{id}`, and `GET /api/organization/partyRole?partyId=&roleType=`; flat FK-only DTOs (FD#3 from Phase 3); `PartyTypeCache` (refresh-on-miss, with `getAll()`-refresh-on-empty fix per Phase 3 RC-6); JPA `@Inheritance(SINGLE_TABLE)` for `Organization extends Party` with `@DiscriminatorColumn(name="class")`; nginx routes `location = /api/organization` (exact-match) + `location /api/organization/` (prefix) to `organization-service:8084`; the 3 React files migrated from `/api/organizations` (plural) → `/api/organization` (singular); `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy` deleted (the generic `/api/${resource}s` URL mapping stays — Grails returns 404 when the target `organizationApi` controller doesn't exist); Grails `OrganizationController` (GSP admin) + `OrganizationService` + Grails domain entities + LoadDataService/MigrationService/LocationImportDataService **all kept alive** for cross-context Grails consumers (Grails-internal writes via shared DB; deferred to Phase 7+); 4 Liquibase shadow changelogs (`tableExists` precondition + empty body for `party`, `party_role`, `party_type`, `address` — no separate `organization` table per SINGLE_TABLE inheritance); `OrganizationServiceIntegrationTest` (15+ tests, TestContainers + JPA, `seed.sql` with discriminator-aware Party + Organization rows + PartyType seed); Playwright E2E (~5 specs covering GET via nginx + POST AddOrganizationModal flow + regression for Grails `/api/organizations` plural URL absence). Tagged `phase-4-organization` on `main`. CI green.

## 3. Forced design decisions

### FD#1 — Read+write scope (Approach B from brainstorming)

**Pick**: Partial strangler. GET endpoints (read surface) + POST `/api/organization` (only React writer surface). Defer the Grails-internal write paths (LoadDataService bootstrap, MigrationService one-shot, LocationImportDataService CSV import) to Phase 7+ when sagas land.

**Rationale**: Approach A (read-only) doesn't delete any Grails code; Approach C (full strangler) introduces a novel Grails→service HTTP-write pattern that breaks transactional atomicity inside Grails Order/Invoice processing — which the saga infrastructure (§4.5 of parent design) explicitly exists to solve in Phase 7. Approach B is the largest strangler step achievable without sagas.

### FD#2 — JPA inheritance strategy (SINGLE_TABLE, NOT JOINED)

**Pick**: `@Inheritance(strategy = InheritanceType.SINGLE_TABLE)` + `@DiscriminatorColumn(name = "class", discriminatorType = STRING, length = 255)` + `@DiscriminatorValue("org.pih.warehouse.core.Organization")` on the Organization subclass.

**Rationale** (empirical, from assumption verification): the physical `party` table (defined at `grails-app/migrations/install/changelog-create-tables.groovy:1586-1620`) contains a `class VARCHAR(255) NOT NULL` discriminator column AND all Organization-specific columns (`code`, `description`, `name`, `default_location_id`, `active`). There is no separate `organization` table. Grails GORM's default `tablePerHierarchy: true` is what shaped the schema. Phase 2's `User extends Person` looks similar but uses JOINED — because GORM `Person.groovy` was explicitly configured `tablePerHierarchy: false`. Party doesn't override.

**Implication**: differs from Phase 2's identity-service inheritance pattern. Future-phase services that extract subclasses of shared-kernel base entities MUST verify the physical schema's discriminator-column-vs-separate-table reality before assuming JOINED.

### FD#3 — Flat FK-only DTOs

**Pick**: Carry forward Phase 3's flat-FK-only DTO pattern. `OrganizationDto`, `PartyDto`, `PartyTypeDto`, `PartyRoleDto`, `AddressDto` all use scalar FK IDs (no nested Party/Organization/PartyType/Address inflation in responses).

**Rationale**: Phase 3 retro line 20 documented the 6+ days of LazyInitializationException + cross-service-fetching cost saved by flat DTOs. Same applies here. Cost: explicit behavior departure documented as Phase 4 §13 known issue.

### FD#4 — Plural URL deletion (hard break)

**Pick**: `/api/organizations` (plural) returns 404 from Grails after Phase 4. React migrates the 4 call sites (in 3 files) to `/api/organization` (singular). No parallel period.

**Rationale**: Phase 3 explicitly kept `/api/locations/*` (plural) on Grails because the `LocationApiController` retained meaningful behavior (writes, role-filtered list, internal-location admin) that Phase 3's read-only slice didn't cover. Phase 4 is different: `OrganizationApiController` has only 3 actions (list/read/create), all replaced by org-service. There is no Grails-side behavior worth preserving on the plural URL. Risk acceptance documented as §13 known issue.

### FD#5 — Defer Supplier / Donor / Shipper from Phase 4 scope

**Pick**: Phase 4 owns Party, Organization, PartyRole, PartyType, Address (5 entities). Defer the other 3 entities from parent design §4.3 line 44:
- **Supplier** is a SQL view (`CREATE OR REPLACE VIEW supplier AS ...` at `grails-app/migrations/views/supplier-list.sql`); JPA cannot write to it; not a real entity.
- **Donor** lives in `grails-app/domain/org/pih/warehouse/donation/Donor.groovy`, donation bounded context. Defer to a hypothetical future donation-service or Phase 12 cleanup.
- **Shipper** lives in `grails-app/domain/org/pih/warehouse/shipping/Shipper.groovy`, shipping bounded context. Naturally moves with Phase 7 shipping-service.

**Rationale**: Parent design §4.3 lumps 8 entities together by spec-author intent; the physical/package reality places 3 in other bounded contexts. Mirrors Phase 3's pragmatic scope-tightening (Phase 3 dropped LocationStatus because it's an enum, not an entity). Back-port to parent design noted in §16.

### FD#6 — `jwt-auth-common` shared library deferred to Phase X

**Pick**: 4th copy of `JwtCookieAuthFilter` + `JwtService` (subset) + `SecurityConfig` from `services/location-service/.../security/` with package rename to `org.openboxes.organization.security`. Do NOT extract a shared library in Phase 4.

**Rationale**: User-approved at brainstorming. Refactoring 3 already-shipped services (document/identity/location) to consume a new shared library adds Phase 4 scope churn outside the slice's purpose. The 4th copy strongly motivates extraction in a Phase X hygiene sprint alongside Phase 2/3 RC items.

## 4. Architecture

```
                            ┌──────────────────────────────────────────────┐
                            │  organization-service (NEW, port 8084)        │
                            │  ┌────────────────────────────────────────┐   │
                            │  │ Controllers (3):                        │   │
                            │  │ - OrganizationController                 │   │
                            │  │   GET /api/organization/{id}             │   │
                            │  │   GET /api/organization (filtered list) │   │
                            │  │   POST /api/organization                │   │
                            │  │ - PartyController                        │   │
                            │  │   GET /api/organization/party/{id}      │   │
                            │  │ - ReferenceController                    │   │
                            │  │   GET /api/organization/partyType{,/id} │   │
                            │  │   GET /api/organization/partyRole       │   │
                            │  ├────────────────────────────────────────┤   │
                            │  │ Services:                                │   │
                            │  │ - OrganizationService (port from Grails) │   │
                            │  │ - PartyService                           │   │
                            │  │ - PartyTypeCache (RC-6 fix)              │   │
                            │  │ - PartyRoleService                       │   │
                            │  │ - OrganizationIdentifierService (port)   │   │
                            │  ├────────────────────────────────────────┤   │
                            │  │ JPA Entities (SINGLE_TABLE inheritance): │   │
                            │  │ - Party (base, discriminator: 'class')   │   │
                            │  │ - Organization extends Party             │   │
                            │  │ - PartyRole (OneToMany to Party)         │   │
                            │  │ - PartyType (reference data)             │   │
                            │  │ - Address                                │   │
                            │  ├────────────────────────────────────────┤   │
                            │  │ Security: JwtCookieAuthFilter (4th copy) │   │
                            │  │ Liquibase: 4 shadow changelogs           │   │
                            │  │   (party, party_role, party_type, addr)  │   │
                            │  └────────────────────────────────────────┘   │
                            └──────────────────────────────────────────────┘
                                              ▲
                                              │ JPA reads/writes against SHARED DB
                                              ▼
   openboxes-db ──────► openboxes-app (Grails) ◄── shared DB ──► openboxes-organization-service
                              │                                              │
   React (3 files) ──────► nginx ─────────────────────────────────────────────┘
   AddOrganizationModal     │       /api/organization* (singular)     → org-service:8084
   option-utils.jsx         │       /api/organizations* (plural)      → Grails (now 404 — controller deleted)
   actions/index.js         │       /api/generic/organization/{id}    → Grails GenericApiController (UNCHANGED, out-of-scope)
                            │       /organization/* (GSP admin)        → Grails OrganizationController (UNCHANGED, Phase 12)
```

### 4.1 New container

`organization-service` is the 7th container in `docker/docker-compose-base.yml` after `app` (8080), `document-service` (8081), `identity-service` (8082), `location-service` (8083). Port 8084 (verified unused per `docker-compose-base.yml` grep). `expose: 8084` only (network-only); host-side access via `sudo docker exec openboxes-organization-service curl localhost:8084/...`. nginx `depends_on: organization-service condition: service_healthy`.

### 4.2 No new dependencies on other services

organization-service does not call any other Spring Boot service. It does NOT call location-service even though `Organization.defaultLocationId` is a Location FK — the DTO surfaces the FK as a scalar; consumers needing full Location data make a separate `/api/location/{id}` call (Phase 3-owned).

## 5. Entity model (JPA, Java 21, SINGLE_TABLE inheritance)

### 5.1 `Party` (base)

**Note (A28-pending):** the `@DiscriminatorValue` string below is a *provisional* FQCN, same status as §5.2's Organization placeholder. Concrete Party rows exist in the physical table (§11.1's polymorphic test fixture relies on one); without an explicit `@DiscriminatorValue`, JPA defaults to the entity simple name `"Party"`, which mismatches if Grails GORM wrote the FQCN. A28 pins this value (and §5.2's, and both `class=` values in §11.1) to the empirically observed string before T2.

```java
@Entity
@Table(name = "party")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "class", discriminatorType = DiscriminatorType.STRING, length = 255)
@DiscriminatorValue("org.pih.warehouse.core.Party")  // A28-pending — same as §5.2
public class Party {
    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "party_type_id", nullable = false)
    private PartyType partyType;

    @OneToMany(mappedBy = "party", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<PartyRole> roles = new HashSet<>();

    @Column(name = "date_created", nullable = false)
    private Instant dateCreated;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @PrePersist void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        if (dateCreated == null) dateCreated = now;
        lastUpdated = now;
    }
    @PreUpdate void preUpdate() { lastUpdated = Instant.now(); }
    // getters/setters
}
```

Notes:
- `EAGER` on `roles` matches Phase 3 RC-5 mitigation (cached/detached entities + collections trip LazyInitializationException; party row counts are small, EAGER is safe).
- `EAGER` on `partyType` enables DTO denormalization without an extra fetch.
- `id` is `CHAR(38)`; identifier-generation pattern matches Phase 2 `Person.java:46` (UUID without hyphens; Grails uses a 38-char form historically — verify generated IDs match shape on first integration test).

### 5.2 `Organization extends Party`

**Note (A28-pending):** the `@DiscriminatorValue` string below is a *provisional* FQCN. Neither Party.groovy nor Organization.groovy declares a `mapping { discriminator … }` override, so Grails GORM writes whatever Hibernate's default produces. Before T2 (bootstrap module) runs, T1's audit MUST execute `SELECT DISTINCT class FROM party` against a Grails-bootstrapped DB and pin this value (and the seed.sql snippet in §11.1) to the empirically observed string. See §17 A28.

```java
@Entity
@DiscriminatorValue("org.pih.warehouse.core.Organization")
public class Organization extends Party {
    @Column(nullable = false, length = 255)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "default_location_id", columnDefinition = "CHAR(38)")
    private String defaultLocationId;  // FK scalar — NOT @ManyToOne (Location lives in another service; Phase 3 read-only ownership)

    @Column(nullable = false, columnDefinition = "BIT(1)")
    private Boolean active = true;
    // getters/setters
}
```

Notes:
- All Organization fields live in the SAME `party` table per SINGLE_TABLE inheritance.
- `Organization.sequences Map<IdentifierTypeCode, String>` from Grails is **NOT modeled** (YAGNI — no React caller accesses it; the join table `organization_sequences` remains accessible to Grails for legacy reads).
- `hasPurchaseOrders()` / `maxPurchaseOrderNumber()` from Grails Organization.groovy are **NOT ported** — they query Order (Phase 7 scope) and are only used by GSP admin (which stays on Grails). The Grails entity retains them.

### 5.3 `PartyRole`

```java
@Entity
@Table(name = "party_role")
public class PartyRole {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column(nullable = false) private Long version;
    @ManyToOne @JoinColumn(name = "party_id", nullable = false) private Party party;
    @Column(name = "role_type", nullable = false, length = 255) private String roleType;
    @Column(name = "start_date") private Instant startDate;
    @Column(name = "end_date") private Instant endDate;
    // getters/setters
}
```

`PartyRole.roleType` is a raw `String` (not a JPA enum) — see §13 for rationale.

### 5.4 `PartyType`

```java
@Entity
@Table(name = "party_type")
public class PartyType {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column(nullable = false) private Long version;
    @Column(nullable = false, length = 255) private String code;       // unique
    @Column(length = 255) private String name;
    @Column(length = 255) private String description;
    @Enumerated(EnumType.STRING) @Column(name = "party_type_code", nullable = false, length = 255) private PartyTypeCode partyTypeCode;
    @Column(name = "date_created", nullable = false) private Instant dateCreated;
    @Column(name = "last_updated", nullable = false) private Instant lastUpdated;
    // getters/setters
}
```

`PartyTypeCode` is a Java enum mirror of the Grails `PartyTypeCode` enum (`ORGANIZATION`, `PERSON`).

### 5.5 `Address`

```java
@Entity
@Table(name = "address")
public class Address {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Column(nullable = false, length = 255) private String address;
    @Column(length = 255) private String address2;
    @Column(length = 255) private String city;
    @Column(name = "state_or_province", length = 255) private String stateOrProvince;
    @Column(name = "postal_code", length = 255) private String postalCode;
    @Column(length = 255) private String country;
    @Column(length = 4000) private String description;
    @Column(name = "date_created") private Instant dateCreated;
    @Column(name = "last_updated") private Instant lastUpdated;
    // getters/setters
}
```

### 5.6 DTOs (flat FK-only per FD#3)

```java
public record OrganizationDto(
    String id, String code, String name, String description,
    String partyTypeId, String partyTypeCode,
    String defaultLocationId,
    Boolean active, Instant dateCreated, Instant lastUpdated,
    List<String> roleTypes
) {
    public static OrganizationDto from(Organization o) { ... }  // null-safe extraction
}

public record PartyDto(
    String id, String partyTypeId, String partyTypeCode, List<String> roleTypes
) { ... }

public record PartyTypeDto(
    String id, String code, String name, String description, String partyTypeCode
) { ... }

public record PartyRoleDto(
    String id, String partyId, String roleType, Instant startDate, Instant endDate
) { ... }

public record AddressDto(
    String id, String address, String address2, String city, String stateOrProvince,
    String postalCode, String country, String description
) { ... }
```

### 5.7 `OrganizationIdentifierService` port

Java port of `grails-app/services/org/pih/warehouse/core/OrganizationIdentifierService.groovy` (~50 lines using Apache Commons Lang `WordUtils.initials`). Reads `openboxes.identifier.organization.minSize` and `openboxes.identifier.organization.maxSize` from `application.yml` (same property names as Grails). Includes the TODO-marked bug at Grails line 42-43 verbatim (`suffix++` produces `':'` when suffix is `'9'`) — Phase 4 is a port, not a refactor; bug fix is a Phase X carry-forward.

Dependencies: `org.apache.commons:commons-lang3` (or rewrite in pure Java — pick during T6 implementation).

## 6. REST API

All endpoints under `/api/organization/*` (singular — new prefix). All require valid `obx_token` JWT cookie. Response envelope: `{data: ...}` matching Grails `OrganizationApiController` shape (Phase 3 precedent).

| Method + path | Purpose | Response | Status codes |
|---|---|---|---|
| `GET /api/organization/{id}` | Read organization by id | `{data: OrganizationDto}` | 200, 401 (no JWT), 404 (not found) |
| `GET /api/organization?q=&roleType[]=&active=&max=&offset=` | Filtered list. `q` substring-matches id/code/name/description; `roleType[]` filters by PartyRole.roleType (multi); `active` filters by Organization.active; `max`/`offset` paginate (default max=50). | `{data: [OrganizationDto, ...]}` | 200, 401, 400 (invalid params) |
| `POST /api/organization` | Create organization. Body: `{name: required, description: optional, code: optional}`. Code auto-generated via `OrganizationIdentifierService.generate(name)` if absent. `partyType` defaults to `PartyType.findByCode("ORG")`. | `{data: {id: <new>}}` | 201, 400 (validation), 401 |
| `GET /api/organization/party/{id}` | Polymorphic party read (returns base shape regardless of discriminator) | `{data: PartyDto}` | 200, 401, 404 |
| `GET /api/organization/partyType` | List all PartyTypes (cache-served) | `{data: [PartyTypeDto, ...]}` | 200, 401 |
| `GET /api/organization/partyType/{id}` | Read PartyType by id (cache-served) | `{data: PartyTypeDto}` | 200, 401, 404 |
| `GET /api/organization/partyRole?partyId=&roleType=` | List PartyRoles (filtered by partyId and/or roleType) | `{data: [PartyRoleDto, ...]}` | 200, 401 |

**Not in Phase 4 surface** (YAGNI):
- ~~`PUT /api/organization/{id}`~~ — no React update caller; GSP admin still owns updates
- ~~`DELETE /api/organization/{id}`~~ — no React delete caller; GSP admin
- ~~CSV download~~ — Grails OrganizationController.download()
- ~~PartyType writes~~ — reference data, admin-managed via Grails GSP

## 7. Service layer

```java
@Service public class OrganizationService {
    OrganizationDto getById(String id);
    List<OrganizationDto> list(OrganizationListParams params);   // q, roleType[], active, max, offset
    OrganizationDto create(CreateOrganizationCommand cmd);       // name + optional description + optional code
}

@Service public class PartyService {
    PartyDto getById(String id);                                  // polymorphic; returns base shape
}

@Service public class PartyTypeCache {                            // mirrors Phase 3 LocationTypeCache
    PartyTypeDto getById(String id);                              // refresh-on-miss
    List<PartyTypeDto> getAll();                                  // RC-6 fix: ALSO refresh-on-empty
}

@Service public class PartyRoleService {
    List<PartyRoleDto> findBy(String partyId, String roleType);
}

@Service public class OrganizationIdentifierService {             // ported from Grails
    String generate(String name);                                  // 50-line port; uniqueness via OrganizationRepository.countByCode
}
```

### 7.1 Cross-context coupling — what stays on Grails

Per FD#1, Phase 4 does NOT migrate the following Grails-internal cross-context write paths. They keep using GORM directly against the shared DB:

| Grails caller | What it writes | Why it stays Grails | Phase to migrate |
|---|---|---|---|
| `LoadDataService.groovy:92-99` | `new Organization(...)` + `addToRoles(new PartyRole(...))` at app bootstrap | Atomic with other bootstrap entities; no concurrent readers | Phase X (post-Phase 7 sagas) |
| `MigrationService.groovy:715,814,822,854,858,867` | `findOrCreateOrganization` for supplier/manufacturer data migration | One-shot admin tool | Phase X |
| `LocationImportDataService.groovy:174-175,190` | `organizationService.findOrCreateSupplierOrganization`, `findOrCreateOrganization`, `new Address()` during CSV import | Atomic with Location row creation; CSV import path | Phase X (same retro as Phase 3 item #3) |

These callers continue to write to `party`, `party_role`, `address` tables via Grails GORM. organization-service reads see committed rows on the next query. Read-after-write across processes is acceptable for bootstrap (no concurrent readers) and admin-rare operations.

### 7.2 Cross-context coupling — what stays on Grails as a reader

Grails internal services that READ Organization continue using `Organization.get(id)` directly via GORM (NOT through organization-service HTTP):

- `OrderService`, `OrderController`, `PurchaseOrderIdentifierService` (ordering scope — Phase 7)
- `InvoiceApiController` (billing scope — Phase 10)
- `ProductSupplierService` (catalog scope — Phase 5)
- `CreateShipmentWorkflowController` (shipping scope — Phase 7)
- `LocationController`, `LocationGroupController` (location scope — Phase 3 — shared DB pattern)
- `JsonController`, `BudgetCodeController`, `SelectTagLib` (cross-cutting UI)

These will switch to HTTP calls during their respective phase extractions OR Phase 12 cleanup. Phase 4 does NOT touch them.

## 8. Security

Pattern: 4th copy of `JwtCookieAuthFilter` + `JwtService` (validate-only subset) + `SecurityConfig` from `services/location-service/.../security/` with package rename `org.openboxes.location.security` → `org.openboxes.organization.security`.

**Critical**: `SecurityConfig` MUST include the `exceptionHandling` line that returns 401 (not Spring Security 6 default 403):

```java
.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
```

Verbatim from `services/location-service/src/main/java/org/openboxes/location/security/SecurityConfig.java:26` (Phase 3 RC-1 retro lesson).

Allowlist: `/actuator/health`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**`.

JWT secret: `OPENBOXES_JWT_SECRET` env var, shared with all Spring Boot services + Grails. Same HMAC HS256 key.

## 9. nginx routing

New routes added to `docker/nginx/conf.d/app.conf` BEFORE the `/api/` catch-all:

```nginx
# Phase 4: organization-service. Exact-match + prefix per Phase 3 RC-T8/T12 pattern.
# Exact-match is required for the bare-path LIST endpoint (GET /api/organization?roleType=...).
location = /api/organization {
    proxy_pass http://organization-service:8084;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Cookie $http_cookie;
}

location /api/organization/ {
    proxy_pass http://organization-service:8084;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Cookie $http_cookie;
}
```

Note: `proxy_pass` URLs intentionally omit trailing slash (Phase 3 spec back-port — adding trailing slash strips matched path).

### 9.1 Route map after Phase 4

| URL pattern | Routes to | Notes |
|---|---|---|
| `/api/organization` (exact) | organization-service:8084 | NEW — LIST endpoint |
| `/api/organization/*` (prefix) | organization-service:8084 | NEW — all other org endpoints |
| `/api/organizations` (plural) | Grails (via `/api/${resource}s` URL mapping line 935) → 404 (controller missing) | **CHANGED** — was Grails 200; now Grails 404 because OrganizationApiController is deleted |
| `/api/generic/organization/{id}` | Grails GenericApiController | UNCHANGED — out of Phase 4 scope; `OrganizationApi.js` continues working |
| `/organization/*` | Grails (catch-all `/openboxes/*` route) | UNCHANGED — GSP admin pages |
| `/api/location/*`, `/api/locations/*`, `/api/identity/*`, `/api/documents/*`, etc. | Phase 1/2/3 services | UNCHANGED |

## 10. Liquibase shadow changelogs

**Pattern**: Phase 3 RC-2 shadow changelogs with `tableExists` precondition + empty body. organization-service declares the tables it reads/writes; Grails Liquibase actually creates them. organization-service's changesets MARK_RAN on fresh DB.

**Files** (under `services/organization-service/src/main/resources/db/changelog/`):

```
db.changelog-master.xml                          # includes the 4 below
changes/2026-05-29-party.xml                     # tableExists tableName="party"
changes/2026-05-29-party_role.xml                # tableExists tableName="party_role"
changes/2026-05-29-party_type.xml                # tableExists tableName="party_type"
changes/2026-05-29-address.xml                   # tableExists tableName="address"
```

**4 changelogs (not 5)** — there is no separate `organization` table per FD#2. The `party` table contains all Organization columns via SINGLE_TABLE discriminator.

**`application.yml` bootstrap discipline** (Phase 3 RC-2 lesson):
- T2 (bootstrap commit): `spring.liquibase.enabled: false`
- T3 (changelog commit): flip to `enabled: true` + `change-log: classpath:db/changelog/db.changelog-master.xml`

**`ddl-auto: validate`** — Phase 3 RC-5 pattern. organization-service declares subset of physical columns (Party + Organization view of the `party` table; not the `class` discriminator's per-row value, which Hibernate manages automatically).

**Fresh-DB race avoidance** (Phase 2 BP-2 lesson): shadow-only, no `<createTable>` body. Grails Liquibase remains sole DDL author.

## 11. Testing

### 11.1 JUnit + TestContainers integration suite

`OrganizationServiceIntegrationTest` (15-18 tests; mirrors Phase 3 pattern at `services/location-service/src/test/java/org/openboxes/location/LocationServiceIntegrationTest.java`):

- `readById_returns200`
- `readById_returns404ForMissing`
- `list_returnsAll` (default pagination)
- `list_filtersByQ` (substring match on name)
- `list_filtersBySingleRoleType`
- `list_filtersByMultiRoleType`
- `list_filtersByActive`
- `list_paginates` (max + offset)
- `readById_returns401WithoutJwt`
- `create_returnsCreatedWithGeneratedCode` (POST without code)
- `create_returnsCreatedWithProvidedCode` (POST with code)
- `create_returns400OnMissingName`
- `readPartyById_returnsBaseShapeForOrganization` (polymorphic; returns PartyDto regardless of discriminator)
- `readPartyById_returnsBaseShapeForBareParty` (no Organization-specific subclass; seed fixture has 1 Party not yet promoted to Organization)
- `partyTypeCache_returnsCachedListOnSecondCall`
- `partyTypeCache_refreshOnEmptyList` (RC-6 fix verification — fails on Phase 3 cache pattern, passes on new)
- `partyRole_findByPartyAndRoleType`

**`@DynamicPropertySource`** (MUST include all 3 properties per Phase 3 CIR R2 lesson):
```java
r.add("spring.jpa.defer-datasource-initialization", () -> "true");
r.add("spring.sql.init.data-locations", () -> "classpath:seed.sql");
r.add("spring.sql.init.mode", () -> "always");
```

**`seed.sql`** seeds:
- 1 PartyType row: code='ORG', party_type_code='ORGANIZATION' (mirrors Grails `changelog-2018-05-30-2315-insert-party-type-data.xml`)
- 1 PartyType row: code='PERSON', party_type_code='PERSON' (for polymorphic test)
- 3 Organization rows (class='org.pih.warehouse.core.Organization', party_type_id='ORG', different role_type sets) *(class value pending A28 verification — placeholder above is the spec's provisional FQCN guess; replace with the observed `SELECT DISTINCT class FROM party` value before T9 implements)*
- 1 bare Party row (class='org.pih.warehouse.core.Party', party_type_id='PERSON', for polymorphic Party-by-id test) *(class value pending A28 verification — placeholder above is the spec's provisional FQCN guess; replace with the observed `SELECT DISTINCT class FROM party` value before T9 implements)*
- 2-3 PartyRole rows per organization (ROLE_SUPPLIER, ROLE_BUYER, etc.)
- 1-2 Address rows (linked indirectly via organization if applicable)

### 11.2 Playwright E2E

~5 specs under `e2e/tests/organization-service.spec.ts`:

1. `GET /api/organization` (list, no filter) returns 200 with `{data: [...]}` via nginx
2. `GET /api/organization/{id}` returns flat DTO matching contract (asserts `partyTypeCode` scalar, not nested `partyType.code`)
3. `POST /api/organization` via AddOrganizationModal flow — modal submits `{name, description}`, response carries id, organization appears in subsequent list query
4. `GET /api/organization/partyType` returns cached list with seed data
5. **Regression**: `GET /api/organizations` (plural) returns 404 (asserts the deletion)
6. **Baseline preservation**: Phase 1+2+3 endpoints still work (`/api/identity/me`, `/api/documents/...`, `/api/location/...`)

CI workflow `.github/workflows/e2e-tests.yml` updated to build organization-service jar + probe health + dump logs on failure (mirrors Phase 1/2/3 BP pattern).

## 12. React URL migration

3 files; 4 call sites total. All `/api/organizations` (plural) → `/api/organization` (singular):

| File | Line | Change |
|---|---|---|
| `src/js/utils/option-utils.jsx` | 191 | `apiClient.get(/api/organizations?q=...)` → `apiClient.get(/api/organization?q=...)` |
| `src/js/utils/option-utils.jsx` | 225 | `apiClient.get(/api/organizations?...)` → `apiClient.get(/api/organization?...)` |
| `src/js/actions/index.js` | 561 | `apiClient.get(/api/organizations?roleType=ROLE_BUYER&active=...)` → singular |
| `src/js/components/locations-configuration/modals/AddOrganizationModal.jsx` | 59 | `const locationUrl = '/api/organizations'` → `'/api/organization'` |

**Response-shape contract**: All 3 files access response fields compatible with flat DTO:
- option-utils.jsx 192-198 / 228-234: `obj.id`, `obj.name`, `obj.code` (flat)
- actions/index.js 561 area: (assume flat per pattern)
- AddOrganizationModal.jsx 71: `response.data.data.id` (flat)

No nested `.partyType.X` / `.roles[i].X` / `.locations[i].X` access. Flat-FK DTO is safe.

**Out-of-scope**: `src/js/api/services/OrganizationApi.js` calls `${GENERIC_API}/organization/${id}` (= `/api/generic/organization/{id}` → Grails GenericApiController). This is NOT a Phase 4 surface; the generic API path stays alive unchanged. Documented in §9 route map.

## 13. Known issues / accepted as out of scope (with user signoff)

- **`/api/organizations` (plural) is a hard URL deletion (FD#4)**. Any external integration, browser bookmark, or undocumented client hitting the old plural URL receives 404. User-approved at brainstorming. No deprecation period.
- **OrganizationIdentifierService port includes Grails' TODO-marked bugs verbatim** (line 42-43: `suffix++` produces `':'` when suffix='9'; line 49: degrades when length exceeds maxSize). Phase 4 ports, doesn't refactor. Bug fix is a Phase X carry-forward.
- **Read-after-write across processes**. LoadDataService bootstrap creates Organizations via GORM; organization-service JPA reads see them on next query (no transactional handoff). Acceptable: bootstrap is one-shot at app boot with no concurrent readers. Migration/Import are admin-rare with manual triggering.
- **Flat FK-only DTO is a behavior departure (FD#3)**. Consumers reading nested `partyType.code` / `roles[i].roleType.name` from Grails responses must accept flat shape from org-service. The 3 migrated React files don't navigate nested fields — verified at A13.
- **PartyType IDs are hardcoded in Grails seed** (id=1 for 'ORG', id=2 for 'PERSON' at `changelog-2018-05-30-2315-insert-party-type-data.xml:10,27`). organization-service looks up PartyType by `code`, never by id (defensive against ID drift). Documented in §11.1 seed.sql block.
- **`Organization.sequences` is NOT modeled** in JPA (YAGNI). The `organization_sequences` join table stays in the DB for Grails legacy reads.
- **PartyRole `roleType` is a raw `String` column** (not a JPA enum). Rationale: Grails `RoleType` has 60+ values; mirroring as a subset enum + `@Enumerated(STRING)` + EAGER fetch on `Party.roles` would throw on first read of any stored value outside the subset. Raw String avoids the synchronization debt entirely. Trade-off: typos in `?roleType=` filter values silently return empty results instead of 400 (acceptable — consumers controlling the filter string already know the valid values; an unknown one returning [] is the same UX as a known one with no rows).

## 14. Risks

- **SINGLE_TABLE inheritance is a first-of-its-kind pattern in the services portfolio.** Phase 2 set up JOINED; Phase 4 sets up SINGLE_TABLE. The risk: discriminator-driven polymorphic queries (e.g., "give me all Party rows") may surprise. Mitigation: §11.1 polymorphic Party-by-id test exercises the discriminator path; T7 quality-reviewer cross-references Phase 2 Person.java for the JOINED comparison.
- **Cross-context Grails write paths don't move.** Implication: organization-service is NOT the sole writer to `party`/`party_role`/`address`. Schema changes (Phase 5+) must respect dual-writer constraint (additive-only). Documented as part of the Phase 4 SDD plan's pre-merge checklist.
- **OrganizationIdentifierService port is non-trivial.** ~50 lines + Apache Commons dependency. Implementation risk: subtle behavioral divergence from Grails algorithm under edge cases (empty name, all-special-chars, code-suffix-overflow). Mitigation: T6 implementer ports literally + writes JUnit tests for the 4-5 Grails comment-documented edge cases as the spec defines them; quality-reviewer cross-references Grails source line-by-line.
- **AddOrganizationModal POST shape divergence.** React sends `{name, description}` only; server must default `code` (via OrganizationIdentifierService) and `partyType` (via `PartyTypeCache.findByCode("ORG")`). Implementation risk: server-side validation rejects payload that Grails accepted. Mitigation: T1 audit captures Grails create() request/response shape as a regression baseline at `/tmp/grails-organization-create.json`; T10 Playwright Test #3 asserts the modal flow end-to-end.
- **`/api/organizations` 404 is irreversible without re-adding the controller.** If a hidden caller surfaces in CI or production, the only recovery is re-instating OrganizationApiController. Mitigation: T1 audit runs `grep -rE "/api/organizations" /home/yv01p/openboxes/{src,grails-app}` for an exhaustive callsite search before T8 deletion.

## 15. Out of scope (explicitly NOT in Phase 4)

- No write decoupling of LoadDataService / MigrationService / LocationImportDataService — they keep direct GORM writes via shared DB (Phase X / Phase 7+)
- No Grails `OrganizationController` GSP admin migration — Phase 12
- No PUT/DELETE endpoints on organization-service — YAGNI
- No CSV download endpoint — Grails keeps it
- No PartyType / Address write endpoints — reference data + admin-managed via Grails GSP
- No Supplier / Donor / Shipper entities — see FD#5
- No `jwt-auth-common` shared library extraction — Phase X (FD#6)
- No modeling of `Organization.sequences` or `organization_sequences` table — YAGNI
- No modeling of `Organization.hasMany [locations]` relationship — drop from DTO; 2-call pattern via Phase 3 location-service if needed
- No fix for OrganizationIdentifierService's TODO-marked bugs — port verbatim, Phase X cleanup
- No bin/zone admin UI work — that's Phase 3 Phase-X item #4, unrelated to organization

## 16. Spec back-ports to parent design

- **Parent design §4.3 line 44** lumps Organization + Party + PartyRole + PartyType + Supplier + Shipper + Address + Donor as "organization-service" scope (8 entities). Per FD#5, real scope is 5 entities (Party + Organization + PartyRole + PartyType + Address). Supplier is a SQL view; Donor lives in donation/ package; Shipper lives in shipping/ package. Recommended back-port: update parent design §4.3 line 44 to enumerate the actual 5 Phase 4 entities + a footnote on the 3 deferred sub-context items.
- **Phase X carry-forward addendum**: parent design §6 row 7 (Phase 7 Ordering + sagas) should explicitly note that LoadDataService / MigrationService / LocationImportDataService organization-write paths migrate to sagas in Phase 7+ (per §7.1 above). This expands the Phase 7 scope by one bullet.

## 17. Verified assumptions

27 assumptions listed cold before verification. Verification touched real files under `grails-app/`, `src/js/`, `services/`, `docker/`, `docs/`. See "Verification summary" section of `handoffs/2026-05-28_<timestamp>_phase-4-brainstorming.md` for full evidence trace (paths + line numbers).

| # | Assumption | Result | Evidence |
|---|---|---|---|
| A1 | `Organization.groovy` extends Party with code/name/description/defaultLocation/active/dateCreated/lastUpdated; sequences Map; hasMany locations | ✅ + 2 cross-context methods to drop | `Organization.groovy:17,22-29,59-78` |
| A2 | Party.groovy: partyType ManyToOne, roles OneToMany (cascade on Organization not Party), dateCreated/lastUpdated | ✅ cascade lives on Organization not Party (minor clarification) | `Party.groovy:14-24`, `Organization.groovy:36` |
| A3 | PartyRole/PartyType/Address field sets | ⚠️ Added fields: PartyType.partyTypeCode enum; PartyRole.startDate/endDate; Address.description | `PartyType.groovy:19`, `PartyRole.groovy:18-19`, `Address.groovy:22` |
| A4 | Physical schema: separate party + organization tables (JOINED) | ❌ Schema is SINGLE_TABLE: ONE `party` table with `class` discriminator + all Org cols | `changelog-create-tables.groovy:1586-1620` (line 1607 `class VARCHAR(255) NOT NULL`) |
| A5 | OrganizationIdentifierService exists; portable | ✅ portable but ~50 lines; uses Apache Commons WordUtils + config-driven sizes | `OrganizationIdentifierService.groovy` |
| A6 | Constants.DEFAULT_ORGANIZATION_CODE | ✅ `static final String DEFAULT_ORGANIZATION_CODE = "ORG"` | `src/main/groovy/.../Constants.groovy:164` |
| A7 | OrganizationApiController has 3 actions (list/read/create) | ✅ confirmed | `OrganizationApiController.groovy:20-37` |
| A8 | OrganizationService methods enumerated | ✅ confirmed: selectOrganizations, find/Or/CreateOrganization (2 overloads), findOrganization, saveOrganization, createOrganization, findOr/Create{Buyer,Supplier}Organization, getOrganizations | `OrganizationService.groovy` |
| A9 | UrlMappings.groovy /api/organizations mapping is locatable | ⚠️ Served by GENERIC mapping `/api/${resource}s` line 935 (shared across all REST APIs); deleting OrganizationApiController is sufficient — do NOT touch URL mapping line | `UrlMappings.groovy:935-948` |
| A10 | GSP admin doesn't route through /api/organizations | ✅ OrganizationController serves `/organization/*` via Grails default mapping `/$controller/$action?/$id?` at UrlMappings.groovy:37 | inferred from absence of explicit mapping |
| A11 | Only 3 React files use /api/organizations | ✅ confirmed: option-utils.jsx (2 hits), actions/index.js (1 hit), AddOrganizationModal.jsx (1 hit). OrganizationApi.js calls `/api/generic/organization/{id}` (different endpoint, out of scope) | grep |
| A12 | AddOrganizationModal POST body | ✅ `{name, description}` only | `AddOrganizationModal.jsx:62-64` |
| A13 | No nested response navigation | ✅ all 3 files access flat fields (obj.id, obj.name, obj.code, response.data.data.id) | `option-utils.jsx:192-198,228-234`, `AddOrganizationModal.jsx:71` |
| A14 | No cross-context Grails writers beyond LoadData/Migration/LocationImport | ✅ confirmed; Order/Invoice/ProductSupplier only READ | grep |
| A15 | Grails internal readers tolerate Organization entity staying alive | ✅ confirmed; they use GORM Organization.get() directly, not /api/organizations | inferred |
| A16 | Phase 2 User/Person JPA inheritance template | ✅ `@Inheritance(JOINED)` at Person.java:12 + `@PrimaryKeyJoinColumn` at User.java:14. (Phase 4 uses different strategy per FD#2.) | `Person.java:12`, `User.java:14-15` |
| A17 | location-service security copy portability + 401 exceptionHandling | ✅ confirmed line 26 verbatim | `services/location-service/.../SecurityConfig.java:26` |
| A18 | TestContainers @DynamicPropertySource template | ✅ all 3 props present | `services/location-service/.../LocationServiceIntegrationTest.java:29-41` |
| A19 | Liquibase shadow-changelog tableExists pattern | ✅ confirmed: `<preConditions onFail="MARK_RAN"><tableExists/></preConditions>` + empty body | `services/location-service/.../changelog-shadow-create-location.xml:8-16` |
| A20 | Port 8084 unused | ✅ confirmed; 8080/8081/8082/8083 occupied in docker-compose-base.yml | `docker/docker-compose-base.yml:8-86` |
| A21 | nginx app.conf supports new exact+prefix block | ✅ structure tolerates additions | `docker/nginx/conf.d/app.conf` |
| A22 | services/organization-service module doesn't exist | ✅ confirmed; settings.gradle has `include 'document-service' / 'identity-service' / 'location-service'` | ls; `services/settings.gradle` |
| A23 | Supplier is a SQL view | ✅ `CREATE OR REPLACE VIEW supplier AS (...)` | `grails-app/migrations/views/supplier-list.sql` |
| A24 | Donor + Shipper safe to defer | ✅ Donor refs only in donation/; Shipper refs only in shipping/ + 1 UI taglib | grep |
| A25 | No missing entity in core/ | ✅ confirmed | `ls grails-app/domain/org/pih/warehouse/core/` |
| A26 | ddl-auto:validate tolerance | ✅ Phase 3 retro line 50 documents working pattern; reuse | Phase 3 retro |
| A27 | PartyType has rows in seed | ✅ guarded `SELECT COUNT(*) FROM party_type WHERE code = 'ORG'` precondition + insert; id=1 hardcoded for ORG | `changelog-2018-05-30-2315-insert-party-type-data.xml:6,10,12` |
| A28 | `party.class` discriminator values Grails writes for BOTH bare Party and Organization rows (no GORM override in Party.groovy or Organization.groovy mapping blocks; default is Hibernate-implementation-dependent) | ⏳ PENDING T1 EMPIRICAL VERIFICATION | Run `SELECT DISTINCT class FROM party` against a Grails-bootstrapped DB; pin `@DiscriminatorValue` in **both §5.1 (Party) and §5.2 (Organization)**, and both `class=` values in **§11.1 seed.sql (lines 469 and 470)**, to the observed values. **Blocks T2.** |

## 18. Estimated effort

~12-13 SDD tasks (matches Phase 3 cadence; the write+delete steps fold into existing slots rather than adding new tasks):

- T1: audit + live-probe baseline (Grails `/api/organizations` GET/POST capture to `/tmp/grails-organization-*.json`)
- T2: bootstrap module (bare Spring Boot at 8084; Liquibase disabled)
- T3: Liquibase shadow changelogs (4 files: party/party_role/party_type/address)
- T4: JPA entities + repositories (SINGLE_TABLE inheritance pattern)
- T5: security (4th JwtCookieAuthFilter/JwtService/SecurityConfig copy)
- T6: services (PartyTypeCache, OrganizationService, PartyService, PartyRoleService, OrganizationIdentifierService port)
- T7: REST controllers (7 endpoints — GET ×6 + POST ×1)
- T8: nginx routing + delete OrganizationApiController.groovy
- T9: TestContainers integration tests (15-18 tests)
- T10: Playwright E2E + React URL migration (3 files, 4 call sites)
- T11: CI workflow + done-gate
- T12 (optional): hotfix slot (Phase 3 used this for nginx exact-match)
- T13: retrospective

~2 weeks of focused work for one developer.
