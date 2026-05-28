---
date: 2026-05-27
phase: 3 (Location slice — read-only)
parent_spec: docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md
predecessor_retro: docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md
predecessor_tag: phase-2-identity (at 2e70b7c91)
target_tag: phase-3-location
estimated_duration: 3-7 days
---

# Phase 3 — Location-Service Design (Read-Only Slice)

## 0. TL;DR

Phase 3 stands up `location-service` as a new Spring Boot 3.x / Java 21 module exposing **read-only** GET endpoints for the 3 location-bounded entities (Location, LocationGroup, LocationType) plus the 2 M:N supported_activities tables. The service is GET-only — all writes stay on Grails so existing in-JVM application events (InventorySnapshotEvent, RefreshProductAvailabilityEvent) keep firing, preserving inventory snapshot + product availability freshness. location-service uses a NEW URL prefix `/api/location/*` (singular) reserved for service-to-service consumption by Phase 4+ slices; existing `/api/locations/*` (plural) React + Grails traffic routes to the Grails app unchanged. The slice produces no React URL changes, no production user flows changing behavior, no admin UI rewrites. Tag `phase-3-location`. Write ownership migration deferred to Phase X (post-Phase 6 when inventory-service can subscribe to a LocationChangedEvent saga for snapshot refresh) — same deferral pattern as Phase 1 (Document.groovy deferred) and Phase 2 (User/Person/Role.groovy deferred).

## 1. Scope

**Entities owned by location-service** (3, not 4 — parent design enumerated `LocationStatus` but neither the domain class nor the table exist; see Verified Assumptions §17 finding F1+F2):

- `Location` — warehouses, suppliers, depots, plus bins/zones (data-level distinguished by LocationType.locationTypeCode)
- `LocationGroup` — hierarchical groupings
- `LocationType` — fixed enum-like reference table

**M:N support tables also owned (read-only):**

- `location_supported_activities` (per-location activity grants — `RECEIVING`, `SHIPPING`, etc.)
- `location_type_supported_activities` (per-type activity defaults)

**Not in scope (carve-outs):**

- `LocationRole` (stays with identity-service per Phase 2; parent design §4.3)
- `LocationDimension` (reporting-service concern; parent design §4.3)
- Bin/zone configuration UI (LocationsConfigurationController + 6 React modals — AddBinModal, AddZoneModal, AddLocationGroupModal, ImportBinModal, LocationDetails, ZoneAndBinLocations) — admin-rare, deferred to Phase X
- `/api/internalLocations/*` (bin/zone REST surface) — stays on Grails
- All Location/LocationGroup/LocationType WRITE paths — stay on Grails (see §13)

**Done-state, one paragraph**: location-service runs in compose as the 6th container; validates obx_token cookies via shared HMAC HS256 secret; serves `/api/location/{id}`, `/api/location?type=...`, `/api/location/group/{id}`, `/api/location/group`, `/api/location/type`, `/api/location/type/{id}`, `/api/location/supportedActivities` GETs; internal locations (BIN_LOCATION/INTERNAL) filtered out of GET responses by default; ZONE locations pass through (Grails parity). nginx routes `/api/location/` (singular) to location-service. `/api/locations/` (plural) and `/api/internalLocations/` continue routing to Grails app unchanged. Grails Location.groovy stays alive as both read and write. Tagged `phase-3-location` on `main`.

## 2. Service Architecture

