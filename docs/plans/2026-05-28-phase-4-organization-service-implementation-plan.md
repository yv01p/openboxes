# Phase 4 Organization-Service Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Source spec:** `docs/specs/2026-05-28-phase-4-organization-service-design.md` (commit SHA: `436c555a15500380fdbca52d9fe9069607c1f6fe`)

**Goal:** Stand up `organization-service` as the 7th docker container (port 8084) owning the React-facing HTTP surface for the Party hierarchy (`Party` + `Organization extends Party` + `PartyRole` + `PartyType` + `Address`). Serves 6 GET endpoints + 1 POST under a new `/api/organization/*` (singular) prefix. Deletes `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy`; migrates 4 React call sites in 3 files from `/api/organizations` (plural) → `/api/organization` (singular). Grails OrganizationController (GSP admin), OrganizationService (3 cross-context callers per spec §7.1 + LocationController GSP-admin caller — back-port to spec), and Grails domain entities all stay alive for cross-context Grails consumers; write decoupling deferred to Phase 7+ (sagas).

**Architecture:** New 7th docker container `openboxes-organization-service` on port 8084 sharing the openboxes MariaDB via `spring.jpa.hibernate.ddl-auto=validate`. 4 Liquibase shadow changelogs use `tableExists` precondition (no DDL emitted; no separate `organization` table per FD#2 SINGLE_TABLE inheritance). nginx routes `/api/organization` (exact-match) AND `/api/organization/` (prefix) to organization-service while `/api/organizations` (plural) stays a Grails 404 (controller deleted). JWT cookie validation copied from location-service (4th `JwtCookieAuthFilter` + `JwtService` subset). Flat DTOs expose FK IDs only (FD#3 carry-forward). `Organization extends Party` uses `@Inheritance(SINGLE_TABLE)` + `@DiscriminatorColumn(name="class")` per A28 empirical verification at T1. POST `/api/organization` replaces the deleted Grails endpoint — first React-facing write in an extracted service.

**Tech stack:** Spring Boot 3.3.5 + Java 21 + Hibernate 6 + Spring Data JPA + Spring Security 6 + jjwt 0.12.5 + Liquibase + springdoc-openapi 2.5.0 + JUnit 5 + TestContainers 1.21.3 (MariaDB). All verbatim carry-forward from `services/location-service/build.gradle`.

---

## File Structure

**Create — Module bootstrap:**
- `services/organization-service/build.gradle`
- `services/organization-service/Dockerfile`
- `services/organization-service/src/main/java/org/openboxes/organization/OrganizationServiceApplication.java`
- `services/organization-service/src/main/resources/application.yml`

**Create — Liquibase shadow changelogs (4 — no separate `organization` table per FD#2):**
- `services/organization-service/src/main/resources/db/changelog/db.changelog-master.xml`
- `services/organization-service/src/main/resources/db/changelog/changelog-shadow-create-party.xml`
- `services/organization-service/src/main/resources/db/changelog/changelog-shadow-create-party-role.xml`
- `services/organization-service/src/main/resources/db/changelog/changelog-shadow-create-party-type.xml`
- `services/organization-service/src/main/resources/db/changelog/changelog-shadow-create-address.xml`

**Create — JPA entities + enum mirror + repositories:**
- `services/organization-service/src/main/java/org/openboxes/organization/entity/Party.java`
- `services/organization-service/src/main/java/org/openboxes/organization/entity/Organization.java`
- `services/organization-service/src/main/java/org/openboxes/organization/entity/PartyRole.java`
- `services/organization-service/src/main/java/org/openboxes/organization/entity/PartyType.java`
- `services/organization-service/src/main/java/org/openboxes/organization/entity/Address.java`
- `services/organization-service/src/main/java/org/openboxes/organization/enums/PartyTypeCode.java`
- `services/organization-service/src/main/java/org/openboxes/organization/repository/OrganizationRepository.java`
- `services/organization-service/src/main/java/org/openboxes/organization/repository/PartyRepository.java`
- `services/organization-service/src/main/java/org/openboxes/organization/repository/PartyRoleRepository.java`
- `services/organization-service/src/main/java/org/openboxes/organization/repository/PartyTypeRepository.java`

(No Address repository — Address is JPA-mapped for ddl-auto:validate but not surfaced through any endpoint; no read or write operations in Phase 4 use it. PartyType repository exists for cache refresh; AddressRepository would be unused code.)

**Create — Security:**
- `services/organization-service/src/main/java/org/openboxes/organization/security/JwtCookieAuthFilter.java`
- `services/organization-service/src/main/java/org/openboxes/organization/security/JwtService.java`
- `services/organization-service/src/main/java/org/openboxes/organization/security/SecurityConfig.java`

**Create — Service layer + DTOs:**
- `services/organization-service/src/main/java/org/openboxes/organization/service/OrganizationService.java`
- `services/organization-service/src/main/java/org/openboxes/organization/service/PartyService.java`
- `services/organization-service/src/main/java/org/openboxes/organization/service/PartyTypeCache.java`
- `services/organization-service/src/main/java/org/openboxes/organization/service/PartyRoleService.java`
- `services/organization-service/src/main/java/org/openboxes/organization/service/OrganizationIdentifierService.java`
- `services/organization-service/src/main/java/org/openboxes/organization/dto/OrganizationDto.java`
- `services/organization-service/src/main/java/org/openboxes/organization/dto/PartyDto.java`
- `services/organization-service/src/main/java/org/openboxes/organization/dto/PartyTypeDto.java`
- `services/organization-service/src/main/java/org/openboxes/organization/dto/PartyRoleDto.java`
- `services/organization-service/src/main/java/org/openboxes/organization/dto/AddressDto.java`
- `services/organization-service/src/main/java/org/openboxes/organization/dto/CreateOrganizationCommand.java` (POST request body record)

**Create — REST controllers (3 controllers / 7 endpoints):**
- `services/organization-service/src/main/java/org/openboxes/organization/controller/OrganizationController.java` (3 endpoints: GET /{id}, GET (list), POST)
- `services/organization-service/src/main/java/org/openboxes/organization/controller/PartyController.java` (1 endpoint: GET /party/{id} polymorphic)
- `services/organization-service/src/main/java/org/openboxes/organization/controller/ReferenceController.java` (3 endpoints: GET /partyType, GET /partyType/{id}, GET /partyRole)

**Create — Tests:**
- `services/organization-service/src/test/java/org/openboxes/organization/OrganizationServiceIntegrationTest.java`
- `services/organization-service/src/test/resources/seed.sql`
- `e2e/tests/organization-service.spec.ts`

**Create — Retrospective:**
- `docs/retrospectives/YYYY-MM-DD-phase-4-organization-retrospective.md` (filled in at done-gate)

**Modify:**
- `services/settings.gradle` — add `include 'organization-service'`
- `docker/docker-compose-base.yml` — add `openboxes-organization-service` service block (mirror location-service at port 8084)
- `docker/docker-compose.yml` — add `organization-service` extends+depends_on block + add `organization-service: service_healthy` to nginx depends_on
- `docker/nginx/conf.d/app.conf` — add `location = /api/organization` (exact-match) + `location /api/organization/` (prefix) blocks BEFORE `/api/` catch-all
- `.github/workflows/e2e-tests.yml` — add `:organization-service:bootJar` to build step + organization-service healthcheck probe + log dump
- `src/js/utils/option-utils.jsx:191` — `/api/organizations` → `/api/organization` (preserves query-string suffix)
- `src/js/utils/option-utils.jsx:225` — same
- `src/js/actions/index.js:561` — same
- `src/js/components/locations-configuration/modals/AddOrganizationModal.jsx:59` — `locationUrl = '/api/organizations'` → `'/api/organization'`

**Delete:**
- `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy` (38 LOC; generic URL mapping at `UrlMappings.groovy:935` stays — do NOT touch)

---

## Inherited from spec

The 28 load-bearing assumptions verified by `thorough-brainstorming` at spec-write time (commit `cfa3f81b8`), updated through CDR R1 + R2 (commits `0ff57277a` + `436c555a1`). Trusted as ground truth — NOT re-verified at plan-write time:

| # | Assumption | Spec evidence |
|---|---|---|
| A1 | `Organization.groovy` extends Party with code/name/description/defaultLocation/active/dateCreated/lastUpdated; sequences Map; hasMany locations | Spec §17 row A1 |
| A2 | `Party.groovy`: partyType ManyToOne; roles OneToMany (cascade lives on Organization not Party) | Spec §17 row A2 |
| A3 | PartyRole + PartyType + Address field sets (PartyType.partyTypeCode enum; PartyRole.startDate/endDate; Address.description) | Spec §17 row A3 |
| A4 | Physical schema is SINGLE_TABLE: ONE `party` table with `class` discriminator + all Org cols (NOT JOINED — verified at migration line 1607 `class VARCHAR(255) NOT NULL`) | Spec §17 row A4 |
| A5 | `OrganizationIdentifierService` exists; ~50 lines using Apache Commons WordUtils + config-driven sizes | Spec §17 row A5 |
| A6 | `Constants.DEFAULT_ORGANIZATION_CODE = "ORG"` at `src/main/groovy/.../Constants.groovy:164` | Spec §17 row A6 |
| A7 | `OrganizationApiController` has 3 actions: list, read, create | Spec §17 row A7 |
| A8 | `OrganizationService` methods enumerated (selectOrganizations, find/Or/CreateOrganization × 2 overloads, findOrganization, saveOrganization, createOrganization, findOr/Create{Buyer,Supplier}Organization, getOrganizations) | Spec §17 row A8 |
| A9 | `UrlMappings.groovy /api/organizations` is dispatched by GENERIC mapping `/api/${resource}s` at line 935 — deleting OrganizationApiController is sufficient; **do NOT touch URL mapping** | Spec §17 row A9 |
| A10 | GSP admin doesn't route through `/api/organizations` — OrganizationController serves `/organization/*` via default `/$controller/$action?/$id?` mapping | Spec §17 row A10 |
| A11 | Only 3 React files use `/api/organizations` (4 hits): `option-utils.jsx:191`, `option-utils.jsx:225`, `actions/index.js:561`, `AddOrganizationModal.jsx:59`. `OrganizationApi.js` calls `/api/generic/organization/{id}` (different endpoint, out of scope) | Spec §17 row A11 |
| A12 | AddOrganizationModal POST body is `{name, description}` only | Spec §17 row A12 |
| A13 | No nested response navigation in any of the 3 React files (all access flat `obj.id`, `obj.name`, `obj.code`, `response.data.data.id`) | Spec §17 row A13 |
| A14 | No cross-context Grails writers beyond LoadDataService/MigrationService/LocationImportDataService for the Organization write path (NOTE — plan-level verification found a 4th caller `LocationController.groovy:103` GSP admin; back-port to spec §7.1 in retro — same disposition: stays Grails per §15 Phase 12) | Spec §17 row A14 (with plan back-port pending) |
| A15 | Grails internal readers tolerate Organization entity staying alive (they use GORM `Organization.get()` directly, not `/api/organizations`) | Spec §17 row A15 |
| A16 | Phase 2 User/Person uses `@Inheritance(JOINED)` because Person.groovy declares `tablePerHierarchy false`; Party.groovy doesn't, so Phase 4 uses SINGLE_TABLE | Spec §17 row A16 |
| A17 | location-service security copy portability + 401 `exceptionHandling` at `SecurityConfig.java:26` | Spec §17 row A17 |
| A18 | TestContainers `@DynamicPropertySource` template (3 properties: `spring.jpa.defer-datasource-initialization`, `spring.sql.init.data-locations`, `spring.sql.init.mode`) | Spec §17 row A18 |
| A19 | Liquibase shadow-changelog `tableExists` precondition pattern + empty body | Spec §17 row A19 |
| A20 | Port 8084 unused | Spec §17 row A20 |
| A21 | nginx app.conf supports new exact+prefix block additions | Spec §17 row A21 |
| A22 | `services/organization-service` module doesn't exist (no `include` line in `services/settings.gradle`) | Spec §17 row A22 |
| A23 | Supplier is a SQL view (`CREATE OR REPLACE VIEW supplier`) — JPA can't write to it; not a real entity | Spec §17 row A23 |
| A24 | Donor (donation/) + Shipper (shipping/) safe to defer to their respective phases | Spec §17 row A24 |
| A25 | No missing entity in core/ package | Spec §17 row A25 |
| A26 | `ddl-auto: validate` tolerance pattern works (Phase 3 retro line 50) | Spec §17 row A26 |
| A27 | `party_type` has guarded seed insert with `code='ORG'` precondition + insert; id=1 hardcoded | Spec §17 row A27 |
| A28 | `party.class` discriminator values Grails writes for **both** bare Party and Organization rows — no GORM `mapping { discriminator … }` override in Party.groovy or Organization.groovy | Spec §17 row A28 — **⏳ PENDING T1 EMPIRICAL VERIFICATION; BLOCKS T2** |

---

## Verified plan-level assumptions

Newly introduced by this plan (paths, signatures, commands, ordering, code-in-plan validity, consumer impact). Each verified at plan-write time against repo `436c555a1`:

| # | Category | Assumption | Evidence |
|---|---|---|---|
| 1 | File path | `services/organization-service/` does not yet exist | `ls -d services/organization-service/` → No such file or directory |
| 2 | File path | `services/settings.gradle` currently has `document-service` + `identity-service` + `location-service` (no organization-service) | `cat services/settings.gradle` (4 lines) |
| 3 | File path | All 9 location-service template files exist: `build.gradle`, `Dockerfile`, `application.yml`, `security/{JwtCookieAuthFilter, JwtService, SecurityConfig}.java`, `service/LocationTypeCache.java`, `test/.../LocationServiceIntegrationTest.java`, `test/resources/seed.sql` | `for f in …; do [ -f "$f" ] && echo ✓; done` (9/9 ✓) |
| 4 | File path | `docker/{docker-compose-base.yml, docker-compose.yml, nginx/conf.d/app.conf}` + `.github/workflows/e2e-tests.yml` all exist with Phase 3 location-service patterns | `for f in …; do [ -f "$f" ]; done` (4/4 ✓) + visual inspection of compose `location-service` blocks + nginx `location = /api/location` block |
| 5 | File path | 4 React files have `/api/organizations` at exact cited line numbers | `grep -n /api/organizations` returns 4 hits matching: `option-utils.jsx:191`, `:225`, `actions/index.js:561`, `AddOrganizationModal.jsx:59` |
| 6 | File path | `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy` exists, 38 LOC | `wc -l` returns 38 |
| 7 | File path | `grails-app/services/org/pih/warehouse/core/OrganizationIdentifierService.groovy` exists, 104 LOC; `Constants.DEFAULT_ORGANIZATION_CODE = "ORG"` at line 164 of `src/main/groovy/org/pih/warehouse/core/Constants.groovy` | `wc -l` + `grep -n DEFAULT_ORGANIZATION_CODE` |
| 8 | File path | `UrlMappings.groovy` generic `/api/${resource}s` mapping at lines 935, 940, 945 (list+create, status, read+update+delete) | `grep -n '${resource}s'` returns 3 hits |
| 9 | Signature | Grails `OrganizationService.createOrganization(Organization)` at line 91: sets `code` via `organizationIdentifierService.generate(name)` if absent; sets `partyType` via `PartyType.findByCode(Constants.DEFAULT_ORGANIZATION_CODE)` if absent; calls `saveOrganization()` — T6 Java port mirrors this body | Read of `OrganizationService.groovy:91-101` |
| 10 | Signature | Grails `OrganizationIdentifierService.generate(String name)` reads `openboxes.identifier.organization.{minSize,maxSize}` via Grails `ConfigService`; uses `org.apache.commons.lang.WordUtils.initials` (commons-lang **1.x**, NOT lang3 — both are on the Grails classpath: `commons-lang:2.6` AND `commons-lang3:3.12.0` are both present in `build.gradle` dependencies). T6 implementer picks among: (a) `commons-lang3` + deprecated `WordUtils.initials`, (b) `commons-text` for non-deprecated `WordUtils.initials`, (c) pure-Java rewrite of initials logic. Java port reads same property names via Spring `@Value` instead of ConfigService | Read of `OrganizationIdentifierService.groovy:1-104`; `grep commons-lang` on Grails `build.gradle` |
| 11 | Signature | `party_type` Grails seed insert is guarded by `<sqlCheck>SELECT COUNT(*) FROM party_type WHERE code = 'ORG'</sqlCheck>` precondition at `changelog-2018-05-30-2315-insert-party-type-data.xml:6`; A27 stands | `grep -n "code.*'ORG'"` returns line 6 |
| 12 | Signature | location-service security signatures: `JwtCookieAuthFilter extends OncePerRequestFilter` at line 20; `JwtService.COOKIE_NAME = "obx_token"` at line 14; `JwtService.validate(String) → Map<String, Object>` at line 21; `SecurityConfig` line 26 has `.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))` for spec-required 401-vs-403 mitigation | Reads of all 3 security files |
| 13 | Command | `cd services && ./gradlew :organization-service:bootJar` works once T2 adds module to settings.gradle (matches Phase 3 pattern) | Phase 3 plan vassump #13 |
| 14 | Command | `cd services && sudo -E ./gradlew :organization-service:test` runs JUnit + TestContainers (matches Phase 3 pattern) | Phase 3 plan vassump #14 |
| 15 | Command | `cd e2e && npm test` runs Playwright suite | `.github/workflows/e2e-tests.yml:69-70` |
| 16 | Command | Commit message convention is `phase N task M: <description>` (or `phase N: <description>` for non-task commits like retro) — verified across all 4 phases | `git log --oneline --grep="^phase"` |
| 17 | Ordering | **T1 A28 verification HARD-GATES T2** — if `SELECT DISTINCT class FROM party` returns values different from §5.1/§5.2's provisional FQCN placeholders, halt T2 and revise spec §5.1 + §5.2 + §11.1 via `update-design-doc` before resuming T2 | Plan structure + spec §17 A28 ("**Blocks T2**") |
| 18 | Ordering | T4 (entities) → T5 (security; independent of T4 but parallelizable in practice) → T6 (services/DTOs imports from T4) → T7 (controllers import from T4-T6) → T8 (nginx routing + controller delete; both within T8 are independent) → T9 (JUnit) / T10 (Playwright+React; can run in parallel with T9) → T11 (CI; independent) → T12 (done-gate) → T13 (retro). No forward references in code blocks | Plan structure (verified by absence of forward refs in code blocks below) |
| 19 | Ordering | T9 (JUnit) requires T2-T7 (TestContainers tests don't need nginx); T10 (Playwright) requires T8 (E2E goes through nginx) | Plan structure |
| 20 | Code-in-plan | `services/build.gradle` declares `org.springframework.boot version '3.3.5' apply false` + `io.spring.dependency-management version '1.1.6' apply false`; subprojects apply both — Spring Boot 3.3.5 BOM pinned at root; Hibernate 6 + jjwt 0.12.5 + TestContainers 1.21.3 + springdoc 2.5.0 + mariadb-java-client 3.4.1 all carry-forward from `services/location-service/build.gradle` verbatim (Phase 4 build.gradle drops `spring-boot-starter-mail` and adds nothing) | Read of `services/build.gradle` + `services/location-service/build.gradle` |
| 21 | Code-in-plan | JPA SINGLE_TABLE inheritance annotations (`@Inheritance(strategy = InheritanceType.SINGLE_TABLE)` + `@DiscriminatorColumn(name="class", discriminatorType=STRING, length=255)` + `@DiscriminatorValue(...)` on Party AND Organization) work in Hibernate 6 / Jakarta Persistence | JPA spec §11.1.2 + Phase 2 JOINED inheritance precedent at `services/identity-service/src/main/java/org/openboxes/identity/entity/Person.java:12` confirms inheritance annotations work in this stack |
| 22 | Code-in-plan | Grails build has both `commons-lang:2.6` (used by Grails OrganizationIdentifierService) AND `commons-lang3:3.12.0` (force-pinned for security). For T6, the implementer picks one of 3 paths (see vassump #10). All 3 are technically valid; T6 picks at implementation time per spec §5.7 | `grep commons-lang` on Grails `build.gradle` |
| 23 | Consumer | `services/settings.gradle` `include 'organization-service'` is purely additive — Gradle's `include` is order-independent and doesn't affect existing document/identity/location includes | Standard Gradle settings behavior + Phase 3 vassump #19 (location-service add was additive) |
| 24 | Consumer | Port 8084 + container name `openboxes-organization-service` don't conflict — existing services use 8080 (app), 8081 (document), 8082 (identity), 8083 (location) | `grep -nE "808[0-9]" docker/docker-compose-base.yml` shows only 8080-8083 |
| 25 | Consumer | nginx `location = /api/organization` (exact-match) does NOT match `/api/organizations` (extra `s`); nginx `location /api/organization/` (prefix) does NOT match `/api/organizations/` (different prefix string due to trailing slash differentiating `/api/organization/` from `/api/organizations`). Both blocks must precede `/api/` catch-all. Pattern proven by existing `/api/location` exact-match + `/api/location/` prefix in `docker/nginx/conf.d/app.conf:25-44` which co-exist cleanly with `/api/locations/` (plural going to Grails) | Read of `app.conf:25-44`; nginx prefix-match semantics |
| 26 | Consumer | 4 React file URL edits (`/api/organizations` → `/api/organization`) preserve query strings + payload shape; consumers use flat fields (`obj.id`, `obj.name`, `obj.code`, `response.data.data.id`) compatible with flat DTO per spec A13. No other React files reference these symbols in a way that the URL change breaks | spec §17 A13; plan-write-time grep returns exactly the 4 cited callsites |
| 27 | Consumer | **Delete `OrganizationApiController.groovy` is safe** — generic UrlMappings uses dynamic dispatch `controller = { "${params.resource}Api" }` (line 936); when Grails can't resolve the named controller class, the URL mapping is unfulfilled and Grails returns 404 (standard Grails 5 behavior). T1 Step 4 verifies live-probe `GET /api/organizations` post-delete returns 404 (not 500); if 500, halt T8 commit and surface | spec FD#4 + UrlMappings.groovy:935-936; T1 verification gates |
| 28 | Consumer | `.github/workflows/e2e-tests.yml` line 36 `bootJar` arg list, line 44 healthcheck probe sequence, line 78 log dump are additive append patterns — already includes `:location-service` from Phase 3; org-service additions don't disturb existing identity/document/location entries | Read of `.github/workflows/e2e-tests.yml` |
| 29 | Consumer | `docker/docker-compose-base.yml` `location-service` block (lines 73-89) is the template; org-service mirrors with port 8084 + container name change. `docker/docker-compose.yml` `location-service` extends+depends_on block (lines 31-39) is the template; org-service mirrors. nginx depends_on block (lines 41-50) is additive | Read of both compose files |
| 30 | Consumer | Hibernate 6 `ddl-auto: validate` only validates DECLARED columns exist in physical schema; allows extra physical columns; does NOT strictly check nullability mismatches. CDR R1 §2.3 fix `@Column(nullable=false) Boolean active = true` on Organization does NOT trigger boot failure even though physical `active` column is nullable. Phase 3 vassump #24 established this | Phase 3 vassump #24 + Hibernate 6 SchemaValidator docs |

---

## Tasks

### Task 1: Scope audit + A28 empirical verification + live-smoke-probe baseline

**Files:** (read-only; no writes)

This task gates T2. If A28 or live-probe surfaces unexpected state, halt and surface to user before proceeding.

- [ ] **Step 1: Scope audit subagent** — Dispatch a sonnet subagent with this checklist:
  - Verify `services/organization-service/` does NOT exist (`ls -d services/organization-service/` → No such file).
  - Verify `services/settings.gradle` unchanged from `436c555a1` (4 lines; no `organization-service` include).
  - Verify 6 dev containers Up healthy: `sudo docker ps --filter name=openboxes --format "table {{.Names}}\t{{.Status}}"` → 6 rows (db, app, document-service, identity-service, location-service, nginx).
  - Verify `OrganizationApiController` exists at 38 LOC with 3 actions (list, read, create) at `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy`.
  - Verify `OrganizationIdentifierService.generate(String)` at `grails-app/services/org/pih/warehouse/core/OrganizationIdentifierService.groovy:21-58` matches plan vassump #10 (uses `WordUtils.initials`, `ConfigService.getProperty`, the documented `':'`-bug at line 42-43).
  - Verify physical `party` table column types via:
    ```bash
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM party"
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM party_role"
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM party_type"
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM address"
    ```
  - Capture actual column names + types for T4 entity column annotations. Especially verify: `class` is VARCHAR(255) NOT NULL, `code`, `description`, `name`, `default_location_id`, `active` are present in `party` (single-table inheritance).

- [ ] **Step 2: A28 empirical verification — the T2 gate**
  ```bash
  sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SELECT DISTINCT class FROM party"
  ```
  Capture the actual distinct values. Two scenarios:
  - **Scenario A (FQCN matches spec placeholders):** observed values are `org.pih.warehouse.core.Party` AND `org.pih.warehouse.core.Organization`. T2 proceeds; spec §5.1 + §5.2 placeholders are correct.
  - **Scenario B (different):** observed values differ (e.g., simple names `Party` + `Organization`, or other override). **HALT.** Run `update-design-doc` on a new R3 review or hand-edit spec §5.1 + §5.2 (`@DiscriminatorValue("…")`) + §11.1 seed.sql snippet (lines 469-470 `class='…'`) + §17 A28 (mark as resolved with observed values) — then resume T2.

- [ ] **Step 3: Live-smoke-probe (regression baseline)** — Through the running dev stack:
  ```bash
  # Get a valid obx_token via login (Phase 2 identity-service)
  TOKEN=$(curl -sf -X POST -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"password"}' \
      -c - http://localhost/api/identity/login | grep obx_token | awk '{print $7}')

  # Capture GET /api/organizations baseline response shape:
  curl -sf -b "obx_token=$TOKEN" 'http://localhost/api/organizations?roleType=ROLE_BUYER&active=true' | jq . > /tmp/grails-organization-list.json
  curl -sf -b "obx_token=$TOKEN" "http://localhost/api/organizations/$(sudo docker exec openboxes-db mariadb -u root -proot openboxes -se 'SELECT id FROM party WHERE class LIKE "%Organization%" LIMIT 1')" | jq . > /tmp/grails-organization-read.json

  # Capture POST /api/organizations baseline (with sample payload mirroring AddOrganizationModal):
  curl -sf -b "obx_token=$TOKEN" -X POST -H 'Content-Type: application/json' \
      -d '{"name":"Phase4BaselineTest","description":"T1 baseline capture"}' \
      'http://localhost/api/organizations' | jq . > /tmp/grails-organization-create.json
  ```
  Reference for T6 default-roles behavior (does Grails create() add any default PartyRoles?) + T7 response-shape baseline + T10 Playwright assertion fixtures.

- [ ] **Step 4: Grails missing-controller behavior verification (gates T8)**
  ```bash
  # Temporarily rename OrganizationApiController to simulate the post-delete state:
  mv grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy /tmp/oac-backup.groovy
  cd docker && sudo docker-compose restart app  # Wait ~30s for Grails restart
  sudo docker logs openboxes-app 2>&1 | grep -i "compilation" | head -5  # Should be empty (Grails compiles fine without OAC)

  # Probe the URL:
  curl -sI -b "obx_token=$TOKEN" http://localhost/api/organizations
  # CAPTURE THE EXACT STATUS CODE. Expected: HTTP/1.1 404. If 500 or 502, halt — plan vassump #27 is wrong and T8's regression test #5 needs updating.

  # Restore the controller:
  mv /tmp/oac-backup.groovy grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy
  cd docker && sudo docker-compose restart app
  ```
  (This is intrusive — only do it once per phase; rollback is mandatory before any other Grails change. Captured response code drives T10 Playwright regression test #5 assertion.)

- [ ] **Step 5: Verify A14 back-port: enumerate all Grails callers of `OrganizationService.findOrCreate*` / `createOrganization`**
  ```bash
  grep -rnE "organizationService\.(create|findOrCreate)Organization|organizationService\.findOrCreate(Buyer|Supplier)Organization" grails-app/ src/main/groovy/
  ```
  Expected output: 4-5 hits. Cross-reference against spec §7.1 (3 listed: LoadDataService, MigrationService, LocationImportDataService). Any additional callers (e.g., `LocationController.groovy:103`) are spec back-port candidates for the T13 retro; they all stay on Grails per §15 Phase 12 deferral, so no plan-shape impact.

- [ ] **Step 6: Report findings** — Subagent reports:
  - All 30 plan-level assumptions reconfirmed (or drift surfaced).
  - A28 result: actual `SELECT DISTINCT class FROM party` output, plus pass/fail-against-FQCN-placeholder.
  - Live-probe response shapes (`/tmp/grails-organization-{list,read,create}.json`) captured.
  - Grails missing-controller behavior: exact status code on `/api/organizations` post-rename.
  - Cross-context caller list with §7.1 delta for retro.
  - If any of these fail (A28 mismatch, missing-controller behavior not 404, baseline capture failure), halt + present to user before T2.

**Done when:** Subagent reports all 30 plan-level assumptions reconfirmed AND A28 matches §5.1/§5.2 placeholders (OR spec is updated to match observed values) AND `/tmp/grails-organization-*.json` baselines captured AND Grails missing-controller returns 404 (OR plan vassump #27 + T10 test #5 updated) AND no drift items requiring plan revision.

---

### Task 2: Bootstrap module + docker-compose entries

**Files:**
- Create: `services/organization-service/build.gradle`, `services/organization-service/Dockerfile`, `services/organization-service/src/main/java/org/openboxes/organization/OrganizationServiceApplication.java`, `services/organization-service/src/main/resources/application.yml`
- Modify: `services/settings.gradle`, `docker/docker-compose-base.yml`, `docker/docker-compose.yml`

- [ ] **Step 1: Add module to `services/settings.gradle`**
```diff
 rootProject.name = 'openboxes-services'
 include 'document-service'
 include 'identity-service'
 include 'location-service'
+include 'organization-service'
```

- [ ] **Step 2: Create `services/organization-service/build.gradle`** (verbatim from `services/location-service/build.gradle`):
```gradle
ext['testcontainers.version'] = '1.21.3'

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.liquibase:liquibase-core'
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'
    implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'
    runtimeOnly 'org.mariadb.jdbc:mariadb-java-client:3.4.1'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'org.testcontainers:junit-jupiter:1.21.3'
    testImplementation 'org.testcontainers:mariadb:1.21.3'
}

test {
    useJUnitPlatform()
    systemProperty 'api.version', '1.44'
    systemProperty 'testcontainers.ryuk.disabled', 'true'
}
```

(T6 implementer may add `org.apache.commons:commons-text:1.12.0` here if picking option (b) for OrganizationIdentifierService port. T6 step shows the choice.)

- [ ] **Step 3: Create `services/organization-service/Dockerfile`** (mirror location-service; port 8084):
```dockerfile
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8084
WORKDIR /app
COPY build/libs/organization-service-*.jar /app/organization-service.jar

RUN useradd -r spring
USER spring

ENTRYPOINT ["java", "-jar", "/app/organization-service.jar"]
```

- [ ] **Step 4: Create `services/organization-service/src/main/java/org/openboxes/organization/OrganizationServiceApplication.java`**:
```java
package org.openboxes.organization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrganizationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrganizationServiceApplication.class, args);
    }
}
```

- [ ] **Step 5: Create `services/organization-service/src/main/resources/application.yml`** (mirror location-service; port 8084; Liquibase enabled per spec §10 since T3 ships the changelogs alongside this bootstrap):
```yaml
server:
  port: 8084
spring:
  application:
    name: organization-service
  datasource:
    url: ${DATASOURCE_URL}
    username: ${DATASOURCE_USERNAME}
    password: ${DATASOURCE_PASSWORD}
    driver-class-name: org.mariadb.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.dialect: org.hibernate.dialect.MariaDBDialect
  liquibase:
    enabled: false  # flipped to true in T3 after shadow changelogs are created
management:
  endpoints:
    web:
      exposure:
        include: health,info
openboxes:
  jwt:
    secret: ${OPENBOXES_JWT_SECRET}
  identifier:
    organization:
      minSize: 2
      maxSize: 3
```

(Property values `minSize: 2`, `maxSize: 3` mirror Grails defaults — T1 audit Step 1 should verify the actual values from Grails `application.yml`/`application.groovy` and adjust if different. The Java port of `OrganizationIdentifierService` reads these via `@Value` in T6.)

- [ ] **Step 6: Add service block to `docker/docker-compose-base.yml`** (after location-service block, before nginx):
```yaml
    organization-service:
      build:
        context: ../services/organization-service
        dockerfile: Dockerfile
      container_name: openboxes-organization-service
      expose:
        - "8084"
      environment:
        DATASOURCE_URL: ${DATASOURCE_URL:-jdbc:mariadb://db:3306/openboxes?serverTimezone=UTC&useSSL=false}
        DATASOURCE_USERNAME: ${DATASOURCE_USERNAME:-openboxes}
        DATASOURCE_PASSWORD: ${DATASOURCE_PASSWORD:-openboxes}
        OPENBOXES_JWT_SECRET: ${OPENBOXES_JWT_SECRET:-dev-secret-only-for-local-please-rotate-in-prod}
      healthcheck:
        test: "curl --fail --silent localhost:8084/actuator/health | grep '\"status\":\"UP\"' || exit 1"
        interval: 10s
        timeout: 5s
        retries: 5
        start_period: 30s
```

- [ ] **Step 7: Add extends+depends_on block to `docker/docker-compose.yml`** (mirror location-service block at lines 31-39; insert before nginx):
```yaml
    organization-service:
      extends:
        file: docker-compose-base.yml
        service: organization-service
      depends_on:
        db:
          condition: service_healthy
        app:
          condition: service_healthy
```

- [ ] **Step 8: Build the bare service jar**
```bash
cd services && ./gradlew :organization-service:bootJar
```

- [ ] **Step 9: Rebuild + boot the stack**
```bash
cd docker && sudo docker-compose down && sudo docker-compose up -d --build
```

- [ ] **Step 10: Verify organization-service is up + healthy** (nginx still ignores it — that's T8)
```bash
sudo docker ps --filter name=openboxes-organization-service --format "table {{.Names}}\t{{.Status}}"
# Expected: openboxes-organization-service ... Up X seconds (healthy)
sudo docker exec openboxes-organization-service curl -sf localhost:8084/actuator/health
# Expected: {"status":"UP"}
```

- [ ] **Step 11: Commit**
```bash
git add services/settings.gradle services/organization-service/build.gradle services/organization-service/Dockerfile services/organization-service/src/main/java/org/openboxes/organization/OrganizationServiceApplication.java services/organization-service/src/main/resources/application.yml docker/docker-compose-base.yml docker/docker-compose.yml
git commit -m "phase 4 task 2: bootstrap organization-service module + docker-compose entry — bare Spring Boot 3.3.5 service on port 8084; boots healthy as 7th container; Liquibase disabled (T3 flips after shadow changelogs); no entities/controllers yet (T3-T7 add them)"
```

---

### Task 3: Liquibase shadow changelogs (4 files)

**Files:**
- Create: `services/organization-service/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: 4 shadow changelog XMLs under `services/organization-service/src/main/resources/db/changelog/`
- Modify: `services/organization-service/src/main/resources/application.yml` (flip `spring.liquibase.enabled: true`)

- [ ] **Step 1: Create master changelog**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <include file="db/changelog/changelog-shadow-create-party.xml"/>
    <include file="db/changelog/changelog-shadow-create-party-role.xml"/>
    <include file="db/changelog/changelog-shadow-create-party-type.xml"/>
    <include file="db/changelog/changelog-shadow-create-address.xml"/>
</databaseChangeLog>
```

(4 changelogs — no separate `organization` table per FD#2 SINGLE_TABLE inheritance; the `party` table holds Organization columns via discriminator.)

- [ ] **Step 2: Create 4 shadow changelogs using `tableExists` precondition** (Phase 3 RC-2 pattern). Template (substitute `X` per table):

```xml
<?xml version="1.1" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd"
                   logicalFilePath="services/organization-service/db/changelog/changelog-shadow-create-X.xml">
    <changeSet id="phase4-shadow-create-X" author="openboxes-organization">
        <preConditions onFail="MARK_RAN" onFailMessage="X table not found — Grails Liquibase must run first">
            <tableExists tableName="X"/>
        </preConditions>
        <comment>
            Shadow for X table. Grails Liquibase owns table creation.
            organization-service uses spring.jpa.hibernate.ddl-auto=validate to prove entity-mapping correctness.
            For party table specifically: SINGLE_TABLE inheritance per A4 — Organization columns live alongside Party columns.
        </comment>
        <!-- No body: table already exists per the precondition. -->
    </changeSet>
</databaseChangeLog>
```

Tables: `party`, `party_role`, `party_type`, `address`.

- [ ] **Step 3: Flip `spring.liquibase.enabled` to true in `application.yml`** + add `change-log` (T2 deviation back-out):
```diff
   liquibase:
-    enabled: false  # flipped to true in T3 after shadow changelogs are created
+    enabled: true
+    change-log: classpath:db/changelog/db.changelog-master.xml
```

- [ ] **Step 4: Rebuild + boot to verify Liquibase shadows MARK_RAN cleanly**
```bash
cd services && ./gradlew :organization-service:bootJar
cd ../docker && sudo docker-compose up -d --build organization-service
sudo docker logs openboxes-organization-service 2>&1 | grep -iE "liquibase|changeset"
# Expected: 4 lines like "ChangeSet ... ran successfully" or "Mark ran" (precondition satisfied)
```

- [ ] **Step 5: Verify DATABASECHANGELOG table has 4 new rows**
```bash
sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SELECT id, author FROM DATABASECHANGELOG WHERE author='openboxes-organization'"
# Expected: 4 rows
```

- [ ] **Step 6: Commit**
```bash
git add services/organization-service/src/main/resources/db/changelog/ services/organization-service/src/main/resources/application.yml
git commit -m "phase 4 task 3: Liquibase shadow changelogs for organization-service (4 tables via tableExists precondition; no separate organization table per FD#2 SINGLE_TABLE) — MARK_RAN if Grails Liquibase already created the tables; no DDL body; satisfies Liquibase scope requirement for ddl-auto:validate; flips spring.liquibase.enabled false→true + adds change-log (T2 deviation back-out)"
```

---

### Task 4: JPA entities + enum mirror + repositories

**Files:**
- Create: `services/organization-service/src/main/java/org/openboxes/organization/entity/{Party, Organization, PartyRole, PartyType, Address}.java`
- Create: `services/organization-service/src/main/java/org/openboxes/organization/enums/PartyTypeCode.java`
- Create: `services/organization-service/src/main/java/org/openboxes/organization/repository/{Organization, Party, PartyRole, PartyType}Repository.java`

**A28 pre-check:** before writing the entities, verify T1 Step 2 output. The `@DiscriminatorValue` strings below assume A28 returned the FQCN form. If T1 returned a different value (e.g., simple `"Organization"`), substitute that here and in T9 seed.sql before committing.

- [ ] **Step 1: Create `enums/PartyTypeCode.java`** — Java enum mirror of Grails `PartyTypeCode` (per spec §5.4; 2 values; small + stable):
```java
package org.openboxes.organization.enums;

public enum PartyTypeCode {
    ORGANIZATION,
    PERSON
}
```

(Unlike RoleType which CDR R1 §2.1 demoted to raw String due to 60+ values, PartyTypeCode has only 2 values verified at `src/main/groovy/org/pih/warehouse/core/PartyTypeCode.groovy`; enum mirror is safe.)

- [ ] **Step 2: Create `entity/Party.java`** (base class, SINGLE_TABLE inheritance):
```java
package org.openboxes.organization.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "party")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "class", discriminatorType = DiscriminatorType.STRING, length = 255)
@DiscriminatorValue("org.pih.warehouse.core.Party")  // A28-verified at T1
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

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        if (dateCreated == null) dateCreated = now;
        lastUpdated = now;
    }
    @PreUpdate void preUpdate() { lastUpdated = Instant.now(); }

    // Getters + setters (id, version, partyType, roles, dateCreated, lastUpdated)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getVersion() { return version; }
    public PartyType getPartyType() { return partyType; }
    public void setPartyType(PartyType partyType) { this.partyType = partyType; }
    public Set<PartyRole> getRoles() { return roles; }
    public void setRoles(Set<PartyRole> roles) { this.roles = roles; }
    public Instant getDateCreated() { return dateCreated; }
    public Instant getLastUpdated() { return lastUpdated; }
}
```

- [ ] **Step 3: Create `entity/Organization.java`** (extends Party, SINGLE_TABLE):
```java
package org.openboxes.organization.entity;

import jakarta.persistence.*;

@Entity
@DiscriminatorValue("org.pih.warehouse.core.Organization")  // A28-verified at T1
public class Organization extends Party {

    @Column(nullable = false, length = 255)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "default_location_id", columnDefinition = "CHAR(38)")
    private String defaultLocationId;  // FK scalar — NOT @ManyToOne (Location lives in another service)

    @Column(nullable = false, columnDefinition = "BIT(1)")
    private Boolean active = true;  // CDR R1 §2.3: app-layer default mirrors Grails

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDefaultLocationId() { return defaultLocationId; }
    public void setDefaultLocationId(String defaultLocationId) { this.defaultLocationId = defaultLocationId; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
```

- [ ] **Step 4: Create `entity/PartyRole.java`**:
```java
package org.openboxes.organization.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "party_role")
public class PartyRole {

    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column(nullable = false) private Long version;

    @ManyToOne
    @JoinColumn(name = "party_id", nullable = false)
    private Party party;

    @Column(name = "role_type", nullable = false, length = 255)
    private String roleType;  // CDR R1 §2.1: raw String (not enum; 60+ Grails RoleType values)

    @Column(name = "start_date") private Instant startDate;
    @Column(name = "end_date") private Instant endDate;

    public String getId() { return id; }
    public Long getVersion() { return version; }
    public Party getParty() { return party; }
    public void setParty(Party party) { this.party = party; }
    public String getRoleType() { return roleType; }
    public void setRoleType(String roleType) { this.roleType = roleType; }
    public Instant getStartDate() { return startDate; }
    public Instant getEndDate() { return endDate; }
}
```

- [ ] **Step 5: Create `entity/PartyType.java`**:
```java
package org.openboxes.organization.entity;

import jakarta.persistence.*;
import org.openboxes.organization.enums.PartyTypeCode;
import java.time.Instant;

@Entity
@Table(name = "party_type")
public class PartyType {

    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column(nullable = false) private Long version;
    @Column(nullable = false, length = 255) private String code;
    @Column(length = 255) private String name;
    @Column(length = 255) private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type_code", nullable = false, length = 255)
    private PartyTypeCode partyTypeCode;

    @Column(name = "date_created", nullable = false) private Instant dateCreated;
    @Column(name = "last_updated", nullable = false) private Instant lastUpdated;

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public PartyTypeCode getPartyTypeCode() { return partyTypeCode; }
}
```

- [ ] **Step 6: Create `entity/Address.java`** (JPA-mapped for ddl-auto:validate; no endpoint surfaces it but spec §5.5 declares it):
```java
package org.openboxes.organization.entity;

import jakarta.persistence.*;
import java.time.Instant;

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

    public String getId() { return id; }
    public String getAddress() { return address; }
    public String getAddress2() { return address2; }
    public String getCity() { return city; }
    public String getStateOrProvince() { return stateOrProvince; }
    public String getPostalCode() { return postalCode; }
    public String getCountry() { return country; }
    public String getDescription() { return description; }
}
```

- [ ] **Step 7: Create repositories** (`repository/`):
```java
// OrganizationRepository.java
package org.openboxes.organization.repository;

import org.openboxes.organization.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OrganizationRepository extends JpaRepository<Organization, String> {
    long countByCode(String code);

    @Query("SELECT DISTINCT o FROM Organization o LEFT JOIN o.roles r WHERE " +
           "(:q IS NULL OR LOWER(o.id) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(o.code) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(o.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(o.description) LIKE LOWER(CONCAT('%', :q, '%'))) AND " +
           "(:active IS NULL OR o.active = :active) AND " +
           "(:hasRoles = FALSE OR r.roleType IN :roleTypes)")
    List<Organization> findFiltered(@Param("q") String q,
                                    @Param("active") Boolean active,
                                    @Param("hasRoles") boolean hasRoles,
                                    @Param("roleTypes") List<String> roleTypes);
}

// PartyRepository.java
package org.openboxes.organization.repository;

import org.openboxes.organization.entity.Party;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyRepository extends JpaRepository<Party, String> {}

// PartyRoleRepository.java
package org.openboxes.organization.repository;

import org.openboxes.organization.entity.PartyRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PartyRoleRepository extends JpaRepository<PartyRole, String> {
    List<PartyRole> findByPartyId(String partyId);
    List<PartyRole> findByRoleType(String roleType);
    List<PartyRole> findByPartyIdAndRoleType(String partyId, String roleType);
}

// PartyTypeRepository.java
package org.openboxes.organization.repository;

import org.openboxes.organization.entity.PartyType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PartyTypeRepository extends JpaRepository<PartyType, String> {
    Optional<PartyType> findByCode(String code);
}
```

(No AddressRepository — Address is declared for ddl-auto:validate completeness; no read/write endpoints use it.)

- [ ] **Step 8: Rebuild + verify ddl-auto:validate passes at boot**
```bash
cd services && ./gradlew :organization-service:bootJar
cd ../docker && sudo docker-compose up -d --build organization-service
sudo docker logs openboxes-organization-service 2>&1 | tail -50
# Expected: NO "Schema-validation: missing column" or "missing table" errors
sudo docker exec openboxes-organization-service curl -sf localhost:8084/actuator/health
# Expected: {"status":"UP"}
```

- [ ] **Step 9: Commit**
```bash
git add services/organization-service/src/main/java/org/openboxes/organization/entity/ services/organization-service/src/main/java/org/openboxes/organization/enums/ services/organization-service/src/main/java/org/openboxes/organization/repository/
git commit -m "phase 4 task 4: JPA entities + PartyTypeCode enum + repositories — Party (SINGLE_TABLE base) + Organization (subclass with class discriminator) + PartyRole (raw String roleType per CDR R1 §2.1) + PartyType + Address; @DiscriminatorValue values pinned per A28 T1 verification; ddl-auto:validate passes at boot"
```

---

### Task 5: Security (4th JwtCookieAuthFilter + JwtService + SecurityConfig)

**Files:**
- Create: `services/organization-service/src/main/java/org/openboxes/organization/security/{JwtCookieAuthFilter, JwtService, SecurityConfig}.java`

**Note:** This is the 4th copy across services (document, identity, location, organization). Spec FD#6 defers `jwt-auth-common` shared library extraction to Phase X.

- [ ] **Step 1: Copy 3 files from `services/location-service/src/main/java/org/openboxes/location/security/`** to `services/organization-service/src/main/java/org/openboxes/organization/security/`:
```bash
cp services/location-service/src/main/java/org/openboxes/location/security/JwtCookieAuthFilter.java services/organization-service/src/main/java/org/openboxes/organization/security/
cp services/location-service/src/main/java/org/openboxes/location/security/JwtService.java services/organization-service/src/main/java/org/openboxes/organization/security/
cp services/location-service/src/main/java/org/openboxes/location/security/SecurityConfig.java services/organization-service/src/main/java/org/openboxes/organization/security/
```

- [ ] **Step 2: Sed-replace package declarations** (3 files):
```bash
sed -i 's|package org\.openboxes\.location\.security;|package org.openboxes.organization.security;|' \
    services/organization-service/src/main/java/org/openboxes/organization/security/JwtCookieAuthFilter.java \
    services/organization-service/src/main/java/org/openboxes/organization/security/JwtService.java \
    services/organization-service/src/main/java/org/openboxes/organization/security/SecurityConfig.java
```

- [ ] **Step 3: Verify the 3 files compile** (no other location-service references):
```bash
grep -rn "openboxes\.location" services/organization-service/src/main/java/org/openboxes/organization/security/
# Expected: empty (the 3 copies only reference package + jakarta + spring + jjwt classes)
cd services && ./gradlew :organization-service:bootJar
```

- [ ] **Step 4: Rebuild + verify auth behavior**
```bash
cd docker && sudo docker-compose up -d --build organization-service
# Anonymous health check passes:
sudo docker exec openboxes-organization-service curl -sf localhost:8084/actuator/health
# Expected: {"status":"UP"}
# Anonymous protected endpoint returns 401 (security filter rejects before dispatcher):
sudo docker exec openboxes-organization-service curl -sI localhost:8084/api/organization/anything
# Expected: HTTP/1.1 401 (HttpStatusEntryPoint(UNAUTHORIZED) per spec §8)
```

- [ ] **Step 5: Commit**
```bash
git add services/organization-service/src/main/java/org/openboxes/organization/security/
git commit -m "phase 4 task 5: security (4th JwtCookieAuthFilter + JwtService + SecurityConfig copy) — verbatim from location-service with package rename; per FD#6 the shared jwt-auth-common library is deferred to Phase X (4 copies now strongly motivates extraction)"
```

---

### Task 6: Services + DTOs + OrganizationIdentifierService port

**Files:**
- Create: `services/organization-service/src/main/java/org/openboxes/organization/service/{OrganizationService, PartyService, PartyTypeCache, PartyRoleService, OrganizationIdentifierService}.java`
- Create: `services/organization-service/src/main/java/org/openboxes/organization/dto/{Organization, Party, PartyType, PartyRole, Address, CreateOrganizationCommand}Dto.java`
- Possibly modify: `services/organization-service/build.gradle` (if T6 implementer picks Apache Commons option for IdentifierService)

- [ ] **Step 1: Create 6 DTO records** (`dto/`):
```java
// OrganizationDto.java
package org.openboxes.organization.dto;
import org.openboxes.organization.entity.Organization;
import java.time.Instant;
import java.util.List;
public record OrganizationDto(
    String id, String code, String name, String description,
    String partyTypeId, String partyTypeCode,
    String defaultLocationId,
    Boolean active, Instant dateCreated, Instant lastUpdated,
    List<String> roleTypes
) {
    public static OrganizationDto from(Organization o) {
        return new OrganizationDto(
            o.getId(), o.getCode(), o.getName(), o.getDescription(),
            o.getPartyType() == null ? null : o.getPartyType().getId(),
            o.getPartyType() == null || o.getPartyType().getPartyTypeCode() == null ? null : o.getPartyType().getPartyTypeCode().name(),
            o.getDefaultLocationId(),
            o.getActive(), o.getDateCreated(), o.getLastUpdated(),
            o.getRoles() == null ? List.of() : o.getRoles().stream().map(r -> r.getRoleType()).toList()
        );
    }
}

// PartyDto.java
package org.openboxes.organization.dto;
import org.openboxes.organization.entity.Party;
import java.util.List;
public record PartyDto(
    String id, String partyTypeId, String partyTypeCode, List<String> roleTypes
) {
    public static PartyDto from(Party p) {
        return new PartyDto(
            p.getId(),
            p.getPartyType() == null ? null : p.getPartyType().getId(),
            p.getPartyType() == null || p.getPartyType().getPartyTypeCode() == null ? null : p.getPartyType().getPartyTypeCode().name(),
            p.getRoles() == null ? List.of() : p.getRoles().stream().map(r -> r.getRoleType()).toList()
        );
    }
}

// PartyTypeDto.java
package org.openboxes.organization.dto;
import org.openboxes.organization.entity.PartyType;
public record PartyTypeDto(
    String id, String code, String name, String description, String partyTypeCode
) {
    public static PartyTypeDto from(PartyType pt) {
        return new PartyTypeDto(
            pt.getId(), pt.getCode(), pt.getName(), pt.getDescription(),
            pt.getPartyTypeCode() == null ? null : pt.getPartyTypeCode().name()
        );
    }
}

// PartyRoleDto.java
package org.openboxes.organization.dto;
import org.openboxes.organization.entity.PartyRole;
import java.time.Instant;
public record PartyRoleDto(
    String id, String partyId, String roleType, Instant startDate, Instant endDate
) {
    public static PartyRoleDto from(PartyRole r) {
        return new PartyRoleDto(
            r.getId(),
            r.getParty() == null ? null : r.getParty().getId(),
            r.getRoleType(), r.getStartDate(), r.getEndDate()
        );
    }
}

// AddressDto.java (defined for completeness per spec §5.6; not surfaced through endpoint in Phase 4)
package org.openboxes.organization.dto;
public record AddressDto(
    String id, String address, String address2, String city, String stateOrProvince,
    String postalCode, String country, String description
) {}

// CreateOrganizationCommand.java (POST request body)
package org.openboxes.organization.dto;
import jakarta.validation.constraints.NotBlank;
public record CreateOrganizationCommand(
    @NotBlank String name,
    String description,
    String code  // optional; auto-generated if absent
) {}
```

- [ ] **Step 2: Create `service/PartyTypeCache.java`** (mirrors location-service `LocationTypeCache`; refresh-on-miss + RC-6 fix `getAll()` refresh-on-empty):
```java
package org.openboxes.organization.service;

import jakarta.annotation.PostConstruct;
import org.openboxes.organization.entity.PartyType;
import org.openboxes.organization.repository.PartyTypeRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class PartyTypeCache {
    private final PartyTypeRepository repo;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<String, PartyType> byId = new HashMap<>();
    private volatile Map<String, PartyType> byCode = new HashMap<>();

    public PartyTypeCache(PartyTypeRepository r) { this.repo = r; }

    @PostConstruct
    public void refresh() {
        lock.writeLock().lock();
        try {
            Map<String, PartyType> freshById = new HashMap<>();
            Map<String, PartyType> freshByCode = new HashMap<>();
            for (PartyType pt : repo.findAll()) {
                freshById.put(pt.getId(), pt);
                freshByCode.put(pt.getCode(), pt);
            }
            this.byId = freshById;
            this.byCode = freshByCode;
        } finally { lock.writeLock().unlock(); }
    }

    public Optional<PartyType> getById(String id) {
        Optional<PartyType> hit = Optional.ofNullable(byId.get(id));
        if (hit.isPresent()) return hit;
        refresh();
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<PartyType> findByCode(String code) {
        Optional<PartyType> hit = Optional.ofNullable(byCode.get(code));
        if (hit.isPresent()) return hit;
        refresh();
        return Optional.ofNullable(byCode.get(code));
    }

    public List<PartyType> getAll() {
        if (byId.isEmpty()) refresh();  // RC-6 fix: refresh-on-empty
        return List.copyOf(byId.values());
    }
}
```

- [ ] **Step 3: Create `service/OrganizationIdentifierService.java`** — Java port of Grails `OrganizationIdentifierService`. **Implementer picks one of 3 paths** per spec §5.7:

  **Path (a) — commons-lang3 with deprecated WordUtils.initials:**
  - Add to `build.gradle`: `implementation 'org.apache.commons:commons-lang3:3.14.0'`
  - Import `org.apache.commons.lang3.text.WordUtils` (deprecated in 3.6+; still functional)

  **Path (b) — commons-text (modern; non-deprecated):**
  - Add to `build.gradle`: `implementation 'org.apache.commons:commons-text:1.12.0'`
  - Import `org.apache.commons.text.WordUtils`

  **Path (c) — pure Java rewrite of initials logic:**
  - No new dependency
  - Inline `private static String initials(String s) { return Arrays.stream(s.split("\\s+")).filter(w -> !w.isEmpty()).map(w -> String.valueOf(w.charAt(0))).collect(Collectors.joining()); }`

  Whichever path is picked, the rest of the port mirrors `OrganizationIdentifierService.groovy:21-104` verbatim (including the documented TODO bugs at line 42-43 — `suffix++` produces `':'` when suffix='9'; line 49 degrades when length > maxSize):

```java
package org.openboxes.organization.service;

import org.openboxes.organization.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// import org.apache.commons.lang3.text.WordUtils;       // path (a)
// import org.apache.commons.text.WordUtils;              // path (b)
// (path (c): no import; inline initials())

@Service
@Transactional
public class OrganizationIdentifierService {

    @Value("${openboxes.identifier.organization.minSize}")
    private int minSize;

    @Value("${openboxes.identifier.organization.maxSize}")
    private int maxSize;

    private final OrganizationRepository repo;

    public OrganizationIdentifierService(OrganizationRepository r) { this.repo = r; }

    public String generate(String name) {
        String identifier = generateOrganizationIdentifier(name);
        if (name == null || name.isBlank()) return null;

        if (!idAlreadyExists(identifier)) return identifier;

        // If identifier exists, suffix with lowest available digit (BB0..BB9).
        String prefix = identifier.substring(0, identifier.length() - 1);
        String highest = getIdentifierWithHighestSuffix(prefix);
        if (highest != null) {
            char suffix = highest.charAt(highest.length() - 1);
            // TODO: If suffix is '9', doing suffix++ produces ':', which is garbage.
            //       Port preserves the bug verbatim per spec §13.
            suffix++;
            return identifier.toUpperCase().substring(0, identifier.length() - 1) + suffix;
        }
        return identifier.length() < maxSize
            ? identifier.toUpperCase() + '0'
            : identifier.toUpperCase().substring(0, maxSize - 1) + '0';
    }

    private String generateOrganizationIdentifier(String name) {
        // Mirror Grails: trim everything after comma; strip non-alphanumeric (keep spaces).
        String sanitized = (name == null) ? null
            : name.split(",")[0].replaceAll("[^a-zA-Z0-9 ]", "");
        if (sanitized == null || sanitized.isBlank()) return null;

        String initials = /* WordUtils.initials(sanitized) — path (a) or (b); or inline initials(sanitized) — path (c) */ "";

        String identifier;
        if (initials.length() == 1 || initials.length() < minSize) {
            String noSpaces = sanitized.replaceAll("\\s+", "");
            identifier = noSpaces.substring(0, Math.min(maxSize, noSpaces.length()));
        } else if (initials.length() > maxSize) {
            identifier = initials.substring(0, maxSize);
        } else {
            identifier = initials;
        }

        return identifier.toUpperCase();
    }

    private boolean idAlreadyExists(String id) {
        return repo.countByCode(id) > 0;
    }

    private String getIdentifierWithHighestSuffix(String prefix) {
        // Query for codes matching the prefix, filter to digit-suffixed, return largest
        // (mirrors Grails `like('code', prefix + '%')` + filter + sort).
        // Implementation: add a method on OrganizationRepository OR inline JPQL here.
        // For brevity (and to defer to T6 implementer):
        return null;  // placeholder — implementer adds repo.findCodesStartingWith(prefix) + filter
    }
}
```

(Implementer fills `getIdentifierWithHighestSuffix` body + picks path (a)/(b)/(c) for `WordUtils.initials`. T9 tests exercise the algorithm against the Grails-captured baseline at `/tmp/grails-organization-create.json`.)

- [ ] **Step 4: Create `service/OrganizationService.java`** (Java port of Grails `OrganizationService.createOrganization` per plan vassump #9):
```java
package org.openboxes.organization.service;

import org.openboxes.organization.dto.CreateOrganizationCommand;
import org.openboxes.organization.dto.OrganizationDto;
import org.openboxes.organization.entity.Organization;
import org.openboxes.organization.entity.PartyType;
import org.openboxes.organization.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class OrganizationService {

    private static final String DEFAULT_ORGANIZATION_CODE = "ORG";

    private final OrganizationRepository repo;
    private final PartyTypeCache partyTypeCache;
    private final OrganizationIdentifierService identifierService;

    public OrganizationService(OrganizationRepository r, PartyTypeCache c, OrganizationIdentifierService i) {
        this.repo = r;
        this.partyTypeCache = c;
        this.identifierService = i;
    }

    public Optional<OrganizationDto> getById(String id) {
        return repo.findById(id).map(OrganizationDto::from);
    }

    public List<OrganizationDto> list(String q, List<String> roleTypes, Boolean active, Integer max, Integer offset) {
        boolean hasRoles = roleTypes != null && !roleTypes.isEmpty();
        return repo.findFiltered(q, active, hasRoles, hasRoles ? roleTypes : List.of())
            .stream()
            .skip(offset == null ? 0 : offset)
            .limit(max == null ? 50 : max)
            .map(OrganizationDto::from)
            .toList();
    }

    public OrganizationDto create(CreateOrganizationCommand cmd) {
        Organization org = new Organization();
        org.setName(cmd.name());
        org.setDescription(cmd.description());
        org.setCode(cmd.code() == null || cmd.code().isBlank()
            ? identifierService.generate(cmd.name())
            : cmd.code());

        PartyType orgType = partyTypeCache.findByCode(DEFAULT_ORGANIZATION_CODE)
            .orElseThrow(() -> new IllegalStateException("PartyType 'ORG' not seeded — A27 must hold"));
        org.setPartyType(orgType);

        // active defaults to true via entity field initializer (CDR R1 §2.3).
        // No default PartyRoles — verified against Grails OrganizationService.createOrganization at T1 baseline capture.

        return OrganizationDto.from(repo.save(org));
    }
}
```

- [ ] **Step 5: Create `service/PartyService.java`** (polymorphic party read):
```java
package org.openboxes.organization.service;

import org.openboxes.organization.dto.PartyDto;
import org.openboxes.organization.entity.Party;
import org.openboxes.organization.repository.PartyRepository;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class PartyService {
    private final PartyRepository repo;
    public PartyService(PartyRepository r) { this.repo = r; }

    public Optional<PartyDto> getById(String id) {
        return repo.findById(id).map(PartyDto::from);
    }
}
```

- [ ] **Step 6: Create `service/PartyRoleService.java`** (filter by partyId and/or roleType):
```java
package org.openboxes.organization.service;

import org.openboxes.organization.dto.PartyRoleDto;
import org.openboxes.organization.repository.PartyRoleRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class PartyRoleService {
    private final PartyRoleRepository repo;
    public PartyRoleService(PartyRoleRepository r) { this.repo = r; }

    public List<PartyRoleDto> findBy(String partyId, String roleType) {
        List<?> rows;
        if (partyId != null && roleType != null) rows = repo.findByPartyIdAndRoleType(partyId, roleType);
        else if (partyId != null) rows = repo.findByPartyId(partyId);
        else if (roleType != null) rows = repo.findByRoleType(roleType);
        else rows = repo.findAll();
        return rows.stream().map(r -> PartyRoleDto.from((org.openboxes.organization.entity.PartyRole) r)).toList();
    }
}
```

- [ ] **Step 7: Commit**
```bash
git add services/organization-service/src/main/java/org/openboxes/organization/service/ services/organization-service/src/main/java/org/openboxes/organization/dto/
# If implementer added a commons-* dependency in build.gradle for path (a) or (b), include it:
# git add services/organization-service/build.gradle
git commit -m "phase 4 task 6: services + DTOs + OrganizationIdentifierService port — OrganizationService (create with code auto-gen + ORG partyType default), PartyService (polymorphic), PartyTypeCache (RC-6 refresh-on-empty), PartyRoleService (filter), OrganizationIdentifierService Java port (implementer picks among commons-lang3/commons-text/pure-Java for WordUtils.initials per spec §5.7)"
```

---

### Task 7: REST controllers (3 controllers; 7 endpoints)

**Files:**
- Create: `services/organization-service/src/main/java/org/openboxes/organization/controller/{Organization, Party, Reference}Controller.java`

- [ ] **Step 1: Create `controller/OrganizationController.java`** (3 endpoints: read, list, POST):
```java
package org.openboxes.organization.controller;

import jakarta.validation.Valid;
import org.openboxes.organization.dto.CreateOrganizationCommand;
import org.openboxes.organization.dto.OrganizationDto;
import org.openboxes.organization.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/organization")
public class OrganizationController {

    private final OrganizationService service;
    public OrganizationController(OrganizationService s) { this.service = s; }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, OrganizationDto>> read(@PathVariable String id) {
        return service.getById(id)
            .map(dto -> ResponseEntity.ok(Map.of("data", dto)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public Map<String, List<OrganizationDto>> list(
        @RequestParam(required = false) String q,
        @RequestParam(name = "roleType", required = false) List<String> roleTypes,
        @RequestParam(required = false) Boolean active,
        @RequestParam(required = false) Integer max,
        @RequestParam(required = false) Integer offset
    ) {
        return Map.of("data", service.list(q, roleTypes, active, max, offset));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Map<String, String>> create(@Valid @RequestBody CreateOrganizationCommand cmd) {
        OrganizationDto created = service.create(cmd);
        return Map.of("data", Map.of("id", created.id()));
    }
}
```

(Response envelope `{data: …}` mirrors Grails OrganizationApiController.create which returns `[data: [id: organization.id]]`.)

- [ ] **Step 2: Create `controller/PartyController.java`** (1 endpoint: polymorphic party read):
```java
package org.openboxes.organization.controller;

import org.openboxes.organization.dto.PartyDto;
import org.openboxes.organization.service.PartyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/organization/party")
public class PartyController {
    private final PartyService service;
    public PartyController(PartyService s) { this.service = s; }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, PartyDto>> read(@PathVariable String id) {
        return service.getById(id)
            .map(dto -> ResponseEntity.ok(Map.of("data", dto)))
            .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 3: Create `controller/ReferenceController.java`** (3 endpoints: partyType list, partyType read, partyRole filter):
```java
package org.openboxes.organization.controller;

import org.openboxes.organization.dto.PartyRoleDto;
import org.openboxes.organization.dto.PartyTypeDto;
import org.openboxes.organization.service.PartyRoleService;
import org.openboxes.organization.service.PartyTypeCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/organization")
public class ReferenceController {

    private final PartyTypeCache cache;
    private final PartyRoleService roles;

    public ReferenceController(PartyTypeCache c, PartyRoleService r) {
        this.cache = c;
        this.roles = r;
    }

    @GetMapping("/partyType")
    public Map<String, List<PartyTypeDto>> listPartyTypes() {
        return Map.of("data", cache.getAll().stream().map(PartyTypeDto::from).toList());
    }

    @GetMapping("/partyType/{id}")
    public ResponseEntity<Map<String, PartyTypeDto>> readPartyType(@PathVariable String id) {
        return cache.getById(id)
            .map(pt -> ResponseEntity.ok(Map.of("data", PartyTypeDto.from(pt))))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/partyRole")
    public Map<String, List<PartyRoleDto>> listPartyRoles(
        @RequestParam(required = false) String partyId,
        @RequestParam(required = false) String roleType
    ) {
        return Map.of("data", roles.findBy(partyId, roleType));
    }
}
```

- [ ] **Step 4: Rebuild + smoke-test all 7 endpoints**
```bash
cd services && ./gradlew :organization-service:bootJar
cd ../docker && sudo docker-compose up -d --build organization-service
# Get a real obx_token via login (Phase 2 identity-service)
TOKEN=$(curl -sf -X POST -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"password"}' \
    -c - http://localhost/api/identity/login | grep obx_token | awk '{print $7}')
# Test all 7 endpoints (still container-direct since T8 hasn't wired nginx):
sudo docker exec openboxes-organization-service curl -sf -b "obx_token=$TOKEN" localhost:8084/api/organization/partyType | jq length
sudo docker exec openboxes-organization-service curl -sf -b "obx_token=$TOKEN" localhost:8084/api/organization | jq '.data | length'
# Pick a real org ID from DB:
ORG=$(sudo docker exec openboxes-db mariadb -u root -proot openboxes -se "SELECT id FROM party WHERE class LIKE '%Organization%' LIMIT 1")
sudo docker exec openboxes-organization-service curl -sf -b "obx_token=$TOKEN" "localhost:8084/api/organization/$ORG" | jq .
sudo docker exec openboxes-organization-service curl -sf -b "obx_token=$TOKEN" "localhost:8084/api/organization/party/$ORG" | jq .
# POST test:
sudo docker exec openboxes-organization-service curl -sf -b "obx_token=$TOKEN" -X POST -H 'Content-Type: application/json' \
    -d '{"name":"T7SmokeTest"}' localhost:8084/api/organization | jq .
# 401 test:
sudo docker exec openboxes-organization-service curl -sI localhost:8084/api/organization/$ORG  # Expected: HTTP/1.1 401
```

- [ ] **Step 5: Commit**
```bash
git add services/organization-service/src/main/java/org/openboxes/organization/controller/
git commit -m "phase 4 task 7: REST controllers (3 controllers; 7 endpoints) — OrganizationController (read+list+POST with {data: ...} envelope mirroring Grails), PartyController (polymorphic party read), ReferenceController (partyType list+read from cache + partyRole filter); 401 on missing JWT, 404 on missing id, 201 on POST"
```

---

### Task 8: nginx routing + delete OrganizationApiController.groovy

**Files:**
- Modify: `docker/nginx/conf.d/app.conf`, `docker/docker-compose.yml`
- Delete: `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy`

- [ ] **Step 1: Add nginx blocks** (`docker/nginx/conf.d/app.conf`) BEFORE the `/api/` catch-all, after the existing `/api/location/` prefix block (around line 44):
```nginx
    # Phase 4: organization-service. Exact-match + prefix per Phase 3 RC-T8/T12 pattern.
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

- [ ] **Step 2: Add organization-service to nginx depends_on** (`docker/docker-compose.yml` lines 41-50; insert after location-service entry):
```diff
       depends_on:
         app:
           condition: service_healthy
         document-service:
           condition: service_healthy
         identity-service:
           condition: service_healthy
         location-service:
           condition: service_healthy
+        organization-service:
+          condition: service_healthy
```

- [ ] **Step 3: Delete the Grails controller**
```bash
git rm grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy
```

- [ ] **Step 4: Rebuild full stack + verify routing**
```bash
cd docker && sudo docker-compose down && sudo docker-compose up -d --build
# Wait for all 7 containers healthy:
for i in {1..60}; do
    HEALTHY=$(sudo docker ps --filter name=openboxes --filter health=healthy --format '{{.Names}}' | wc -l)
    if [ "$HEALTHY" -ge 6 ]; then break; fi
    sleep 5
done
sudo docker ps --filter name=openboxes
TOKEN=$(curl -sf -X POST -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"password"}' \
    -c - http://localhost/api/identity/login | grep obx_token | awk '{print $7}')

# Verify org-service routing through nginx (singular):
ORG=$(sudo docker exec openboxes-db mariadb -u root -proot openboxes -se "SELECT id FROM party WHERE class LIKE '%Organization%' LIMIT 1")
curl -sf -b "obx_token=$TOKEN" http://localhost/api/organization/$ORG | jq .
# Verify LIST endpoint via exact-match (bare path):
curl -sf -b "obx_token=$TOKEN" "http://localhost/api/organization?active=true" | jq '.data | length'

# Verify /api/organizations (plural) now returns 404 from Grails (controller deleted):
curl -sI -b "obx_token=$TOKEN" http://localhost/api/organizations
# Expected: HTTP/1.1 404 (per plan vassump #27; T1 verified this)
# If 500 instead → halt + investigate (vassump #27 wrong → re-evaluate T10 test #5)
```

- [ ] **Step 5: Commit**
```bash
git add docker/nginx/conf.d/app.conf docker/docker-compose.yml
git commit -m "phase 4 task 8: nginx /api/organization routing (exact-match + prefix) + delete OrganizationApiController.groovy + nginx depends_on — exact-match for bare-path LIST (Phase 3 T8/T12 pattern), prefix for /{id}/etc; OrganizationApiController.groovy deleted (38 LOC); /api/organizations (plural) now returns 404 from Grails per FD#4; nginx waits for organization-service health"
```

---

### Task 9: JUnit + TestContainers integration tests

**Files:**
- Create: `services/organization-service/src/test/java/org/openboxes/organization/OrganizationServiceIntegrationTest.java`
- Create: `services/organization-service/src/test/resources/seed.sql`

- [ ] **Step 1: Create seed fixture** (`services/organization-service/src/test/resources/seed.sql`) — PartyType + Party (bare) + Organization × 3 + PartyRole × 5-6 + 1-2 Address rows. Use the A28-verified discriminator value (substitute placeholder if T1 found different):

```sql
-- PartyType reference data (mirrors Grails seed at changelog-2018-05-30-2315-insert-party-type-data.xml)
INSERT INTO party_type (id, version, code, name, party_type_code, date_created, last_updated) VALUES
    ('pt-org-001', 0, 'ORG', 'Organization', 'ORGANIZATION', NOW(), NOW()),
    ('pt-prs-001', 0, 'PERSON', 'Person', 'PERSON', NOW(), NOW());

-- 3 Organization rows (class value from A28 verification — placeholder below assumes FQCN)
INSERT INTO party (id, version, class, party_type_id, code, name, description, active, date_created, last_updated) VALUES
    ('org-acme', 0, 'org.pih.warehouse.core.Organization', 'pt-org-001', 'ACM', 'Acme Inc', 'Acme test org', 1, NOW(), NOW()),
    ('org-beta', 0, 'org.pih.warehouse.core.Organization', 'pt-org-001', 'BET', 'Beta Corp', 'Beta test org', 1, NOW(), NOW()),
    ('org-inactive', 0, 'org.pih.warehouse.core.Organization', 'pt-org-001', 'INA', 'Inactive Co', 'Inactive test org', 0, NOW(), NOW());

-- 1 bare Party row for polymorphic test (class value also from A28; placeholder assumes FQCN)
INSERT INTO party (id, version, class, party_type_id, date_created, last_updated) VALUES
    ('party-bare', 0, 'org.pih.warehouse.core.Party', 'pt-prs-001', NOW(), NOW());

-- PartyRole rows (raw string roleType per CDR R1 §2.1)
INSERT INTO party_role (id, version, party_id, role_type) VALUES
    ('pr-acme-supplier', 0, 'org-acme', 'ROLE_SUPPLIER'),
    ('pr-acme-buyer', 0, 'org-acme', 'ROLE_BUYER'),
    ('pr-beta-buyer', 0, 'org-beta', 'ROLE_BUYER'),
    ('pr-bare-arbitrary', 0, 'party-bare', 'ROLE_RANDOM_NEW_VALUE');  -- verifies String tolerance (vs enum)

-- Address (defined for ddl-auto:validate; no endpoint accesses it)
INSERT INTO address (id, address, city, country) VALUES
    ('addr-001', '123 Main St', 'Boston', 'USA');
```

- [ ] **Step 2: Create `OrganizationServiceIntegrationTest.java`** with the 15-18 tests per spec §11.1 (`@DynamicPropertySource` MUST include all 3 properties per Phase 3 CIR R2 lesson):
```java
package org.openboxes.organization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrganizationServiceIntegrationTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mariadb::getJdbcUrl);
        r.add("spring.datasource.username", mariadb::getUsername);
        r.add("spring.datasource.password", mariadb::getPassword);
        r.add("openboxes.jwt.secret", () -> "test-secret-32-chars-minimum-for-hs256-key");
        r.add("openboxes.identifier.organization.minSize", () -> "2");
        r.add("openboxes.identifier.organization.maxSize", () -> "3");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        // Phase 3 CIR R2 lesson — MUST include all 3:
        r.add("spring.jpa.defer-datasource-initialization", () -> "true");
        r.add("spring.sql.init.data-locations", () -> "classpath:seed.sql");
        r.add("spring.sql.init.mode", () -> "always");
    }

    @Autowired MockMvc mvc;

    private static final String TEST_SECRET = "test-secret-32-chars-minimum-for-hs256-key";

    private String validToken() {
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(TEST_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return io.jsonwebtoken.Jwts.builder()
            .subject("test-user")
            .claim("roles", java.util.List.of("ROLE_BROWSER"))
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000L))
            .signWith(key).compact();
    }

    private jakarta.servlet.http.Cookie authCookie() {
        return new jakarta.servlet.http.Cookie("obx_token", validToken());
    }

    @Test void readById_returns200() throws Exception {
        mvc.perform(get("/api/organization/org-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("org-acme"))
            .andExpect(jsonPath("$.data.code").value("ACM"))
            .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test void readById_returns404ForMissing() throws Exception { /* GET /api/organization/nonexistent → 404 */ }
    @Test void list_returnsAll() throws Exception { /* default → 3 orgs */ }
    @Test void list_filtersByQ() throws Exception { /* q=Acme → 1 */ }
    @Test void list_filtersBySingleRoleType() throws Exception { /* roleType=ROLE_SUPPLIER → 1 */ }
    @Test void list_filtersByMultiRoleType() throws Exception { /* roleType=ROLE_SUPPLIER&roleType=ROLE_BUYER → 2 */ }
    @Test void list_filtersByActive() throws Exception { /* active=false → 1 */ }
    @Test void list_paginates() throws Exception { /* max=1&offset=1 → 1 row */ }
    @Test void readById_returns401WithoutJwt() throws Exception { /* GET without cookie → 401 */ }
    @Test void create_returnsCreatedWithGeneratedCode() throws Exception { /* POST {name:"NewOrg"} → 201; data.id present */ }
    @Test void create_returnsCreatedWithProvidedCode() throws Exception { /* POST {name:"X", code:"XYZ"} → 201 */ }
    @Test void create_returns400OnMissingName() throws Exception { /* POST {} → 400 (validation) */ }
    @Test void readPartyById_returnsBaseShapeForOrganization() throws Exception { /* GET /api/organization/party/org-acme → 200; data.id="org-acme"; no `code` field (PartyDto shape) */ }
    @Test void readPartyById_returnsBaseShapeForBareParty() throws Exception { /* GET /api/organization/party/party-bare → 200; A28 polymorphic test */ }
    @Test void partyTypeCache_returnsCachedListOnSecondCall() throws Exception { /* 2× GET /api/organization/partyType → both 200; 2 types */ }
    @Test void partyTypeCache_refreshOnEmptyList() throws Exception { /* RC-6 fix verification — empty cache refreshes on getAll() */ }
    @Test void partyRole_findByPartyAndRoleType() throws Exception { /* GET /api/organization/partyRole?partyId=org-acme&roleType=ROLE_BUYER → 1 */ }
    @Test void partyRole_arbitraryRoleTypeStringWorks() throws Exception {
        /* GET /api/organization/party/party-bare → 200 even though 'ROLE_RANDOM_NEW_VALUE' is not in any enum (verifies CDR R1 §2.1 String fix) */
    }
}
```

(Implementer fills each test body using MockMvc + token helpers; mirror Phase 3 patterns.)

- [ ] **Step 3: Run the suite**
```bash
cd services && sudo -E ./gradlew :organization-service:test
# Expected: 18 tests pass; BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit**
```bash
git add services/organization-service/src/test/
git commit -m "phase 4 task 9: JUnit + TestContainers integration tests (18 tests) — covers all 7 endpoints, auth paths, POST create, polymorphic Party read (bare + Organization), PartyTypeCache RC-6 fix verification, arbitrary roleType String value tolerance (CDR R1 §2.1 String fix); seed.sql with discriminator values per A28"
```

---

### Task 10: React URL migration + Playwright E2E

**Files:**
- Modify: `src/js/utils/option-utils.jsx`, `src/js/actions/index.js`, `src/js/components/locations-configuration/modals/AddOrganizationModal.jsx`
- Create: `e2e/tests/organization-service.spec.ts`

- [ ] **Step 1: Migrate 4 React call sites** — `/api/organizations` (plural) → `/api/organization` (singular):

`src/js/utils/option-utils.jsx:191`:
```diff
-      apiClient.get(`/api/organizations?q=${searchTerm}${roleTypes ? roleTypes.map((roleType) => `&roleType=${roleType}`).join('') : ''}&active=${active}`)
+      apiClient.get(`/api/organization?q=${searchTerm}${roleTypes ? roleTypes.map((roleType) => `&roleType=${roleType}`).join('') : ''}&active=${active}`)
```

`src/js/utils/option-utils.jsx:225`:
```diff
-  apiClient.get(`/api/organizations?${roleTypes ? roleTypes.map((roleType) => `&roleType=${roleType}`).join('') : ''}&active=${active}`)
+  apiClient.get(`/api/organization?${roleTypes ? roleTypes.map((roleType) => `&roleType=${roleType}`).join('') : ''}&active=${active}`)
```

`src/js/actions/index.js:561`:
```diff
-    apiClient.get(`/api/organizations?roleType=ROLE_BUYER&active=${active}`)
+    apiClient.get(`/api/organization?roleType=ROLE_BUYER&active=${active}`)
```

`src/js/components/locations-configuration/modals/AddOrganizationModal.jsx:59`:
```diff
-      const locationUrl = '/api/organizations';
+      const locationUrl = '/api/organization';
```

- [ ] **Step 2: Verify React build doesn't break**
```bash
# Standard frontend build verification (whatever the project uses; if there's a per-app TypeScript/eslint check)
grep -rn "/api/organizations" src/js/  # Expected: empty (or non-/api/organizations matches like /api/generic/organization)
```

- [ ] **Step 3: Create Playwright E2E spec** (`e2e/tests/organization-service.spec.ts`):
```typescript
import { test, expect } from '@playwright/test';

const BASE = process.env.BASE_URL ?? 'http://localhost';
const USER = process.env.E2E_USER ?? 'admin';
const PASS = process.env.E2E_PASSWORD ?? 'password';

async function login(request: any) {
    const res = await request.post(`${BASE}/api/identity/login`, {
        data: { username: USER, password: PASS },
    });
    expect(res.ok()).toBeTruthy();
    return res.headers()['set-cookie'];
}

test.describe('organization-service via nginx', () => {
    test('GET /api/organization (list) returns 200 with {data: [...]}', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/organization`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data).toBeTruthy();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/organization/{id} returns flat DTO', async ({ request }) => {
        const cookie = await login(request);
        // Fetch a real organization ID from the list:
        const listRes = await request.get(`${BASE}/api/organization`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No organizations in DB');
        const orgId = list.data[0].id;
        const res = await request.get(`${BASE}/api/organization/${orgId}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(orgId);
        expect(body.data).toHaveProperty('partyTypeCode');  // flat scalar, not nested partyType.code
    });

    test('POST /api/organization creates an organization via AddOrganizationModal flow', async ({ request }) => {
        const cookie = await login(request);
        const name = `E2E-Test-${Date.now()}`;
        const res = await request.post(`${BASE}/api/organization`, {
            headers: { Cookie: cookie, 'Content-Type': 'application/json' },
            data: { name, description: 'E2E test' },
        });
        expect(res.status()).toBe(201);
        const body = await res.json();
        expect(body.data.id).toBeTruthy();
        // Subsequent list includes it:
        const listRes = await request.get(`${BASE}/api/organization?q=${encodeURIComponent(name)}`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        expect(list.data.some((o: any) => o.id === body.data.id)).toBeTruthy();
    });

    test('GET /api/organization/partyType returns cached list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/organization/partyType`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.length).toBeGreaterThan(0);
    });

    test('regression: /api/organizations (plural) returns 404 — controller deleted', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/organizations`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(404);
    });

    test('baseline preservation: Phase 1+2+3 endpoints still work', async ({ request }) => {
        const cookie = await login(request);
        const me = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        expect(me.status()).toBe(200);
        const locType = await request.get(`${BASE}/api/location/type`, { headers: { Cookie: cookie } });
        expect(locType.status()).toBe(200);
    });
});
```

- [ ] **Step 4: Run Playwright suite**
```bash
cd e2e && npm test
# Expected: 6 new tests pass + all baseline (Phase 1+2+3 ~29 tests) still pass
```

- [ ] **Step 5: Commit**
```bash
git add src/js/utils/option-utils.jsx src/js/actions/index.js src/js/components/locations-configuration/modals/AddOrganizationModal.jsx e2e/tests/organization-service.spec.ts
git commit -m "phase 4 task 10: React URL migration (4 call sites in 3 files) + Playwright E2E (6 tests) — /api/organizations → /api/organization (singular); AddOrganizationModal POST flow; regression for /api/organizations 404; Phase 1+2+3 baseline preservation"
```

---

### Task 11: CI workflow probe + log dump

**Files:**
- Modify: `.github/workflows/e2e-tests.yml`

- [ ] **Step 1: Add organization-service to bootJar build step** (around line 36):
```diff
-        run: ./gradlew :identity-service:bootJar :document-service:bootJar :location-service:bootJar
+        run: ./gradlew :identity-service:bootJar :document-service:bootJar :location-service:bootJar :organization-service:bootJar
```

- [ ] **Step 2: Add organization-service healthcheck probe to the boot-wait loop** (around line 44; insert after location-service probe):
```diff
           for i in {1..60}; do
             curl -sf http://localhost/openboxes/health \
               && docker exec openboxes-document-service curl -sf localhost:8081/actuator/health \
               && docker exec openboxes-identity-service curl -sf localhost:8082/actuator/health \
               && docker exec openboxes-location-service curl -sf localhost:8083/actuator/health \
+              && docker exec openboxes-organization-service curl -sf localhost:8084/actuator/health \
               && [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost/api/documents)" != "502" ] \
               && break
             sleep 5
           done
```

- [ ] **Step 3: Add organization-service log dump to diagnostic step** (around line 78):
```diff
           echo "---location-service---" && docker logs openboxes-location-service 2>&1 | tail -100 || true
+          echo "---organization-service---" && docker logs openboxes-organization-service 2>&1 | tail -100 || true
           echo "---nginx---" && docker logs openboxes-nginx 2>&1 | tail -50 || true
```

- [ ] **Step 4: Commit**
```bash
git add .github/workflows/e2e-tests.yml
git commit -m "ci: e2e-tests workflow builds organization-service jar + probes its health + dumps logs on failure — mirrors location-service pattern from Phase 3"
```

---

### Task 12: Done-gate verification + 15-minute soak + tag `phase-4-organization`

**Files:** (no code changes; verification + tag)

Per spec §2 done state + Phase 3 done-gate cadence:

- [ ] **Step 1: Clean rebuild from scratch**
```bash
cd services && ./gradlew :organization-service:bootJar :location-service:bootJar :identity-service:bootJar :document-service:bootJar
cd ../docker && sudo docker-compose down -v && sudo docker-compose up -d --build
```

- [ ] **Step 2: Wait for 7 containers Up healthy**
```bash
for i in {1..60}; do
    HEALTHY=$(sudo docker ps --filter name=openboxes --filter health=healthy --format '{{.Names}}' | wc -l)
    if [ "$HEALTHY" -ge 6 ]; then break; fi  # nginx has no healthcheck; 6 healthy + 1 nginx running
    sleep 5
done
sudo docker ps --filter name=openboxes --format "table {{.Names}}\t{{.Status}}"
# Expected: 7 containers (db, app, document, identity, location, organization, nginx) all Up
```

- [ ] **Step 3: Healthcheck**
```bash
sudo docker exec openboxes-organization-service curl -sf localhost:8084/actuator/health | jq .
# Expected: {"status":"UP"}
```

- [ ] **Step 4: Smoke-test all 7 endpoints + POST + 404 regression** (per T7 Step 4 + T8 Step 4 commands; with valid obx_token)

- [ ] **Step 5: Verify ddl-auto:validate passes**
```bash
sudo docker logs openboxes-organization-service 2>&1 | grep -iE "validation|schema|missing column|missing table"
# Expected: no error lines
```

- [ ] **Step 6: Grep gates** (per spec §2 done state):
  - **`/api/organizations` returns 404:** `curl -sI -b "obx_token=$TOKEN" http://localhost/api/organizations` → HTTP/1.1 404
  - **OrganizationApiController deleted:** `git ls-files | grep OrganizationApiController` → empty
  - **No write endpoints beyond POST /api/organization:** `grep -rnE '@PutMapping|@DeleteMapping|@PatchMapping' services/organization-service/src/main/java/` → empty
  - **Generic URL mapping at UrlMappings.groovy:935 UNCHANGED:** `git diff phase-3-location..HEAD -- grails-app/controllers/org/pih/warehouse/UrlMappings.groovy` → empty

- [ ] **Step 7: Run JUnit suites**
```bash
cd services && sudo -E ./gradlew :organization-service:test :location-service:test :identity-service:test :document-service:test
# Expected: all 4 test suites pass; BUILD SUCCESSFUL
```

- [ ] **Step 8: Run Playwright suite**
```bash
cd e2e && npm test
# Expected: 6 new + ~29 baseline = ~35 tests pass
```

- [ ] **Step 9: 15-minute soak** (memory + log monitoring)
```bash
for i in 1 2 3 4; do
    sudo docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}" --filter name=openboxes | tee /tmp/soak-phase4-$i.txt
    sleep 300
done
sudo docker logs openboxes-organization-service 2>&1 | grep -iE "exception|error" | grep -v "INFO\|DEBUG" | head -20
# Expected: empty
```

- [ ] **Step 10: Push to origin** (per per-push gate — STOP and ask user before this step)
```bash
# After explicit user confirmation:
git push origin main
gh run watch
```

- [ ] **Step 11: Tag `phase-4-organization` at done-gate-green HEAD** (after CI green)
```bash
git tag phase-4-organization $(git rev-parse HEAD)
# Per per-push gate — STOP and ask user before this step:
git push origin phase-4-organization
```

- [ ] **Step 12: Commit** (none for this task — verification + tagging only)

---

### Task 13: Phase 4 retrospective

**Files:**
- Create: `docs/retrospectives/YYYY-MM-DD-phase-4-organization-retrospective.md` (replace `YYYY-MM-DD` with done-gate date)

Mirror Phase 1+2+3 retro structure.

- [ ] **Step 1: Write YAML frontmatter** (date, phase, tag = phase-4-organization, commit_range = `phase-3-location..<done-gate-HEAD>`, plan, spec_section)

- [ ] **Step 2: TL;DR paragraph** — 1 paragraph summary of slice outcome (first React-facing POST in extracted service; SINGLE_TABLE inheritance first-of-kind; A28 empirical-verification pattern)

- [ ] **Step 3: What worked** — Phase 4-specific:
  - A28 hard-gate pattern (T1 verification before T2 caught discriminator drift OR confirmed FQCN early; no T2 churn)
  - SINGLE_TABLE inheritance with explicit `@DiscriminatorValue` on BOTH Party and Organization (CDR R1 §2.2 + CDR R2 §2.1 symmetric fix)
  - Raw String PartyRole.roleType (CDR R1 §2.1) — avoided 60+-value enum mirror trap
  - Boolean active = true initializer (CDR R1 §2.3) — avoided NULL persist via Hibernate INSERT default behavior
  - First React-facing POST in extracted service worked cleanly
  - 4th security copy reaffirms Phase X jwt-auth-common need

- [ ] **Step 4: Codebase / env gotchas** — sub-grouped (Build & deploy, Code-level, Container, Runtime):
  - Carry forward Phase 1+2+3 retro gotchas
  - NEW this slice: SINGLE_TABLE discriminator empirical verification pattern (A28 SQL probe); Apache Commons lang vs lang3 vs text confusion for OrganizationIdentifierService; Grails 404-on-missing-controller verification before delete

- [ ] **Step 5: Process / meta-lessons** — capture:
  - CDR R1+R2+R3 cadence (R3 caught the symmetric Party-discriminator partial-fix gap; R3 came back ✅ confirming the loop closed)
  - The "negative claim requires empirical evidence" pattern (CDR R2 §2.1 surfaced the asymmetric Party fix gap that R1 missed)
  - Plan-level back-port: spec §7.1 missed `LocationController.groovy:103` as a 4th `findOrCreateSupplierOrganization` caller — back-port to spec §7.1

- [ ] **Step 6: Forward to Phase 5** — Catalog slice (Product / Category / ProductSupplier / ProductPackage / etc. — confirm scope from parent design §4.3)

- [ ] **Step 7: Phase X carry-forward** — list deferred items:
  - jwt-auth-common library extraction (4 copies now; spec FD#6)
  - LoadDataService / MigrationService / LocationImportDataService write decoupling (Phase 7+ sagas)
  - LocationController.groovy:103 findOrCreateSupplierOrganization (now also Phase 7+ saga; spec §7.1 back-port)
  - OrganizationIdentifierService TODO bugs (spec §13 carry-forward)
  - Grails OrganizationController GSP admin migration (Phase 12)
  - PUT/DELETE/CSV download endpoints (YAGNI; revisit if React caller surfaces)
  - PartyType / Address write endpoints (admin-managed via Grails GSP)
  - Supplier (SQL view) / Donor (donation/) / Shipper (shipping/) entities (their respective phases)
  - Organization.sequences modeling (YAGNI)
  - Phase X items 1-6 from Phase 3 retro (carry forward)

- [ ] **Step 8: Artifacts** — links to spec, plan, CDR R1/R2/R3, UDD commits, tag, commit range

- [ ] **Step 9: Commit + push** (per per-push gate — STOP and ask user)
```bash
git add docs/retrospectives/YYYY-MM-DD-phase-4-organization-retrospective.md
git commit -m "phase 4: organization-service retrospective"
# After user confirmation:
git push origin main
```

---

## Tasks NOT in this plan

Inherited from spec §15. A new spec → new plan cycle is required to add any of these:

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

---

## Known issues inherited from spec

From spec §13. These exist in the implementation by design — accepted by the user during brainstorming + CDR R1/R2/R3:

- **`/api/organizations` (plural) is a hard URL deletion (FD#4).** Any external integration, browser bookmark, or undocumented client hitting the old plural URL receives 404. User-approved at brainstorming. No deprecation period.
- **OrganizationIdentifierService port includes Grails' TODO-marked bugs verbatim** (line 42-43: `suffix++` produces `':'` when suffix='9'; line 49: degrades when length exceeds maxSize). Phase 4 ports, doesn't refactor. Bug fix is a Phase X carry-forward.
- **Read-after-write across processes.** LoadDataService bootstrap creates Organizations via GORM; organization-service JPA reads see them on next query (no transactional handoff). Acceptable: bootstrap is one-shot at app boot with no concurrent readers. Migration/Import are admin-rare with manual triggering.
- **Flat FK-only DTO is a behavior departure (FD#3).** Consumers reading nested `partyType.code` / `roles[i].roleType.name` from Grails responses must accept flat shape from org-service. The 3 migrated React files don't navigate nested fields — verified at A13.
- **PartyType IDs are hardcoded in Grails seed** (id=1 for 'ORG', id=2 for 'PERSON' at `changelog-2018-05-30-2315-insert-party-type-data.xml:10,27`). organization-service looks up PartyType by `code`, never by id (defensive against ID drift).
- **`Organization.sequences` is NOT modeled** in JPA (YAGNI). The `organization_sequences` join table stays in the DB for Grails legacy reads.
- **PartyRole `roleType` is a raw `String` column** (not a JPA enum). Rationale: Grails `RoleType` has 60+ values; mirroring as a subset enum + `@Enumerated(STRING)` + EAGER fetch on `Party.roles` would throw on first read of any stored value outside the subset. Raw String avoids the synchronization debt entirely. Trade-off: typos in `?roleType=` filter values silently return empty results instead of 400 (acceptable — consumers controlling the filter string already know the valid values; an unknown one returning [] is the same UX as a known one with no rows).
