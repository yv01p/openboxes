# Phase 6 (core) — Inventory Service Slice — Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. Each task is one git commit unless a step says otherwise. **Light-SDD calibration (carried Phase 4→5.5):** direct-apply only when (plan-verbatim AND no business logic AND <20 LOC); full subagent cycle otherwise. **Per-task gate cadence (Phase 3+4+4.1+5+5.5 retro lesson):** stop after each task's two-stage review (spec-compliance + code-quality) for user disposition. In-task commit messages use the subject-only convention `phase 6 task N: <description>` — NO `Co-Authored-By` trailer (A23/RC-23).

**Source spec:** `docs/specs/2026-06-03-phase-6-inventory-service-design.md` (commit `478321bf2`)

**Goal:** Stand up `inventory-service` (6th Spring Boot service, port 8086, pure read-only), have it own 8 foundational inventory tables proven by `ddl-auto=validate`, resolve the Phase-5 `ProductAvailability` deferral (own-read / defer-refresh), and migrate the live RC-16 product-classification read — behavior-preserving — deleting the Grails original.

**Architecture:** A new Spring Boot 3.3.5 / Java 21 service cloned from the catalog-service shape (`jwt-auth-common` starter, `expose:`-only, shared MariaDB). It maps 8 inventory tables as flat JPA `@Entity` classes (cross-context FKs carried as flat id columns per FD#6; no JPA inheritance per FD#7) and takes Liquibase shadow-changelog ownership (`tableExists` precondition + empty body). The only HTTP surface is the RC-16 endpoint `GET /api/facilities/{facilityId}/products/classifications`, which unions a local distinct `inventory_level.abc_class` (facility-scoped) with a global distinct `product.abc_class` fetched from a **new read-only catalog-service endpoint** over Spring `RestClient`, forwarding the caller's `obx_token`. All inventory writes stay on Grails (Phase 6.5+).

**Tech stack:** Spring Boot 3.3.5, Java 21, Spring Data JPA + Hibernate 6 (MariaDBDialect, `ddl-auto=validate`), Liquibase (shadow changelogs), `jwt-auth-common` starter (cookie `obx_token`), Spring `RestClient`, MariaDB, TestContainers (JUnit 5), nginx (regex location), Playwright. Grails build = Temurin 8; services build = JDK 21 from `cd services` (RC-55).

---

## Spec → plan task mapping

This plan consolidates the spec's provisional T1–T11 into **10 tasks** and **reorders the Grails delete to land after the new endpoint is built, routed, tested, and e2e-verified** (strangler-safe; the spec's §7 risk section endorses "port tests as contract + verify before cutover"). The spec's `T4` (DTOs) is folded into `T4` here (the only core DTO is RC-16's single-field record — splitting it from the service that returns it is premature task-splitting).

| Plan | Spec | Task |
|---|---|---|
| T1 | T1 | Empirical audit + **user approval gate** |
| T2 | T2 | Module skeleton (Gradle, main class, application.yml, Dockerfile, SecurityConfig) |
| T3 | T3 | 8 JPA entities + 8 Liquibase shadow changelogs (DESCRIBE-first) |
| T4 | T4+T5 | RC-16 end-to-end (DTO + service + controller + CatalogReadClient + new catalog-service GET) |
| T5 | T6 | nginx (regex) + both compose files + CI + healthcheck |
| T6 | T8 | TestContainers `InventoryServiceIntegrationTest` (logic + ported RC-16 contract) |
| T7 | T9 | Real-payload ground-truth + seeded e2e through nginx + Playwright |
| T8 | T7 | Delete Grails `ProductClassification*` + grep-verify no dangling refs |
| T9 | T10 | Done-gate |
| T10 | T11 | Retro (A–F triage) + 6.5 forward pointer + tag `phase-6-inventory` |

---

## File Structure