| Concern | Choice |
|---|---|
| Runtime | Spring Boot 3.x, Java 21, Spring Data JPA + Hibernate 6 |
| Build | Gradle 8.x via `services/gradlew`; module added to `services/settings.gradle` |
| Module location | `services/location-service/` |
| Port (internal only; `expose:` not `ports:`) | 8083 |
| Container name | `openboxes-location-service` |
| `depends_on` | `db: service_healthy` + `app: service_healthy` (Grails Liquibase finishes first per Phase 2 BP-2 lesson) |
| nginx `depends_on` | adds `location-service: service_healthy` |
| Healthcheck | Spring Boot Actuator `/actuator/health` (3-attempt curl loop, like identity-service) |
| Schema migrations | Per-service Liquibase under `services/location-service/src/main/resources/db/changelog/`; shadow pattern (`tableExists` precondition + empty body) from commit 1 per Phase 2 retro lesson #5 (template at §4) |
| Auth | jjwt 0.12+; `JwtCookieAuthFilter` duplicated from document-service / identity-service (Phase X DRY refactor candidate) |
| API contract | springdoc-openapi at `/v3/api-docs` |
| Testing | JUnit 5 + TestContainers MariaDB (mirror identity-service test setup) |

**Container layout after Phase 3 (6 containers):**
```
openboxes-db ────────────► openboxes-app (Grails) ────────────► openboxes-nginx
                            └─► openboxes-document-service ─────┤
                            └─► openboxes-identity-service ─────┤
                            └─► openboxes-location-service ─────┘   (NEW)
```

## 3. HTTP API Surface

All endpoints under `/api/location/*` (singular — new prefix, NOT `/api/locations/` plural which stays Grails-owned). All GET-only. All require valid `obx_token` JWT cookie.

| Endpoint | Purpose | Filters |
|---|---|---|
| `GET /api/location/{id}` | Read location by id | Returns 404 if id is an internal location (LocationType.locationTypeCode in BIN_LOCATION/INTERNAL, per Grails `LocationTypeCode.listInternalTypeCodes()`) unless `?includeInternal=true` query param. ZONE locations are NOT filtered (Grails parity). |
| `GET /api/location?type={typeCode}&active=true&parentId={id}` | Filtered list | Internal locations (BIN_LOCATION/INTERNAL) excluded by default; `&includeInternal=true` to include. ZONE locations not filtered. |
| `GET /api/location/group/{id}` | Read group by id | — |
| `GET /api/location/group` | List groups | — |
| `GET /api/location/type` | List all LocationTypes | Served from LocationTypeCache |
| `GET /api/location/type/{id}` | Read type by id | Served from LocationTypeCache |
| `GET /api/location/supportedActivities` | List all supported-activity codes | Served from SupportedActivitiesEnum (hardcoded enum mirror of Grails ActivityCode) |
| `GET /actuator/health` | Healthcheck (anonymous) | — |
| `GET /v3/api-docs` | OpenAPI spec (anonymous) | — |

**Response shape**: Field-for-field match with existing Grails `LocationApiController` GET response shapes (so Phase 4+ callers can switch from `/api/locations/{id}` → `/api/location/{id}` with no payload reshape required).

**No POST, PUT, DELETE.** All write traffic stays on Grails (see §13).

## 4. Data Model + Liquibase

**JPA entities** (5 entities; no inheritance hierarchies per F15):

| Entity | Table | Notes |
|---|---|---|
| `Location` | `location` | Self-referential FKs: `parent_location_id` (parent), `zone_id` (containing zone). `@ManyToOne LocationType locationType`, `@ManyToOne LocationGroup locationGroup`. `@ElementCollection Set<String> supportedActivities` mapping `location_supported_activities`. |
| `LocationGroup` | `location_group` | Simple entity (id, name, address_id, etc.) |
| `LocationType` | `location_type` | id, name, location_type_code, sort_order, description. `@ElementCollection Set<String> supportedActivities` mapping `location_type_supported_activities`. |
| `LocationActivityGrant` (implicit, via `@ElementCollection`) | `location_supported_activities` | (location_id, supported_activities_string) |
| `LocationTypeActivityGrant` (implicit, via `@ElementCollection`) | `location_type_supported_activities` | (location_type_id, supported_activities_string) |

**Liquibase shadow changelogs** (Phase 2 retro lesson #5 — shadow from Day 1):
- `changelog-shadow-create-location.xml`
- `changelog-shadow-create-location-group.xml`
- `changelog-shadow-create-location-type.xml`
- `changelog-shadow-create-location-supported-activities.xml`
- `changelog-shadow-create-location-type-supported-activities.xml`

Each follows the document-service shadow pattern:
```xml
<changeSet id="phase3-shadow-create-X" author="openboxes-location">
    <preConditions onFail="MARK_RAN" onFailMessage="X table not found — Grails Liquibase must run first">
        <tableExists tableName="X"/>
    </preConditions>
    <comment>
        Shadow for X table. Grails Liquibase owns table creation.
        location-service uses spring.jpa.hibernate.ddl-auto=validate to prove entity-mapping correctness.
        tableExists works for both entity tables and pure M:N join tables (which have no `id` column).
    </comment>
    <!-- No body: table already exists per the precondition. -->
</changeSet>
```

**`spring.jpa.hibernate.ddl-auto: validate`** confirms entity-mapping correctness against existing Grails-created schema. Same precedent as identity-service post-BP-2.

## 5. Authentication

- JWT obx_token cookie validated via shared HMAC HS256 secret (`OPENBOXES_JWT_SECRET` env var, already wired in `docker-compose-base.yml` per Phase 2)
- `JwtCookieAuthFilter` copied from identity-service (Phase 3 mirrors the existing duplication; carry-forward in retrospective for Phase X DRY refactor)
- `/actuator/health` + `/v3/api-docs` anonymous-passthrough
- All other endpoints require valid token; no role-based authorization in location-service (read-only endpoints; any authenticated user can read all non-internal location data)
- Note on Grails parity: this matches Grails `LocationApiController.read()` (single-id GET at `grails-app/controllers/org/pih/warehouse/api/LocationApiController.groovy:42-45`), but Grails `LocationApiController.list()` at lines 73-92 performs role-based result filtering (driven by `params.locationChooser`, `userService.isUserRequestor()`, and `currentUser.locationRoles`) that location-service does NOT replicate. Phase 4+ list callers via `/api/location?...` will see unfiltered results. If any consumer migrates from `/api/locations/...` list and depends on the filtered shape, surface it in the Phase X retro before that migration.
- Anonymous-user fallback NOT supported (matches identity-service pattern)

## 6. Caching

- **LocationTypeCache** (refresh-on-miss `Map<String, LocationType>`, RoleTypeCache pattern per Phase 2 retro lesson)
  - `@PostConstruct refresh()` loads all LocationType rows once
  - `getById(id)`: cache hit OR call `refresh()` then retry
  - Used by `GET /api/location/type/*` endpoints AND internally by `Location` DTO assembly when serializing `location.locationType.name` etc.
  - Single-node safe (no distributed cache complexity)
- **SupportedActivitiesEnum** (hardcoded Java enum mirroring Grails `ActivityCode` at `src/main/groovy/org/pih/warehouse/core/ActivityCode.groovy`, 31 values)
  - No DB query; values returned directly from enum reflection (same pattern as Grails `LocationApiController.supportedActivities()` at `grails-app/controllers/org/pih/warehouse/api/LocationApiController.groovy:144-147`)
  - Used by `GET /api/location/supportedActivities`
  - Synchronization debt: if Grails adds an ActivityCode value, location-service's enum must be updated in lock-step (see §15)
- **LocationGroup NOT cached** (admin-managed; can grow; queried filtered — direct repo lookups)

## 7. Cross-Service Interactions

| From | To | Pattern | Phase 3 behavior |
|---|---|---|---|
| Phase 4+ slices | location-service | HTTP GET `/api/location/*` | Phase 3 readies the endpoints; no current Phase 3 consumer |
| identity-service | location-service | (none currently) | identity-service KEEPS reading `location.active` via shared-DB JPA per Phase 2 Option 1 decision; no HTTP call to location-service. Accepted ddl-auto:validate cross-service entity-mapping coupling as documented Phase X debt. |
| Grails app | location-service | (none) | Grails keeps using GORM `Location.get(id)` against shared DB; no HTTP call to location-service |
| React frontend | location-service | (none directly) | React keeps calling `/api/locations/*` (plural) → Grails app, unchanged |
| location-service | identity-service or others | (none) | Phase 3 has no outbound HTTP dependencies |

## 8. URL Routing

**Existing routes (unchanged):**
- `/api/locations/*` (plural) → Grails app (React + admin + bulk import + bin/zone)
- `/api/internalLocations/*` → Grails app (bin/zone REST)
- `/api/identity/*` → identity-service (Phase 2)
- `/api/documents/*` → document-service (Phase 1)
- `/api/*` catch-all → Grails app
- `/openboxes/*` → Grails app

**New route (Phase 3 addition):**
- `/api/location/*` (singular) → location-service (port 8083)

nginx config diff:
```nginx
# Add this block BEFORE the /api/ catch-all
location /api/location/ {
    proxy_pass http://location-service:8083/;
}
```

(`location /api/location/` is more specific than `/api/locations/` per nginx prefix matching — verify the longer-prefix-wins logic doesn't accidentally route `/api/locations/x` to location-service. nginx routes via longest matching prefix; `/api/location/` would match `/api/locations/...` because the prefix `/api/location` is a substring. Mitigation: trailing slash in the route. The route `/api/location/` matches paths starting with exactly `/api/location/`, NOT `/api/locations`. Verified pattern.)

**Two URLs in parallel during Phase 3 → Phase X transition:**
- `/api/locations/*` (plural, Grails) — used by React + writes + bin/zone
- `/api/location/*` (singular, location-service) — used by future Phase 4+ slices for GETs

Consolidation deferred to Phase X (when React migrates to /api/location/ and Grails LocationApiController is deleted).

## 9. Internal-Location Filtering at Application Layer

Bins and zones are rows in the SAME `location` table, distinguished by `LocationType.locationTypeCode`:
- Real locations: `DEPOT`, `WAREHOUSE`, `SUPPLIER`, `CUSTOMER`, `PHARMACY`, etc.
- Internal locations: `BIN_LOCATION`, `INTERNAL` (per Grails `LocationTypeCode.listInternalTypeCodes()` at `src/main/groovy/org/pih/warehouse/core/LocationTypeCode.groovy:55-57`) — filtered by default
- ZONE locations: NOT in the internal filter set (Grails parity — `Location.listNonInternalLocations()` at `grails-app/domain/org/pih/warehouse/core/Location.groovy:368-378` returns zones; preserving drop-in behavior)

location-service's `LocationRepository` adds a JPA criterion to filter out internal types by default:
```java
@Query("SELECT l FROM Location l WHERE l.id = :id AND l.locationType.locationTypeCode NOT IN :internalTypes")
Optional<Location> findByIdExcludingInternal(@Param("id") String id, @Param("internalTypes") Set<String> internalTypes);
```

Endpoints accept `?includeInternal=true` query param to bypass the filter (for admin/debug callers).

The list of internal type codes is sourced from a Java enum in location-service mirroring `LocationTypeCode.listInternalTypeCodes()` from Grails. Synchronization is a known carry-forward (if Grails adds a new internal type, location-service's enum must be updated in lock-step; documented in retrospective).

## 10. Tests

**JUnit + TestContainers** at `services/location-service/src/test/java/org/openboxes/location/LocationServiceIntegrationTest.java` — target ~15 tests:

1. `GET /api/location/{id}` returns 200 with payload for existing location
2. `GET /api/location/{id}` returns 404 for non-existent id
3. `GET /api/location/{id}` returns 404 for internal-location (BIN_LOCATION/INTERNAL) id (default filter)
4. `GET /api/location/{id}?includeInternal=true` returns 200 for internal-location (BIN_LOCATION/INTERNAL) id
5. `GET /api/location?type=DEPOT` returns matching locations only
6. `GET /api/location?active=false` returns inactive locations
7. `GET /api/location` returns no internal locations (BIN_LOCATION/INTERNAL) by default; ZONE locations still included (Grails parity)
8. `GET /api/location/group/{id}` returns 200 with payload
9. `GET /api/location/group` returns list
10. `GET /api/location/type` returns all types from cache
11. `GET /api/location/type/{id}` returns 404 for non-existent id
12. `GET /api/location/supportedActivities` returns activity codes from cache
13. LocationTypeCache: first call loads; subsequent calls hit cache
14. JWT missing: 401 unauthorized
15. JWT invalid signature: 401 unauthorized

Seed.sql fixture: 3-4 real locations + 2 bins + 2 location-types + 1 group + activity grants.

**Playwright E2E** at `e2e/tests/location-*.spec.ts` — target 4-5 tests:

1. `GET /api/location/{id}` via nginx with valid obx_token returns 200
2. `GET /api/location/type` returns expected reference data
3. Regression: `/api/locations/*` (plural) still works via Grails (LocationChooser flow)
4. Regression: `/api/internalLocations/*` still works via Grails (bin/zone admin)
5. Regression: existing Phase 1 + Phase 2 baseline (login, document-service, identity-service) unchanged

## 11. Done Gate

(Mirrors Phase 2 Task 18 pattern)

- Clean rebuild from scratch: `cd services && sudo -E ./gradlew :location-service:bootJar`, `cd docker && sudo docker-compose down && up -d --build`
- 6 containers Up healthy: db, app, document-service, identity-service, location-service, nginx
- `docker exec openboxes-location-service curl -sf localhost:8083/actuator/health` → UP
- 7 GET endpoints return correct shapes (curl smoke test through nginx with valid obx_token)
- `ddl-auto: validate` passes (no entity-schema mismatches in startup logs)
- Grep gates (Phase 3-specific):
  - **No Grails write paths CHANGED** (read-only service introduces no Grails-side rewrites; verify `grep -rn 'new Location(' grails-app/` count unchanged from pre-Phase-3)
  - **No new location-service write endpoints in commit range** (verify location-service has only GET handlers)
  - **identity-service Location.java unchanged** (Phase 2 status quo preserved)
- JUnit pass: all 15 location-service tests green
- Playwright pass: 4-5 new + all baseline (current 24 + new ones = ~29 total)
- 15-minute soak: memory steady, no exceptions in any container log
- CI green at done-gate-green HEAD

## 12. Tag + Retrospective

**Tag**: `phase-3-location` on `main` at done-gate-green HEAD. Pushed after explicit user confirmation per standing per-push gate.

**Retrospective**: `docs/retrospectives/2026-MM-DD-phase-3-location-retrospective.md` (replace MM-DD with done-gate date). Mirror Phase 1 + Phase 2 format:
- YAML frontmatter (date, phase, tag, commit_range, plan, spec_section)
- TL;DR (1 paragraph)
- What worked (Phase 3-specific: read-only pivot decision, bin/zone filtering pattern, two-URL-in-parallel transition pattern)
- Codebase / env gotchas (sub-grouped Build & deploy / Code-level / Container / runtime)
- Process / meta-lessons (the F1-F6 verification findings)
- Forward to Phase 4 (Organization slice — whatever consumes location data)
- Phase X: Location decoupling (§14 of this spec)
- Artifacts (links to spec, plan, audit if any, tag, commits)

## 13. Hybrid Exit State (Read-Only)

After Phase 3 ships:
- **location-service is read-only.** Exposes only GET endpoints under `/api/location/*` (singular).
- **All Location writes stay on Grails.** Single rule, easy to grep-enforce.
- **Grails Location.groovy / LocationGroup.groovy / LocationType.groovy stay alive** as both read AND write paths.
- **In-JVM events (InventorySnapshotEvent, RefreshProductAvailabilityEvent) keep firing** on every Grails-side Location write — inventory snapshots + product availability cache stay fresh.
- **~200 Grails `Location.get(id)` callsites unchanged.** They continue to work via GORM against the shared DB.
- **5 known Grails write paths kept alive** (per the F4 verification):
  - `LocationController` admin GSP CRUD (admin-rare)
  - `LocationApiController.save/update/delete` (HTTP admin)
  - `LocationService.findOrCreateInternalLocation` (called by shipping workflow for receiving locations)
  - `LocationImportDataService` (CSV bulk import)
  - `LocationService` bin/zone management methods (`addToLocations`, `removeFromLocations`, internal location creates at lines 563/569/716/721/723)
- **Bin/zone admin UI stays on Grails** (LocationsConfigurationController + 6 React modals — AddBinModal, AddZoneModal, etc.) — `/api/internalLocations/*` and `/api/locations/binLocations/*` unchanged.

No bounded carve-out enumeration needed — the rule is simply "all location writes Grails-side". Done-gate grep verifies no location-service write paths exist.

## 14. Phase X: Location-Service Decoupling (Deferred)

Phase X picks up Grails Location.groovy + related deletion once these blockers are resolved:

1. **Inventory snapshot saga (Phase 6 prerequisite).** location-service can emit `LocationChangedEvent` to its outbox; inventory-service consumes and runs `productAvailabilityService.updateProductAvailability(...)` + `inventorySnapshotService.updateInventorySnapshots(...)`. Replaces the in-JVM `InventorySnapshotEvent` + `RefreshProductAvailabilityEvent` consumers. Requires saga infrastructure (parent design §4.5; Phase 7+).

2. **Shipping-workflow internal-location creation (Phase 8 prerequisite).** `LocationService.findOrCreateInternalLocation` (called from `CreateShipmentWorkflowController` to create receiving locations during shipment workflow) migrates to shipping-service-owned + sync HTTP call to location-service POST endpoint. Requires location-service to have POST endpoint (added in Phase X).

3. **CSV bulk import migration.** `LocationImportDataService` (admin-rare; CSV parse + batch save) moves to location-service `POST /api/location/importCsv`. Or stays on Grails until Phase 12 (admin-rare; low priority).

4. **Bin/zone configuration UI rewrite.** `LocationsConfigurationController` + 6 React modals (AddBinModal, AddZoneModal, AddLocationGroupModal, ImportBinModal, LocationDetails, ZoneAndBinLocations). Either rewrite as React + location-service POST endpoints OR delete in Phase 12 cleanup.

5. **`/api/locations/*` (plural) → `/api/location/*` (singular) consolidation.** Migrate all ~16 React files that call `/api/locations/*` to new singular prefix. Migrate all ~200 Grails `Location.get(id)` callsites slice-by-slice in Phases 4-11. Once both are done, delete Grails LocationApiController, LocationController, LocationGroupApiController, LocationService, Location.groovy, LocationGroup.groovy, LocationType.groovy, plus `grails-app/views/location/`, `grails-app/views/locationGroup/`, `grails-app/views/locationType/`, plus URL mappings.

6. **identity-service Location.java entity removal.** Once location-service has POST endpoints and identity-service can call location-service for chooseLocation's `location.active` check, identity-service drops its Location.java + LocationRepository + LocationDto. Resolves the Phase 2 → Phase 3 ddl-auto:validate cross-service coupling debt.

**Trigger conditions:**
- Phase 6 shipped (inventory-service + LocationChangedEvent saga)
- Phase 8 shipped (shipping-service + receiving-location creation migration)
- (Optional) Phase 9+ shipped (other slices' Location callers migrated)

**Owner**: TBD. Likely paired with the appropriate Phase X (could be Location-specific OR absorbed into Phase 12 final cleanup).

## 15. Known Issues / Accepted as Out of Scope

- **No write endpoints in Phase 3.** Phase 4+ slices that need to MUTATE Location data continue calling Grails (or stay on direct JDBC). The deferral is intentional (§13 events rationale).
- **identity-service still maps Location as a JPA entity** against the shared DB. After Phase 3, identity-service's Location.java entity coexists with location-service's Location.java entity, both mapping the same table. Schema changes require updating BOTH entities in lock-step. Phase 2 → Phase 3 accepts this debt; Phase X resolves.
- **JwtCookieAuthFilter is duplicated.** Now in 3 services (document, identity, location). DRY violation deferred to Phase X.
- **Internal-type-code list duplicated.** location-service's enum of bin/zone codes mirrors Grails `LocationTypeCode.listInternalTypeCodes()`. If Grails adds a new internal type, location-service's enum must be updated; mitigated by retrospective documentation + future test that exercises both lists.
- **ActivityCode enum duplicated.** location-service's `SupportedActivitiesEnum` mirrors Grails `ActivityCode` enum (31 values at `src/main/groovy/org/pih/warehouse/core/ActivityCode.groovy`). If Grails adds a new activity code, location-service's enum must be updated in lock-step; mitigated by retrospective documentation + future test that exercises both lists. Same pattern as the LocationTypeCode debt above.
- **Parent design enumerated LocationStatus** (entity + table) that doesn't exist. Spec deviation noted in retrospective for parent design correction.
- **No saga infrastructure.** Parent design's saga support arrives in Phase 7+. Phase 3 (read-only) doesn't need it; Phase X (write decoupling) does.

## 16. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| nginx prefix routing accidentally routes `/api/locations/x` to location-service | Low | Trailing-slash + verify routing during done-gate; `/api/location/` (with trailing slash) does NOT match `/api/locations/`. Add explicit smoke test. |
| ddl-auto: validate fails at boot because identity-service's Location.java and location-service's Location.java diverge | Medium | Both services must declare entities with IDENTICAL `@Column` annotations matching the existing schema. Done-gate validates both services boot. Phase 3 plan task explicitly diffs the two entity classes before commit. |
| Bin/zone filter misses a type code; production users see internal locations in GET responses | Low | Done-gate test exercises filter with realistic prod-like seed data. Retrospective notes the manual sync between location-service enum and Grails `LocationTypeCode.listInternalTypeCodes()`. |
| Phase 3 has no current consumer; "infrastructure-only" slice ships without proving value | Accepted | Per parent design ordering; sets foundation for Phase 4+. Same pattern as Phase 1 (document-service had no React migration in Phase 1 — that was Phase 2's job). |

## 17. Verified Assumptions

The following 15 load-bearing assumptions were verified against the codebase (commit `2e70b7c91`) before this spec was committed. Findings F1-F6 changed the design; F1-F2 prompted the entity-count correction, F4+F5 prompted the read-only pivot, F3 added the bin/zone filtering design.

| # | Assumption | Result |
|---|---|---|
| A1 | LocationGroup/Type/Status Grails domain classes exist | **F1: 3 of 4 exist; LocationStatus.groovy does NOT.** Found Location, LocationGroup, LocationType, LocationRole (identity-owned), LocationDimension (reporting-owned). Design corrected to 3 entities. |
| A2 | 4 tables (location, location_group, location_type, location_status) exist | **F2: `location_status` table does NOT exist.** Found location, location_group, location_type, location_role, location_supported_activities, location_type_supported_activities, location_dimension. Design adds the 2 M:N activity tables. |
| A3 | `/api/locations/*` URL mappings resolve to LocationApiController | ✅ Verified at `grails-app/controllers/.../UrlMappings.groovy:158-188`. |
| A4 | React uses `/api/locations/*` (plural) consistently | ✅ Verified — 16 React files; no singular `/api/location/` usage. Design's "/api/location/ singular for service-to-service" is collision-free. |
| A5 | identity-service maps Location as JPA entity | ✅ Verified — `services/identity-service/src/main/java/org/openboxes/identity/entity/Location.java` exists; `AuthService.java:65,83` reads `locationRepository.findById(locationId)`. Option 1 (stay-with-identity for location_role) is implementable. |
| A6 | Bins/zones in same `location` table OR separate | **F3: Same `location` table.** `location.parent_location_id` + `location.zone_id` self-FKs; distinguished by `LocationType.locationTypeCode`. Design adds application-layer filtering (§9). |
| A7+A14 | No cross-context atomic writes touching location | **F4: 7+ apparent cross-context callers; refined analysis shows MOST are transient constructors not real writes.** Real writes are localized to ~5 paths all in `core/` package (LocationService.findOrCreateInternalLocation, LocationApiController.save, LocationImportDataService, LocationController GSPs, LocationService bin/zone mgmt). Design pivot: all writes stay Grails-side. |
| A8 | LocationApiController endpoints map cleanly | ✅ Catalog confirmed: LocationApiController = 287 LOC, LocationService = 725 LOC, LocationController = 435 LOC. Read-only design avoids migrating most of this. |
| A9 | Liquibase shadow pattern works (no FK ordering) | ✅ Verified — shadow pattern doesn't execute DDL, FK ordering irrelevant. |
| A10 | document-service + identity-service templates stable | ✅ Both present at `services/document-service/` + `services/identity-service/`. |
| A11 | services/settings.gradle controls module inclusion | ✅ Verified — current content: `include 'document-service'` + `include 'identity-service'`. Phase 3 adds `include 'location-service'`. |
| A12 | JwtCookieAuthFilter is shareable | **F6: Duplicated** in `services/document-service/.../security/JwtCookieAuthFilter.java` AND `services/identity-service/.../security/JwtCookieAuthFilter.java`. Phase 3 mirrors duplication; retrospective carry-forward. |
| A13 | nginx config supports prefix routing | ✅ Verified — `docker/nginx/conf.d/app.conf` already has `/api/identity` + `/api/documents` blocks before `/api/` catch-all. Phase 3 adds `/api/location` block in same pattern. |
| A15 | Location has no inheritance hierarchy | ✅ Verified — `Location.groovy:25` `class Location implements Comparable<Location>, java.io.Serializable`. No inheritance. **BUT discovered F5: Location.afterInsert/afterUpdate/afterDelete publishes Grails events** (InventorySnapshotEvent, RefreshProductAvailabilityEvent) consumed by InventorySnapshotEventService + RefreshProductAvailabilityEventService — these update product availability cache + inventory snapshots. Critical input to the read-only pivot decision (§13). |

## 18. Artifacts

- **Parent migration design**: `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`
- **Phase 1 design (precedent)**: implicit in parent §9
- **Phase 1 retrospective (precedent)**: `docs/retrospectives/2026-05-26-phase-1-document-retrospective.md`
- **Phase 2 design (predecessor)**: `docs/specs/2026-05-26-phase-2-identity-service-design.md`
- **Phase 2 retrospective (predecessor)**: `docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md`
- **Phase 2 plan (template structure for Phase 3 plan)**: `docs/plans/2026-05-26-phase-2-identity-service-implementation-plan.md`
- **Predecessor tag**: `phase-2-identity` at `2e70b7c91`
- **Target tag**: `phase-3-location`

## 19. Next Steps

Per the standing brainstorming → spec → CIR → plan pattern:
1. **CDR Round 1+** on this spec (via `critical-design-review` skill) — adversarial review for issues before plan write
2. **Update spec** based on CDR findings (via `update-design-doc` skill)
3. **TWP** to produce implementation plan (via `thorough-writing-plans` skill)
4. **CIR Round 1+** on the plan (via `critical-implementation-review` skill)
5. **Update plan** based on CIR findings (via `update-implementation-plan` skill)
6. **SDD execution** (via `superpowers:subagent-driven-development` skill, sonnet implementers per task; same pattern as Phase 2)