**Create — inventory-service module** (under `services/inventory-service/`):
- `build.gradle` — clone of catalog-service build (Boot starters, `jwt-auth-common` project dep, liquibase, mariadb, testcontainers).
- `Dockerfile` — `eclipse-temurin:21-jre-jammy`, apt curl, non-root `spring` user, copy `build/libs/inventory-service-*.jar`, expose 8086.
- `src/main/resources/application.yml` — port 8086, datasource env, `ddl-auto=validate`, liquibase master changelog, `openboxes.jwt.secret`, `openboxes.services.catalog.base-url`.
- `src/main/java/org/openboxes/inventory/InventoryServiceApplication.java` — `@SpringBootApplication` only (NO `@EnableJpaAuditing` — read-only, FD#8).
- `src/main/java/org/openboxes/inventory/security/SecurityConfig.java` — clone of catalog SecurityConfig (JWT filter from starter; permit actuator/swagger; `anyRequest().authenticated()`).
- `src/main/java/org/openboxes/inventory/entity/{Inventory,InventoryItem,InventoryLevel,Transaction,TransactionEntry,TransactionType,TransactionSource}.java` + `src/main/java/org/openboxes/inventory/entity/ProductAvailability.java` — 8 flat `@Entity` classes (read-only, getters-only).
- `src/main/java/org/openboxes/inventory/repository/{InventoryRepository,InventoryLevelRepository}.java` — only the 2 repos RC-16 needs (the other 6 entities are owned for `validate` + Phase 7/8; no repo until a reader exists — YAGNI).
- `src/main/java/org/openboxes/inventory/dto/ProductClassificationDto.java` — `record ProductClassificationDto(String name)`.
- `src/main/java/org/openboxes/inventory/client/CatalogReadClient.java` — `RestClient` to catalog-service; forwards `obx_token`.
- `src/main/java/org/openboxes/inventory/service/ProductClassificationService.java` — RC-16 union/sort/dedup + invalid-facility guard.
- `src/main/java/org/openboxes/inventory/controller/ProductClassificationController.java` — `GET /api/facilities/{facilityId}/products/classifications`.
- `src/main/resources/db/changelog/db.changelog-master.xml` + `changelog-shadow-create-{inventory,inventory_item,inventory_level,product_availability,transaction,transaction_entry,transaction_type,transaction_source}.xml` — 1 master + 8 shadows.
- `src/test/java/org/openboxes/inventory/InventoryServiceIntegrationTest.java` + `src/test/resources/seed.sql`.

**Create — catalog-service additions** (RC-16 Product side):
- `src/main/java/org/openboxes/catalog/controller/ProductAbcClassController.java` — `GET /api/products/abcClasses` (new tiny controller; NOT in ProductController, whose `/api/product/{id}` would shadow `/api/product/abcClasses`).

**Modify:**
- `services/settings.gradle` — add `include 'inventory-service'`.
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/Product.java` — add `@Column(name = "abc_class") private String abcClass;` + getter (the column exists; the entity currently omits it — `ddl-auto=validate` tolerates the addition).
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/ProductRepository.java` — add `findDistinctAbcClasses()` `@Query`.
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/ProductService.java` — add `distinctAbcClasses()`.
- `services/catalog-service/src/test/java/org/openboxes/catalog/CatalogServiceIntegrationTest.java` — add one test for `/api/products/abcClasses`; ensure `seed.sql` has products with `abc_class`.
- `docker/docker-compose-base.yml` — add `inventory-service` service block (8086).
- `docker/docker-compose.yml` — add `inventory-service` (`extends` + `depends_on`); add `inventory-service` to nginx `depends_on`.
- `docker/nginx/conf.d/app.conf` — add one **regex** location for the RC-16 path (before `/api/` catch-all).
- `.github/workflows/e2e-tests.yml` — append `:inventory-service:bootJar`; add health probe + diagnostic dump.

**Delete (T8 — after green tests + e2e):**
- `grails-app/controllers/org/pih/warehouse/api/ProductClassificationApiController.groovy`
- `grails-app/services/org/pih/warehouse/product/ProductClassificationService.groovy`
- `src/main/groovy/org/pih/warehouse/product/ProductClassificationDto.groovy`
- `src/test/groovy/org/pih/warehouse/product/ProductClassificationServiceSpec.groovy`
- `src/integration-test/groovy/org/pih/warehouse/api/spec/product/ProductClassificationApiCRUDSpec.groovy`
- `src/integration-test/groovy/org/pih/warehouse/api/spec/product/ProductClassificationApiListFiltersOutEmptySpec.groovy`
- `src/integration-test/groovy/org/pih/warehouse/api/client/product/ProductClassificationApiWrapper.groovy`
- `src/integration-test/groovy/org/pih/warehouse/api/client/product/ProductClassificationApi.groovy`
- `grails-app/controllers/org/pih/warehouse/UrlMappings.groovy` — remove the `/api/facilities/$facilityId/products/classifications` mapping (~line 139).
- **NOT deleted / NOT changed:** `src/js/api/services/ProductClassificationApi.js`, `src/js/utils/option-utils.jsx`, `src/js/hooks/cycleCount/useCycleCountFilters.js`, `src/js/api/urls.js` — the React path is preserved verbatim; nginx reroutes the same URL to inventory-service, so React needs no change.

---

## Inherited from spec

Verified by `thorough-brainstorming` at spec-write time (spec §8: A1–A31 + V1–V5). Trusted as ground truth; NOT re-verified here.

- **A8** `product_availability` is a real table (not a view) — `changelog-create-tables.groovy:2046`.
- **A9/A10** `ProductAvailabilityService` (1386 LOC) is the sole writer; stays Grails (refresh → 6.5).
- **A11/V5** RC-16 is a **behavior-preserving migration of a working, live-consumed endpoint** (React CycleCount filter), NOT a bug fix; the "500" is the invalid-facility guard by design.
- **A12–A14** single `list(facilityId)` action; the service's only caller is its controller.
- **A13** service reads Location + InventoryLevel (by `facility.inventory`) + Product.abcClass.
- **A15** `abcClass` is a plain column on both `Product` and `InventoryLevel`; no `AbcClass` entity (stays deferred).
- **A16/V1** facility→inventory resolves locally via the `Inventory.warehouse` FK; the Product side is a distinct-aggregate served by a **new read-only catalog-service GET** (owner-exists → HTTP, parent §4.3).
- **A18/A22** port 8086 free; services are `expose:`-only.
- **A19** `jwt-auth-common` consumed via `implementation project(':jwt-auth-common')`.
- **A20** Spring Boot 3.3.5 / Java 21.
- **A23** per-table shadow-changelog pattern (`tableExists` MARK_RAN + empty body + namespaced `logicalFilePath`).
- **A24** strict Jackson is the service default (keep DTOs exact).
- **A25** no JPA inheritance across the 24 inventory domain classes → RC-1 inheritance rule N/A.
- **A26** audit columns present; read-only core needs no `AuditorAware` (FD#8).
- **A27** CHAR(38)-id / TINYINT-boolean divergence applies (DESCRIBE-first + `columnDefinition`).
- **A28** the only core entity collections are `Transaction.transactionEntries` + `Inventory.configuredProducts`; neither has a React read → RC-56 trap doesn't arise; flat DTOs anyway.
- **A29/A30/V2** GET surface is **consumed-only**: own all 8 tables; expose GET only for RC-16 + any T1-confirmed consumer; no speculative endpoints.
- **A31** Grails inventory consumers stay direct-JDBC (non-breaking) during transition.
- **V3** parent-design correction: StockMovement*/StockTransfer*/Replenishment have no domain class/table.
- **V4** RC-16 real path is `/api/facilities/$facilityId/products/classifications` (`UrlMappings.groovy:139`).

---

## Verified plan-level assumptions

Newly introduced by this plan and verified at plan-write time (repo HEAD `478321bf2`).

| # | Category | Assumption | Evidence |
|---|---|---|---|
| PA1 | File path | `services/inventory-service/` does not exist (T2 creates it) | `ls services/inventory-service` → No such file or directory |
| PA2 | File path | `services/settings.gradle` includes 5 services + `jwt-auth-common` (T2 adds inventory-service) | read: includes document/identity/location/organization/catalog + jwt-auth-common |
| PA3 | File path | catalog-service `build.gradle` exists (clone template) | `services/catalog-service/build.gradle` read (28 lines) |
| PA4 | Code-in-plan | catalog `application.yml` shape (port/datasource env/`ddl-auto: validate`/liquibase master/`openboxes.jwt.secret`) | read in full; uses `${DATASOURCE_URL}` etc., `liquibase.enabled: true` |
| PA5 | Code-in-plan | Dockerfile base = `eclipse-temurin:21-jre-jammy` + apt curl + `useradd -r spring` (NOT alpine) | catalog `Dockerfile` read |
| PA6 | File path / Signature | catalog `security/SecurityConfig.java` exists; injects `org.openboxes.auth.common.JwtCookieAuthFilter`; `anyRequest().authenticated()` | read in full |
| PA7 | File path | catalog main class `org.openboxes.catalog.CatalogServiceApplication` → inventory pkg `org.openboxes.inventory` | `find … *Application.java` |
| PA8 | Code-in-plan | shadow changelog template: `changeSet id="phase5-shadow-create-<t>"`, `tableExists` onFail MARK_RAN, empty body, namespaced `logicalFilePath` | `changelog-shadow-create-product.xml` read → inventory uses `phase6-…`, author `openboxes-inventory` |
| PA9 | File path | BOTH compose files live under `docker/`: `docker/docker-compose-base.yml` + `docker/docker-compose.yml` (no root-level base — spec wording loose) | `ls` → root file absent; `docker/` files present |
| PA10 | Code-in-plan | `docker/docker-compose.yml` uses `extends` + `depends_on` per service; nginx `depends_on` all services | confirmed via Explore of compose files |
| PA11 | File path | nginx file = `docker/nginx/conf.d/app.conf`; `docker/nginx/conf.d/proxy_params` exists | `ls` both present |
| PA12 | Command | CI `e2e-tests.yml`: JDK 8 Grails `prepareDocker`, JDK 21 services jars (`working-directory: services`), 60-iter health loop | read in full |
| PA13 | File path | all 8 inventory domain files exist at expected paths | `ls` all 8 present |
| PA14 | File path | RC-16 delete-targets exist (8 Grails files): controller, service, dto, unit spec, 2 integration specs, **2 test-client files** (`ProductClassificationApiWrapper.groovy` + `ProductClassificationApi.groovy`) | `find -iname '*ProductClassification*'` |
| PA15 | Ordering / Code-in-plan | RC-16 mapping at `UrlMappings.groovy:139`; `/api/facilities/$facilityId/inventory-levels` is the *adjacent* route that MUST stay Grails (Rule-3 evidence) | Explore: classifications at :139, inventory-levels at :143 |
| PA16 | File path | catalog `CatalogServiceIntegrationTest.java` exists (T4 adds a test) | `find` present |
| PA17 | File path | catalog has `ProductController`/`ProductService`/`ProductRepository`/`entity/Product.java` | `find` all present |
| PA18 | Signature | catalog `Product` entity does **NOT** map `abcClass` → T4 must add `@Column(name="abc_class") String abcClass` + getter | `Product.java` read (no abcClass field) |
| PA19 | Signature | `Inventory.warehouse` (belongsTo Location) → DB column `warehouse_id` CHAR(38) | `Inventory.groovy:19,26`; `changelog-create-tables.groovy:3659` `warehouse_id` CHAR(38) (NOTE: absent from the install `inventory` block 708-721 → schema drift; see PA50) |
| PA20 | Signature | `InventoryLevel` has `abc_class` VARCHAR(255) + `inventory_id` CHAR(38) (belongsTo Inventory) | `InventoryLevel.groovy:72,81`; install changelog: `inventory_id` + `abc_class` at lines 803/811 |
| PA21 | Signature | jwt filter = `org.openboxes.auth.common.JwtCookieAuthFilter`; cookie name constant `JwtService.COOKIE_NAME = "obx_token"` | `SecurityConfig.java:11`; `JwtService.java:14` |
| PA22 | Code-in-plan | CatalogReadClient forwards `Cookie: obx_token=<token>`; inventory controller reads the cookie from the incoming `HttpServletRequest` | `JwtService.COOKIE_NAME = "obx_token"` |
| PA23 | Code-in-plan | Boot `spring-boot-starter-web` provides `org.springframework.web.client.RestClient` (no extra dep) | catalog `build.gradle:4` starter-web; precedent below |
| PA24 | Code-in-plan | `ProductAvailability.quantityNotPicked` is a GORM `formula: "quantity_on_hand - quantity_allocated"` → JPA `@Formula(...)`, NOT a column | `ProductAvailability.groovy:43`; `id generator:"assigned"` |
| PA25/26 | Command | services build/test from `cd services`: `./gradlew :inventory-service:bootJar` / `:inventory-service:test`; CI line appends `:inventory-service:bootJar` | Phase 5 plan P40/P41; `e2e-tests.yml:57-58` |
| PA27 | Command | Grails build = Temurin 8; services = JDK 21 (CI uses both) | `e2e-tests.yml:42-55` |
| PA28 | Command | nginx done-gate = `docker compose exec nginx nginx -t` + `nginx -s reload` | Phase 5.5 plan:723-724 |
| PA29 | Command | plan-file commit convention = `add <topic> implementation plan` (plain subject, no prefix, no Co-Authored-By) | `git log docs/plans/` → "add phase-5-catalog-service implementation plan" |
| PA35/51 | Code-in-plan | nginx has **no** existing regex (`~`) locations; `/api/` is a plain prefix (not `^~`) → a new regex intercepts before the catch-all | `app.conf` read in full (only `=`, `/prefix/`, `/`) |
| PA36 | Code-in-plan | a regex `^/api/facilities/[^/]+/products/classifications$` matches ONLY that path; `/api/facilities/$id/inventory-levels` does not match → stays Grails | regex anchored on `…/products/classifications$` |
| PA37 | Code-in-plan | inventory-service maps cross-context FKs as flat id columns (no `Location` entity); `InventoryRepository.findByWarehouseId(String)` valid on a flat `warehouseId` field | FD#6 (inherited); RestClient/flat-FK precedent |
| PA38 | Code-in-plan | RC-16 logic = distinct global `product.abc_class` ∪ distinct facility-scoped `inventory_level.abc_class`, non-null + non-empty, dedup, sort | Grails `ProductClassificationService.list()` source captured |
| PA39 | Code-in-plan | `record ProductClassificationDto(String name)` → `{"name":..}`; controller `Map.of("data", list)` → `{"data":[{"name"}]}` matches `option-utils.jsx` `data.data[].name` | React consumer source captured |
| PA40 | Code-in-plan | RestClient base-url injected via `@Value("${openboxes.services.catalog.base-url}")` | standard Spring; config added in T2 |
| PA41-44 | Consumer impact | deleting `ProductClassificationService`/`Dto`/`ApiWrapper`/`Api`/UrlMapping breaks nothing outside the delete set | grep: all refs are within the 8 delete-targets only |
| PA45 | Consumer impact | catalog has no `/api/products` (plural) mapping → new `GET /api/products/abcClasses` collision-free (catalog uses `/api/product` singular) | grep catalog controllers |
| PA46 | Consumer impact | the new catalog endpoint is reached service-to-service at `http://catalog-service:8085` (NOT nginx-routed) → no nginx block for it | RestClient base-url = container address |
| PA48 | Consumer impact / DRY | outbound-HTTP precedent in services = Spring `RestClient` (`RecaptchaService` uses `RestClient.create()`) → CatalogReadClient follows it | `identity-service/.../RecaptchaService.java:5,16,31` |
| PA49 | Code-in-plan | 8 table names: `inventory`, `inventory_item`, `inventory_level`, `product_availability`, `transaction`, `transaction_entry`, `transaction_type`, `transaction_source` | install changelog lines 708/726/784/2046/3352/3408/3480 + `transaction_source` via `0.9.x/changelog-2025-10-08-1700-create-table-transaction-source.xml` |
| PA50 | Code-in-plan | the install `changelog-create-tables.groovy` is a historical snapshot **missing later-migration columns** (e.g. `inventory.warehouse_id`, `transaction_source.accurate`) → DESCRIBE-first against the LIVE DB is mandatory in T3 | `warehouse_id` absent from install `inventory` block; `transaction_source` + its `accurate` column in separate 2025 migrations |

**Verification-driven plan adjustments (mechanical; shape unchanged):**
1. Compose paths corrected to `docker/docker-compose-base.yml` + `docker/docker-compose.yml` (PA9).
2. Dockerfile base = jammy (PA5).
3. Delete set includes **2** test-client files, not 1 (PA14).
4. T4 adds `abcClass` to catalog `Product.java` + repo query (PA18); catalog endpoint lives in a **new** `ProductAbcClassController` to avoid `/api/product/{id}` shadowing (PA45).
5. CatalogReadClient uses Spring `RestClient` (PA48); forwards `obx_token` (user decision).
6. **TestContainers test uses `ddl-auto=create` (per the proven catalog precedent), not `validate`** — an empty TestContainers MariaDB has no schema to validate against. Live-schema `validate` proof is at T3 smoke-start (dev DB) + T9 compose boot (shared DB). (This corrects the spec T8's literal "ddl-auto=validate in TestContainers" wording.)
7. `version` columns are left **unmapped** across all 8 entities (read-only; `validate` tolerates extra table columns; matches catalog `Product` which omits `version`). Per-entity divergences (`ProductAvailability` implicit version + `@Formula`; `TransactionSource` `version false` + `accurate` TINYINT + `Instant` timestamps) are resolved by DESCRIBE-first in T3.
8. RC-16 invalid-facility guard becomes "no `Inventory` for `warehouse_id`" (local resolution, FD#5/V1) rather than Grails' "no `Location`" — behavior-equivalent for every tested and realistic case (invalid facility → 500; a real facility always has an Inventory). Documented in T4.

---

## Tasks

### Task 1: Empirical audit + user approval gate

No code changes; produces the finalized scope. **STOP for user approval before T2.**

**Files:** none (audit only).

- [ ] **Step 1: Confirm the IN/DEFER entity membership (FD#2).** Re-confirm the 8 IN entities and that snapshot/count/audit + CycleCount family + LocalTransfer/OutboundStockMovement*/Requirement DEFER (no consumed read; writers stay Grails). `grep -rl "Snapshot\|CycleCount\|InventoryAudit" grails-app/domain/org/pih/warehouse/inventory/`.
- [ ] **Step 2: Finalize the consumed-GET surface (FD#3/V2).** RC-16 is the one definite GET. Confirm/deny the `inventory-transactions-summary` candidate: `grep -rn "inventory-transactions-summary\|InventoryTransactionSummary" src/js grails-app/controllers`; verify (a) a real React consumer exists, (b) its owning Grails controller is GET-only, (c) it is pure-inventory (no cross-context join requiring forecasting/Product). **If all three hold → it is added as a sibling of RC-16** (one DTO + service + controller + nginx route, mirroring T4/T5); **otherwise DEFER to 6.5.** Record the decision.
- [ ] **Step 3: Confirm zero other React inventory GETs.** `grep -rnE "/api/(inventory|inventoryLevel|transaction|transactionEntry)" src/js` → expect none beyond what FD#3 lists.
- [ ] **Step 4: RC-16 path + Rule-3 audit.** Confirm the only `/api/facilities/.../products/classifications` mapping and that `/api/facilities/$facilityId/inventory-levels` (and any other `/api/facilities/*`) must remain on Grails. `grep -nE '"/api/facilities' grails-app/controllers/org/pih/warehouse/UrlMappings.groovy`.
- [ ] **Step 5: Per-controller delete/keep matrix.** ProductClassification* → DELETE (T8). InventoryApiController (`importCsv` write) → KEEP. ProductApiController (cross-context reads) → KEEP. Record.
- [ ] **Step 6: Cross-context atomic-write audit (parent §8 Step 1).** Confirm the migrated surface (RC-16, read-only) performs no cross-context writes. Trivially true for a read; record.
- [ ] **Step 7: Present the audit output to the user. STOP — do not start T2 until approved.** If T1 changes the consumed-GET surface, fold the sibling endpoint into T4/T5 before proceeding.

### Task 2: inventory-service module skeleton

Clone the catalog-service module shape. No business logic yet; the service must build and (smoke) start.

**Files:**
- Create: `services/inventory-service/build.gradle`, `Dockerfile`, `src/main/resources/application.yml`, `src/main/java/org/openboxes/inventory/InventoryServiceApplication.java`, `src/main/java/org/openboxes/inventory/security/SecurityConfig.java`, `src/main/resources/db/changelog/db.changelog-master.xml` (empty shell)
- Modify: `services/settings.gradle`

- [ ] **Step 1: `build.gradle`** — copy `services/catalog-service/build.gradle` verbatim (the dependency set is identical: starter-web/data-jpa/security/validation/actuator, liquibase, springdoc, jjwt api/impl/jackson, `implementation project(':jwt-auth-common')`, mariadb runtime, testcontainers test deps; `test { useJUnitPlatform(); systemProperty 'testcontainers.ryuk.disabled','true' }`). No new dependency is required (RestClient ships with starter-web).
- [ ] **Step 2: `settings.gradle`** — add `include 'inventory-service'` after the `catalog-service` line.
- [ ] **Step 3: `InventoryServiceApplication.java`**
  ```java
  package org.openboxes.inventory;

  import org.springframework.boot.SpringApplication;
  import org.springframework.boot.autoconfigure.SpringBootApplication;

  @SpringBootApplication
  public class InventoryServiceApplication {
      public static void main(String[] args) {
          SpringApplication.run(InventoryServiceApplication.class, args);
      }
  }
  ```
  (No `@EnableJpaAuditing` — read-only, FD#8.)
- [ ] **Step 4: `application.yml`**
  ```yaml
  server:
    port: 8086
  spring:
    application:
      name: inventory-service
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
      enabled: true
      change-log: classpath:db/changelog/db.changelog-master.xml
  management:
    endpoints:
      web:
        exposure:
          include: health,info
  openboxes:
    jwt:
      secret: ${OPENBOXES_JWT_SECRET}
    services:
      catalog:
        base-url: ${CATALOG_SERVICE_URL:http://catalog-service:8085}
  ```
  Also create the **empty master shell** now so the `change-log` path resolves cleanly: `src/main/resources/db/changelog/db.changelog-master.xml` containing only the `<databaseChangeLog>` root (no `<include>` yet — T3 adds the 8 includes). T2's smoke is `bootJar` (build only, does not run Liquibase), so an empty master is sufficient here; the first Liquibase run is T3's smoke-start.
- [ ] **Step 5: `SecurityConfig.java`** — copy catalog's verbatim, changing only the package to `org.openboxes.inventory.security`. (Keeps `anyRequest().authenticated()`; permits `/actuator/health`,`/actuator/info`,`/v3/api-docs/**`,`/swagger-ui/**`; adds `JwtCookieAuthFilter` before `UsernamePasswordAuthenticationFilter`.)
- [ ] **Step 6: `Dockerfile`** — copy catalog's verbatim, replacing `8085`→`8086` and `catalog-service`→`inventory-service`:
  ```dockerfile
  FROM eclipse-temurin:21-jre-jammy
  RUN apt-get update \
      && apt-get install -y --no-install-recommends curl \
      && rm -rf /var/lib/apt/lists/*
  EXPOSE 8086
  WORKDIR /app
  COPY build/libs/inventory-service-*.jar /app/inventory-service.jar
  RUN useradd -r spring
  USER spring
  ENTRYPOINT ["java", "-jar", "/app/inventory-service.jar"]
  ```
- [ ] **Step 7: Smoke build** — `(cd services && ./gradlew :inventory-service:bootJar)` succeeds.
- [ ] **Step 8: Commit** — `git add services/inventory-service services/settings.gradle` then `git commit -m "phase 6 task 2: inventory-service module skeleton (Boot 3.3.5/Java 21; jwt-auth-common starter; port 8086; ddl-auto=validate; SecurityConfig clone)"`.

### Task 3: 8 JPA entities + 8 Liquibase shadow changelogs

The heaviest task. **DESCRIBE-first against the LIVE dev DB** — the install changelog is a stale snapshot (PA50). Map each table faithfully; `ddl-auto=validate` is the startup backstop.

**Files:**
- Create: `src/main/java/org/openboxes/inventory/entity/{Inventory,InventoryItem,InventoryLevel,ProductAvailability,Transaction,TransactionEntry,TransactionType,TransactionSource}.java`
- Create: `src/main/resources/db/changelog/db.changelog-master.xml` + 8 `changelog-shadow-create-*.xml`

- [ ] **Step 1: DESCRIBE every table first.** For each of the 8 tables run `SHOW COLUMNS FROM <table>;` (or `DESCRIBE <table>;`) against the live dev DB (Temurin-8 Grails dev DB per `docs/process/dev-env-setup.md`). Record the real column list, types, and nullability. **Do not infer columns from `changelog-create-tables.groovy`** — it omits later-migration columns (e.g. `inventory.warehouse_id`, `transaction_source.accurate`).
- [ ] **Step 2: Map the 8 entities** following the catalog `Product.java` conventions: `@Entity` + `@Table(name="…")`; `@Id @Column(columnDefinition="CHAR(38)") String id`; cross-context FKs as **flat id columns** (`@Column(name="…_id", columnDefinition="CHAR(38)") String …Id`, NOT `@ManyToOne` to non-owned entities — FD#6); booleans as `Boolean` (TINYINT — RC-1); timestamps as `Instant` (`datetime` columns); getters only (read-only). Per-entity specifics:
  - **Inventory** (`inventory`): `id`, `warehouseId` (`warehouse_id`), `dateCreated`, `lastUpdated`, `lastInventoryDate` (+ any DESCRIBE surfaces). Do not map `configuredProducts` (collection not inflated — FD#6).
  - **InventoryLevel** (`inventory_level`): `id`, `inventoryId` (`inventory_id`), `abcClass` (`abc_class`), + product/bin FKs and status columns per DESCRIBE.
  - **ProductAvailability** (`product_availability`): flat FKs `productId`/`locationId`/`binLocationId`/`inventoryItemId`; quantity columns; `quantityNotPicked` mapped as `@Formula("quantity_on_hand - quantity_allocated")` (NOT a column — there is no `quantity_not_picked` column; PA24). `id` is `assigned` (no generator needed for a read-only mapping).
  - **Transaction** (`transaction` — SQL reserved word; if `validate`/queries error, use `@Table(name="`transaction`")` with backticks): flat FKs (transaction_type_id, transaction_source_id, inventory_id, etc.) per DESCRIBE. Do not map `transactionEntries` collection.
  - **TransactionEntry** (`transaction_entry`): flat FKs (transaction_id, inventory_item_id, bin_location_id) + quantity.
  - **TransactionType** (`transaction_type`), **TransactionSource** (`transaction_source`): reference data; map columns per DESCRIBE. Note `transaction_source` has `accurate` (TINYINT) + `Instant` timestamps + `version false` (no version column).
  - **All entities:** leave any `version` column **unmapped** (read-only; `validate` tolerates extra table columns; matches catalog `Product`).
- [ ] **Step 3: Populate the master shell** (created empty in T2) — add 8 `<include>` lines (one per shadow file), matching the catalog master format.
- [ ] **Step 4: Create the 8 shadow changelogs**, each modeled on `changelog-shadow-create-product.xml`:
  ```xml
  <?xml version="1.1" encoding="UTF-8"?>
  <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                         https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd"
                     logicalFilePath="services/inventory-service/db/changelog/changelog-shadow-create-<table>.xml">
      <changeSet id="phase6-shadow-create-<table>" author="openboxes-inventory">
          <preConditions onFail="MARK_RAN" onFailMessage="<table> table not found — Grails Liquibase must run first">
              <tableExists tableName="<table>"/>
          </preConditions>
          <comment>Shadow for <table>. Grails Liquibase owns table creation; inventory-service uses ddl-auto=validate to prove entity-mapping correctness.</comment>
      </changeSet>
  </databaseChangeLog>
  ```
- [ ] **Step 5: Prove the mappings — smoke-start with `ddl-auto=validate` against the live dev DB.** The service must start cleanly (Liquibase shadows MARK_RAN; Hibernate validates all 8 entities). Fix any mapping that `validate` rejects (column name/type/nullability) until startup is clean. This is the core proof of FD#2.
- [ ] **Step 6: Commit** — `git add services/inventory-service/src/main/java/org/openboxes/inventory/entity services/inventory-service/src/main/resources/db/changelog` then `git commit -m "phase 6 task 3: 8 inventory JPA entities + 8 shadow changelogs (flat FK-only per FD#6; ProductAvailability @Formula; DESCRIBE-first; ddl-auto=validate proves all 8)"`.

### Task 4: RC-16 end-to-end (inventory-service service/controller/client + catalog-service new GET)

A two-service, read-only change. Build the new path; do not delete the Grails original yet (T8).

**Files:**
- Create (inventory-service): `dto/ProductClassificationDto.java`, `client/CatalogReadClient.java`, `service/ProductClassificationService.java`, `controller/ProductClassificationController.java`, `repository/InventoryRepository.java`, `repository/InventoryLevelRepository.java`
- Modify (catalog-service): `entity/Product.java`, `repository/ProductRepository.java`, `service/ProductService.java`; Create: `controller/ProductAbcClassController.java`

**Catalog-service side (the Product distinct-abcClass source):**
- [ ] **Step 1: Add `abcClass` to catalog `Product.java`** — `@Column(name = "abc_class") private String abcClass;` + `public String getAbcClass() { return abcClass; }`. (`ddl-auto=validate` accepts it — the column exists.)
- [ ] **Step 2: `ProductRepository`** — add:
  ```java
  @org.springframework.data.jpa.repository.Query(
      "select distinct p.abcClass from Product p where p.abcClass is not null and p.abcClass <> ''")
  java.util.List<String> findDistinctAbcClasses();
  ```
- [ ] **Step 3: `ProductService`** — add `public List<String> distinctAbcClasses() { return repo.findDistinctAbcClasses(); }`.
- [ ] **Step 4: New `ProductAbcClassController`** (separate from `ProductController` to avoid the `/api/product/{id}` shadow):
  ```java
  package org.openboxes.catalog.controller;

  import org.openboxes.catalog.service.ProductService;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RestController;
  import java.util.List;
  import java.util.Map;

  @RestController
  public class ProductAbcClassController {
      private final ProductService service;
      public ProductAbcClassController(ProductService service) { this.service = service; }

      @GetMapping("/api/products/abcClasses")
      public Map<String, Object> abcClasses() {
          List<String> data = service.distinctAbcClasses();
          return Map.of("data", data);
      }
  }
  ```
- [ ] **Step 5: catalog test** — add to `CatalogServiceIntegrationTest`: ensure `seed.sql` has ≥2 products with distinct `abc_class` (+ one null/empty), then `GET /api/products/abcClasses` returns `{data:[...]}` with the non-empty distinct set. Run `(cd services && ./gradlew :catalog-service:test)`.

**Inventory-service side (union + facility-scoping + guard):**
- [ ] **Step 6: `ProductClassificationDto`** — `public record ProductClassificationDto(String name) {}`.
- [ ] **Step 7: Repositories**
  ```java
  // InventoryRepository
  public interface InventoryRepository extends JpaRepository<Inventory, String> {
      java.util.Optional<Inventory> findByWarehouseId(String warehouseId);
  }
  // InventoryLevelRepository
  public interface InventoryLevelRepository extends JpaRepository<InventoryLevel, String> {
      @Query("select distinct il.abcClass from InventoryLevel il " +
             "where il.inventoryId = :inventoryId and il.abcClass is not null and il.abcClass <> ''")
      java.util.List<String> findDistinctAbcClassesByInventoryId(@Param("inventoryId") String inventoryId);
  }
  ```
- [ ] **Step 8: `CatalogReadClient`** (RestClient; forwards `obx_token`):
  ```java
  package org.openboxes.inventory.client;

  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.stereotype.Component;
  import org.springframework.web.client.RestClient;
  import java.util.List;

  @Component
  public class CatalogReadClient {
      private final RestClient http;
      public CatalogReadClient(@Value("${openboxes.services.catalog.base-url}") String baseUrl) {
          this.http = RestClient.builder().baseUrl(baseUrl).build();
      }
      public List<String> distinctAbcClasses(String obxToken) {
          AbcClassesResponse resp = http.get()
              .uri("/api/products/abcClasses")
              .header("Cookie", "obx_token=" + obxToken)
              .retrieve()
              .body(AbcClassesResponse.class);
          return (resp == null || resp.data() == null) ? List.of() : resp.data();
      }
      public record AbcClassesResponse(List<String> data) {}
  }
  ```
- [ ] **Step 9: `ProductClassificationService`** (union/sort/dedup + invalid-facility guard):
  ```java
  package org.openboxes.inventory.service;

  import org.openboxes.inventory.client.CatalogReadClient;
  import org.openboxes.inventory.dto.ProductClassificationDto;
  import org.openboxes.inventory.entity.Inventory;
  import org.openboxes.inventory.repository.InventoryLevelRepository;
  import org.openboxes.inventory.repository.InventoryRepository;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;
  import java.util.List;
  import java.util.TreeSet;

  @Service
  @Transactional(readOnly = true)
  public class ProductClassificationService {
      private final InventoryRepository inventoryRepo;
      private final InventoryLevelRepository levelRepo;
      private final CatalogReadClient catalogClient;

      public ProductClassificationService(InventoryRepository inventoryRepo,
                                          InventoryLevelRepository levelRepo,
                                          CatalogReadClient catalogClient) {
          this.inventoryRepo = inventoryRepo;
          this.levelRepo = levelRepo;
          this.catalogClient = catalogClient;
      }

      public List<ProductClassificationDto> list(String facilityId, String obxToken) {
          // facility -> inventory resolved locally (FD#5/V1). Invalid facility => no inventory => 500 (guard preserved).
          Inventory inventory = inventoryRepo.findByWarehouseId(facilityId)
              .orElseThrow(() -> new IllegalArgumentException("Invalid facilityId: " + facilityId));
          // TreeSet => dedup + alphabetical sort (matches Grails sort()).
          TreeSet<String> classes = new TreeSet<>();
          classes.addAll(catalogClient.distinctAbcClasses(obxToken));                          // global Product.abcClass
          classes.addAll(levelRepo.findDistinctAbcClassesByInventoryId(inventory.getId()));    // facility-scoped InventoryLevel
          return classes.stream().map(ProductClassificationDto::new).toList();
      }
  }
  ```
  Behavior note: the Grails guard tested `Location.read(facilityId)`; the local resolution tests "an Inventory exists for `warehouse_id`". Equivalent for every tested/realistic case (invalid → 500; a real facility always has an Inventory). The default Spring 500 on the unhandled `IllegalArgumentException` preserves the existing error contract — no exception handler is added (YAGNI).
- [ ] **Step 10: `ProductClassificationController`** (reads `obx_token` from the request, forwards it):
  ```java
  package org.openboxes.inventory.controller;

  import jakarta.servlet.http.Cookie;
  import jakarta.servlet.http.HttpServletRequest;
  import org.openboxes.inventory.dto.ProductClassificationDto;
  import org.openboxes.inventory.service.ProductClassificationService;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.PathVariable;
  import org.springframework.web.bind.annotation.RestController;
  import java.util.List;
  import java.util.Map;

  @RestController
  public class ProductClassificationController {
      private final ProductClassificationService service;
      public ProductClassificationController(ProductClassificationService service) { this.service = service; }

      @GetMapping("/api/facilities/{facilityId}/products/classifications")
      public Map<String, Object> list(@PathVariable String facilityId, HttpServletRequest request) {
          List<ProductClassificationDto> data = service.list(facilityId, readObxToken(request));
          return Map.of("data", data);
      }

      private String readObxToken(HttpServletRequest request) {
          if (request.getCookies() != null) {
              for (Cookie c : request.getCookies()) {
                  if ("obx_token".equals(c.getName())) return c.getValue();
              }
          }
          return null;
      }
  }
  ```
- [ ] **Step 11: Smoke build both services** — `(cd services && ./gradlew :catalog-service:bootJar :inventory-service:bootJar)`.
- [ ] **Step 12: Commit** — `git add services/catalog-service services/inventory-service` then `git commit -m "phase 6 task 4: RC-16 product-classification read on inventory-service (local InventoryLevel union + catalog-service distinct abcClass GET over RestClient forwarding obx_token); behavior-preserving"`.

### Task 5: nginx (regex) + both compose files + CI + healthcheck

Wire inventory-service into the stack as the 9th container and route RC-16. Compose + CI bundled in one commit (RC-2/Rule-1: CI builds the jar same-commit-as-or-before the compose change).

**Files:**
- Modify: `docker/docker-compose-base.yml`, `docker/docker-compose.yml`, `docker/nginx/conf.d/app.conf`, `.github/workflows/e2e-tests.yml`

- [ ] **Step 1: `docker/docker-compose-base.yml`** — add (after the catalog-service block, before nginx):
  ```yaml
  inventory-service:
    build:
      context: ../services/inventory-service
      dockerfile: Dockerfile
    container_name: openboxes-inventory-service
    expose:
      - "8086"
    environment:
      DATASOURCE_URL: ${DATASOURCE_URL:-jdbc:mariadb://db:3306/openboxes?serverTimezone=UTC&useSSL=false}
      DATASOURCE_USERNAME: ${DATASOURCE_USERNAME:-openboxes}
      DATASOURCE_PASSWORD: ${DATASOURCE_PASSWORD:-openboxes}
      OPENBOXES_JWT_SECRET: ${OPENBOXES_JWT_SECRET:-dev-secret-only-for-local-please-rotate-in-prod}
      CATALOG_SERVICE_URL: ${CATALOG_SERVICE_URL:-http://catalog-service:8085}
    healthcheck:
      test: "curl --fail --silent localhost:8086/actuator/health | grep '\"status\":\"UP\"' || exit 1"
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
  ```
- [ ] **Step 2: `docker/docker-compose.yml`** — add the `extends` + `depends_on` block (mirroring catalog-service), and add `inventory-service: { condition: service_healthy }` to the nginx `depends_on`:
  ```yaml
  inventory-service:
    extends:
      file: docker-compose-base.yml
      service: inventory-service
    depends_on:
      db:
        condition: service_healthy
      app:
        condition: service_healthy
  ```
- [ ] **Step 3: `docker/nginx/conf.d/app.conf`** — add a **regex** location (Rule-3: variable `facilityId` is mid-path; a prefix would wrongly capture `/api/facilities/$id/inventory-levels`). Place after the catalog blocks, before the `/api/` catch-all:
  ```nginx
  # Phase 6: inventory-service. RC-16 product-classification read (FD#5).
  # Regex (not prefix): facilityId is a mid-path variable, and a /api/facilities/ prefix
  # would also capture inventory-levels (which must stay on Grails) — Rule-3. nginx evaluates
  # regex locations before the /api/ prefix catch-all, so only this exact sub-path is intercepted.
  location ~ ^/api/facilities/[^/]+/products/classifications$ {
      proxy_pass http://inventory-service:8086;
      include /etc/nginx/conf.d/proxy_params;
  }
  ```
- [ ] **Step 4: `.github/workflows/e2e-tests.yml`** — (a) append `:inventory-service:bootJar` to the JDK-21 services build step; (b) add `&& docker exec openboxes-inventory-service curl -sf localhost:8086/actuator/health \` to the health-probe loop; (c) add an inventory-service line to the failure diagnostic dump.
- [ ] **Step 5: Verify config** — `(cd docker && docker compose config >/dev/null)` parses; if a stack is running, `docker compose exec nginx nginx -t`.
- [ ] **Step 6: Commit** — `git add docker/docker-compose-base.yml docker/docker-compose.yml docker/nginx/conf.d/app.conf .github/workflows/e2e-tests.yml` then `git commit -m "phase 6 task 5: wire inventory-service (9th container, 8086) into both compose files + CI jar/health-probe + nginx regex route for RC-16 (Rule-3)"`.

### Task 6: TestContainers `InventoryServiceIntegrationTest`

Prove the entity round-trip + RC-16 query logic + the ported behavior contract. Uses `ddl-auto=create` against an empty TestContainers MariaDB (per the catalog precedent — the live-schema `validate` proof is T3 smoke + T9 boot). The catalog HTTP call is stubbed (`@MockBean CatalogReadClient`); the real cross-service union is proven in T7.

**Files:**
- Create: `services/inventory-service/src/test/java/org/openboxes/inventory/InventoryServiceIntegrationTest.java`, `services/inventory-service/src/test/resources/seed.sql`

- [ ] **Step 1: `seed.sql`** — seed for two facilities: facility `F1` with an `inventory` row (`warehouse_id='F1'`) and `inventory_level` rows for `F1`'s inventory with `abc_class` in {`A`,`C`,`` (empty)}; facility `F2` with its own inventory + an `inventory_level` `abc_class='D'` that MUST be excluded from `F1`'s result. (Mirrors the Grails `ProductClassificationApiCRUDSpec` fixture.)
- [ ] **Step 2: `InventoryServiceIntegrationTest`** — model on `CatalogServiceIntegrationTest` (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers`; `MariaDBContainer("mariadb:10")`; `@DynamicPropertySource` sets datasource + `spring.liquibase.enabled=false` + `spring.jpa.hibernate.ddl-auto=create` + `spring.sql.init` seed + `openboxes.jwt.secret`; JWT cookie helper). Add `@MockBean CatalogReadClient` returning a fixed set (e.g. `["A","B"]`). Tests (porting the 2 Grails specs' contract):
  - entity-mapping round-trip: each of the 8 entities persists/loads (basic `repository.save/find` or seed-and-read) so the `create` schema exercises every mapping;
  - `given a valid facility, list returns the unique union` — `GET /api/facilities/F1/products/classifications` (with auth cookie) → status 200, `$.data[*].name` = sorted unique union of mock `{A,B}` ∪ F1's `{A,C}` = `[A,B,C]` (D excluded; empty excluded);
  - `given a valid facility, list excludes the empty string` — assert `""` not present;
  - `given an invalid facility, list errors` — `GET /api/facilities/-1/products/classifications` → status 500.
- [ ] **Step 3: Run** — `(cd services && ./gradlew :inventory-service:test)` green.
- [ ] **Step 4: Commit** — `git add services/inventory-service/src/test` then `git commit -m "phase 6 task 6: InventoryServiceIntegrationTest (TestContainers; 8-entity round-trip + RC-16 contract ported from Grails specs: union/dedup/sort/empty-filter/invalid-facility-500; catalog call mocked)"`.

### Task 7: Real-payload ground-truth + seeded e2e through nginx + Playwright

The full cross-service round-trip (inventory-service → catalog-service) through nginx, with a real JWT. This is the live-endpoint regression guard (RC-43/45). Requires T5 (compose + nginx).

**Files:**
- Create: `e2e/tests/inventory-product-classifications.spec.ts` (Playwright)
- (No new service code; may add a seed step / SQL for the e2e DB.)

- [ ] **Step 1: Real-payload ground-truth (RC-43).** Before cutover, capture the *current* Grails response shape for a real facility: `curl` (with a real `obx_token`) `http://localhost/api/facilities/<realFacilityId>/products/classifications` → record the exact JSON `{"data":[{"name":"…"}]}`. This is the contract the migrated endpoint must match byte-for-shape.
- [ ] **Step 2: Bring up the stack with both services** — `(cd docker && docker compose up --build -d)` (rebuilds catalog-service with the new endpoint + starts inventory-service). Wait for health.
- [ ] **Step 3: Seed a deterministic fixture (RC-45)** — ensure a known facility has `abc_class` values on BOTH `product` (global) and `inventory_level` (facility-scoped), plus a second facility's `inventory_level` row that must be excluded. (Empty-DB self-skip caveat: do not accept a skip-heavy green — the seeded round-trip is mandatory.)
- [ ] **Step 4: Read-through assertion through nginx** — with a real JWT cookie, `GET http://localhost/api/facilities/<facilityId>/products/classifications` returns 200 and the union (global Product ∪ facility InventoryLevel), sorted, deduped, empty-string excluded, second-facility class excluded; the payload shape matches Step 1.
- [ ] **Step 5: Playwright spec** — `inventory-product-classifications.spec.ts`: log in, hit the endpoint (or exercise the CycleCount filter UI that consumes `abcClasses`), assert the classification options render. Run `(cd e2e && npm test)` (or the single spec).
- [ ] **Step 6: Commit** — `git add e2e/tests/inventory-product-classifications.spec.ts` (+ any seed file) then `git commit -m "phase 6 task 7: RC-16 real-payload ground-truth + seeded read-through e2e through nginx (global∪facility union, facility-scoping, empty-filter) + Playwright spec"`.

### Task 8: Delete the Grails ProductClassification surface (strangler bite)

Now that the inventory-service path is built (T4), routed (T5), unit-contract-green (T6), and e2e-verified (T7), remove the Grails original. nginx already reroutes the path, so this removes dead code.

**Files:** the 8 Grails delete-targets + the UrlMappings entry (see File Structure → Delete).

- [ ] **Step 1: Delete the 8 Grails files** — controller, service, dto, unit spec, 2 integration specs, `ProductClassificationApiWrapper.groovy`, `ProductClassificationApi.groovy`.
- [ ] **Step 2: Remove the UrlMapping** — delete the `/api/facilities/$facilityId/products/classifications { controller="productClassificationApi"; action=[GET:"list"] }` block from `grails-app/controllers/org/pih/warehouse/UrlMappings.groovy` (~line 139). Leave the adjacent `inventory-levels` mapping intact.
- [ ] **Step 3: Grep-verify no dangling refs** — `grep -rnE "ProductClassification(Service|Dto|ApiController|ApiWrapper|Api)\b" grails-app src --include=*.groovy --include=*.java` returns nothing (the React `ProductClassificationApi.js` is unrelated and stays). Confirm `grep -rn "products/classifications" grails-app` returns nothing.
- [ ] **Step 4: Smoke** — Grails compiles/starts without the deleted classes (`JAVA_HOME=<temurin-8> ./gradlew compileGroovy` or the project's standard Grails check per `docs/process/dev-env-setup.md`).
- [ ] **Step 5: Commit** — stage the 8 deletions with `git rm` and the modified mapping with `git add` (no `-A`):
  ```bash
  git rm grails-app/controllers/org/pih/warehouse/api/ProductClassificationApiController.groovy \
         grails-app/services/org/pih/warehouse/product/ProductClassificationService.groovy \
         src/main/groovy/org/pih/warehouse/product/ProductClassificationDto.groovy \
         src/test/groovy/org/pih/warehouse/product/ProductClassificationServiceSpec.groovy \
         src/integration-test/groovy/org/pih/warehouse/api/spec/product/ProductClassificationApiCRUDSpec.groovy \
         src/integration-test/groovy/org/pih/warehouse/api/spec/product/ProductClassificationApiListFiltersOutEmptySpec.groovy \
         src/integration-test/groovy/org/pih/warehouse/api/client/product/ProductClassificationApiWrapper.groovy \
         src/integration-test/groovy/org/pih/warehouse/api/client/product/ProductClassificationApi.groovy
  git add grails-app/controllers/org/pih/warehouse/UrlMappings.groovy
  git commit -m "phase 6 task 8: delete Grails ProductClassification* (controller/service/dto/unit spec/2 integration specs/2 test clients) + UrlMappings entry — RC-16 strangler bite (now served by inventory-service)"
  ```

### Task 9: Done-gate

**Files:** none (verification only; commit only if a fix is needed).

- [ ] **Step 1:** `(cd docker && docker compose up --build -d)`; wait for all 9 services + Grails healthy.
- [ ] **Step 2:** `docker compose exec nginx nginx -t` clean; `docker compose exec nginx nginx -s reload` no error.
- [ ] **Step 3:** Real-JWT curl-through-nginx 2xx on `GET /api/facilities/<facilityId>/products/classifications`; confirm the union payload.
- [ ] **Step 4:** inventory-service boots against the shared MariaDB with `ddl-auto=validate` clean (the live-schema proof for all 8 mappings) — `docker logs openboxes-inventory-service` shows no validation error.
- [ ] **Step 5:** Playwright re-run green (`cd e2e && npm test`).
- [ ] **Step 6:** Builds: Grails (`JAVA_HOME=<temurin-8> ./gradlew prepareDocker -Dgrails.env=prod`) + services (`cd services && ./gradlew :inventory-service:bootJar`) both succeed (RC-55 split).
- [ ] **Step 7:** 9-route nginx smoke — each service's health reachable; the RC-16 route resolves to inventory-service while `/api/facilities/$id/inventory-levels` still resolves to Grails.

### Task 10: Retrospective + tag

**Files:** Create `docs/retrospectives/2026-06-03-phase-6-inventory-retrospective.md`.

- [ ] **Step 1: Retro with A–F triage** (carry-forward lessons; what the DESCRIBE-first/schema-drift discipline cost; RC-16 cutover notes).
- [ ] **Step 2: Phase 6.5 forward pointer** — deferred entities (CycleCount family, snapshot/count/audit, LocalTransfer/OutboundStockMovement*/Requirement); restructure (a) ProductMergeService; restructure (b) InventoryService bulk-import; ProductAvailability refresh move; write clusters; RC-13 remainder.
- [ ] **Step 3: Commit the retro**, then **tag**: `git tag phase-6-inventory`. Commit message `phase 6 task 10: retrospective (A–F triage) + Phase 6.5 forward pointer; tag phase-6-inventory`.

---

## Known issues inherited from spec

(From spec §6 — accepted as out of scope during brainstorming; these exist in the implementation by design. A new spec → new plan cycle is required to change any of them.)

- **All inventory writes stay Grails** — record stock, adjustments, transactions, cycle count, transfers, bulk import, product merge, availability refresh → Phase 6.5+.
- **Both parent-design restructures deferred to 6.5**: (a) ProductMergeService → inventory-service + catalog thin delegate; (b) InventoryService bulk-import → inventory-service with per-row sync HTTP to catalog. (Restructure (b) is why `InventoryApiController.importCsv` stays alive in core.)
- **ProductAvailability refresh logic stays Grails** (FD#4) — moves in 6.5 with restructure (a) (shared service).
- **CycleCount family + snapshot/count/audit + LocalTransfer/OutboundStockMovement*/Requirement** → 6.5/6.x (FD#2).
- **RC-13 remainder** (CategoryApiController final deletion / `runtime.groovy:757` fillRate categoryApi dependency) → 6.5 (FD#11) — separate from RC-16.
- **Cross-context inventory readers stay direct-JDBC** (FD#10): `ProductApiController`, `StockMovementService`, fillRate/`IndicatorDataService`. Switch to HTTP at their owning service's extraction (Phase 7/8) or never.
- **InventoryItem / ProductAvailability React reads stay on Grails `ProductApiController`** — cross-context (Product + forecasting + inventory); cannot move to a read-only inventory-service. Re-open if a dedicated inventory read API is ever demanded.
- **Flat FK-only DTO degradation** (FD#6) — no cross-service name resolution (RC-48).
- **`abcClass` stays a Product column** (not its own entity) — the entity refactor stays deferred.
- **Parent-design entity-list correction** (V3): StockMovement/StockMovementItem/StockTransfer/StockTransferItem/Replenishment have no domain class/table; parent §4.3/§6's "Liquibase ownership transfers" for them is vacuous. Their services defer to Phase 7/8 unchanged.
- **`StockMovementService` / `StockTransferService` / `ReplenishmentService` stay Grails** — parent-deferred (Phase 7/8); their cross-context atomic writes need saga consumers that don't exist yet.
