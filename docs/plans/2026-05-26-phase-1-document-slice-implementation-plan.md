# Phase 1 — Document Slice Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Source spec:** `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` (commit SHA: `34283a7`)
**Phase 0 retrospective referenced:** `docs/retrospectives/2026-05-26-phase-0-foundations-retrospective.md` (commit `a9eb5df`)

**Goal:** Extract Document + DocumentType ownership from Grails into a new `services/document-service/` Spring Boot 3.x / Java 21 module. Migrate all ~20 Grails callers (controllers, services, GSPs, documentService entity-facing methods) to HTTP via a shared `DocumentClient.groovy` helper. Delete Grails `Document.groovy`, `DocumentType.groovy`, `DocumentController.groovy`, `grails-app/views/document/`, and related UrlMappings. `DocumentService.groovy` STAYS in Grails as a file-utility service (Excel/PDF/image generation methods have no Document-entity dependency). Tag `phase-1-document` on `main`.

**Architecture:** New `services/` Gradle 8 build tree with `document-service` as its first sub-module. Shared MariaDB; document-service owns the `document` + `document_type` Liquibase changesets via per-service changelog (A17). Validates the existing `obx_token` cookie via shared HMAC secret (`OPENBOXES_JWT_SECRET`). nginx gains `location /api/documents/` block routed to `http://document-service:8081/`, inserted ABOVE the existing `/api/` block so the more-specific match wins. Grails callers use `DocumentClient.groovy` that forwards the caller's `obx_token` per spec §4.4. `docker-compose-base.yml` gains a permanent `build:` directive for the `app` service (Phase 0 retrospective follow-up — removes the per-phase retag-as-`:latest` workaround).

**Tech stack:** Spring Boot 3.3.5, Java 21 LTS (Temurin), Gradle 8.5+, Spring Data JPA + Hibernate 6 (bundled with Spring Boot 3.3), jjwt 0.12.5 (HS256, matches Phase 0 Grails-side issuance secret), springdoc-openapi-starter-webmvc-ui 2.5.0, mariadb-java-client 3.4.x, Liquibase 4.x (bundled with Spring Boot 3.3). Grails container stays on Java 8 / Gradle 4.10.3 per spec §5.

**Pace estimate:** 2–3 weeks for one developer (revised up from spec §6's 1–2 weeks based on the caller count surfaced during plan-level verification — see §"Verified plan-level assumptions" below).

---

## File Structure

**Create (new):**
- `services/build.gradle`, `services/settings.gradle`, `services/gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}`, `services/gradlew`, `services/gradlew.bat` — Gradle 8.5 wrapper, root build for the `services/` tree
- `services/document-service/build.gradle` — module Gradle build (Spring Boot 3.3.5, Java 21, dependencies)
- `services/document-service/Dockerfile` — Java 21 base image, copies the bootJar
- `services/document-service/src/main/resources/application.yml` — port 8081, JPA datasource pointing at shared MariaDB, Liquibase changelog path
- `services/document-service/src/main/java/org/openboxes/document/DocumentServiceApplication.java` — Spring Boot entry point
- `services/document-service/src/main/java/org/openboxes/document/entity/Document.java`, `DocumentType.java`, `DocumentCode.java` — JPA entities
- `services/document-service/src/main/java/org/openboxes/document/repository/DocumentRepository.java`, `DocumentTypeRepository.java` — Spring Data JPA repositories
- `services/document-service/src/main/java/org/openboxes/document/service/DocumentService.java` — entity-facing business logic
- `services/document-service/src/main/java/org/openboxes/document/controller/DocumentController.java` — REST controller, springdoc-annotated
- `services/document-service/src/main/java/org/openboxes/document/security/JwtCookieAuthFilter.java`, `SecurityConfig.java` — Spring Security 6 cookie-based JWT auth
- `services/document-service/src/main/resources/db/changelog/document-changelog-master.xml` (or yaml) — service-scoped Liquibase master, includes the relocated changesets
- `grails-app/services/org/pih/warehouse/core/DocumentClient.groovy` — shared Grails helper that HTTP-calls document-service and forwards the caller's `obx_token` cookie per spec §4.4
- `e2e/tests/document-upload.spec.ts`, `document-download.spec.ts`, `document-list-by-code.spec.ts`, `document-delete.spec.ts` — Playwright specs for the new document-service flows
- `e2e/tests/document-callers-regression.spec.ts` — single spec covering the migrated caller flows (Data Export download, template render via Invoice, Stock Movement upload, Product attachment, Shipment attachment, Document Upload via Shipping)

**Modify:**
- `docker/docker-compose-base.yml` — add `document-service` service entry; add permanent `build:` directive to `app` service (Phase 0 retrospective follow-up)
- `docker/nginx/conf.d/app.conf` — add `location /api/documents/` block ABOVE existing `location /api/`
- `.github/workflows/e2e-tests.yml` — add `services/document-service` build step before docker-compose up (built into image via compose `build:` directive — actually no separate step needed if compose handles it; verify in Task 2)
- `grails-app/controllers/org/pih/warehouse/data/DataExportController.groovy` — lines 23, 28 — switch from `Document.findAllByDocumentCode(...)` / `Document.get(params.id)` to `documentClient.findByCode(...)` / `documentClient.fetchById(...)`
- `grails-app/services/org/pih/warehouse/core/TemplateService.groovy` — lines 20-21 — `renderTemplate(Document, Map)` callers must now pre-fetch via `documentClient`; the method signature stays
- `grails-app/services/org/pih/warehouse/inventory/StockMovementService.groovy` — lines 3301, 3455-3458 — `Document.findAllByDocumentCode` → `documentClient.findByCode`; `new Document(...).save()` → `documentClient.create(...)`
- `grails-app/controllers/org/pih/warehouse/invoice/InvoiceController.groovy` — lines 142, 161 — `Document.get(params.id)` → `documentClient.fetchById(params.id)`
- `grails-app/controllers/org/pih/warehouse/product/ProductController.groovy` — lines 462, 498, 548, 607, 617, 628, 1118, 1122 — all `Document.get / new Document` migrate to `documentClient.*`
- `grails-app/controllers/org/pih/warehouse/inventory/StockMovementController.groovy` — lines 502, 505, 507 — `getNonTemplateDocumentTypes` indirection + `Document.get` + `new Document`
- `grails-app/controllers/org/pih/warehouse/order/OrderController.groovy` — lines 526, 546, 943, 961 — `Document.get` / `Document.findByName(...)`
- `grails-app/controllers/org/pih/warehouse/shipping/ShipmentController.groovy` — lines 816, 820, 835, 926 — `Document.get` / `new Document`
- `grails-app/controllers/org/pih/warehouse/shipping/ShipmentWorkflowController.groovy` — line 113 — `Document.findAllByDocumentTypeInList`
- `grails-app/services/org/pih/warehouse/data/MigrationService.groovy` — line 1192 — `new Document()`
- `grails-app/controllers/org/pih/warehouse/shipping/DocumentUploadController.groovy` — line 20 — the 8th caller per spec A21
- `grails-app/services/org/pih/warehouse/core/DocumentService.groovy` — KEEPS its file/Excel/PDF/image utility methods; the Document-entity-facing methods (currently `getNonTemplateDocumentTypes` plus any additional entity-rooted methods surfaced during Task 1 audit) become thin shims that delegate to `documentClient`. This service does NOT get deleted in Task 10. **`getAllDocumentsBySupplierOrganization` STAYS Grails-side unchanged** — its projection root is `Order` (filtered by `originParty` + `orderType = PURCHASE_ORDER`), traversing `Order.documents` only for left-join enrichment; semantically it is an Order-rooted report, not a Document query, and its future home is order-service when Order is extracted (post-Phase 1).
- `grails-app/views/order/_summary.gsp:242`, `grails-app/views/order/_orderDocuments.gsp:2`, `grails-app/views/inventoryItem/_actionsCurrentStock.gsp:45` — remove inline `Document.findAllByDocumentCode(...)` calls; use a model variable that the wrapping controller action (`OrderController.show`/`edit`/whichever, `InventoryItemController.showStockCard`/whichever) pre-fetches via `documentClient` and passes through to the `<g:render template="..."/>` include
- `grails-app/controllers/org/pih/warehouse/UrlMappings.groovy` — remove `/document/*` mappings (if any exist; Task 1 audit confirms)

**Delete (Task 10):**
- `grails-app/domain/org/pih/warehouse/core/Document.groovy`
- `grails-app/domain/org/pih/warehouse/core/DocumentType.groovy`
- `grails-app/domain/org/pih/warehouse/core/DocumentCode.groovy` (if it's a separate file; if it's nested in Document.groovy, it goes with it)
- `grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy`
- `grails-app/views/document/` (entire directory)

**Move (Task 6):**
- The historical Document/DocumentType Liquibase changesets from `grails-app/migrations/0.X.x/changelog-*-document*.xml` (and the related shipment-document/invoice-document join-table changesets that affect the `document` schema) — relocate to `services/document-service/src/main/resources/db/changelog/`. Filename-namespacing per A17 ensures the shared `DATABASECHANGELOG` table doesn't double-run them. Task 1 audit enumerates the exact set.

## Inherited from spec

The following assumptions were verified by `thorough-brainstorming` at spec-write time (per spec §10) and are NOT re-verified here. Trusted as ground truth:

- **A4** Java runtime: Grails on Java 8; Spring Boot services on Java 21. jjwt 0.11.x on Grails, 0.12+ on Spring Boot.
- **A7** AuthService entry points: `setCurrentUser(User)`, `setCurrentLocation(Location)` via ThreadLocal — Grails-side; Spring Boot side will use Spring Security's `SecurityContextHolder`.
- **A8** No conflicting JWT infra in build.gradle / grails-app — Phase 0 added jjwt 0.11.5 to Grails; Spring Boot side adds jjwt 0.12.5.
- **A9** Two-Gradle-wrapper layout: Grails keeps 4.10.3; new `services/` uses Gradle 8.x. Confirmed.
- **A10** Document is a structurally real bounded context (entity + service + controller + views all present).
- **A11** Document referenced by callers via internal fields (`fileContents`, `name`, `contentType`, `filename`) — resolved via Step 8b's caller-migration pattern.
- **A15** Shared MariaDB works with Hibernate 5 (Grails) + Hibernate 6 (document-service) — explicit `@Column`/`@Table` annotations resolve naming-strategy differences.
- **A17** Liquibase per-service changelog supported via `DATABASECHANGELOG.filename` SUBSTRING tracking — verified in `LiquibaseUtil.groovy`.
- **A21** Cross-context atomic writes audit (jobs + GORM events clean; controllers' cross-context writes absorbed by §11 policy-based coverage + §8 Step 1 per-phase audit). Plan applies the §4.3 coverage policy for any Phase 1 findings.
- **§4.4** Auth during coexistence: Grails forwards `obx_token` on outbound calls to Spring Boot services; document-service validates via shared HMAC secret.
- **§5** Tech choices (Spring Boot 3.x, Java 21, Gradle 8.x for services/, springdoc-openapi, per-service Liquibase).

## Verified plan-level assumptions

Newly introduced by this plan (paths, signatures, commands, ordering, consumer impact) and verified at plan-write time against the codebase + live stack (per Phase 0 retrospective live-smoke-probe meta-lesson). Spec literal corrections noted explicitly.

| # | Category | Assumption | Evidence |
|---|----------|------------|----------|
| P1 | path | `services/` does not yet exist (Task 2 creates it) | `ls services` → `cannot access: No such file or directory` |
| P2 | path | `grails-app/domain/org/pih/warehouse/core/Document.groovy` exists | `find` returned the exact path |
| P3 | path | `grails-app/domain/org/pih/warehouse/core/DocumentType.groovy` exists | `find` returned the exact path |
| P4 | path | `grails-app/services/org/pih/warehouse/core/DocumentService.groovy` exists | `find` returned the exact path |
| P5 | path | `grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy` exists | `find` returned the exact path |
| P6 | path | `grails-app/views/document/` exists | `ls -d` returned the directory |
| P7 | path | `grails-app/controllers/org/pih/warehouse/data/DataExportController.groovy` exists (note: `data/` not `dataexport/`) | `find` returned the exact path |
| P8 | path | `grails-app/services/org/pih/warehouse/core/TemplateService.groovy` exists | `find` returned the exact path |
| P9 | path | `grails-app/services/org/pih/warehouse/inventory/StockMovementService.groovy` exists | `find` returned the exact path |
| P10 | path | `grails-app/controllers/org/pih/warehouse/invoice/InvoiceController.groovy` exists | `find` returned the exact path |
| P11 | path | `grails-app/controllers/org/pih/warehouse/product/ProductController.groovy` exists | `find` returned the exact path |
| P12 | path | **SPEC CORRECTION (A21):** `DocumentUploadController.groovy` is at `grails-app/controllers/org/pih/warehouse/shipping/`, NOT `core/` as spec A21 states | `find grails-app/controllers -iname "DocumentUploadController.groovy"` returned `…/shipping/DocumentUploadController.groovy` |
| P13 | path | `docker/nginx/conf.d/app.conf`, `docker/docker-compose-base.yml`, `docker/Dockerfile` exist (Phase 0 verified) | `ls` confirmed; same paths Phase 0 plan referenced |
| P14 | path | `e2e/` + `playwright.config.ts` + `e2e/tests/` exist (Phase 0 left in place) | `ls e2e/` confirmed 4 existing specs |
| P15 | path | Liquibase changesets for Document live under `grails-app/migrations/0.X.x/changelog-*-document-*.xml` — multiple files spanning 0.7.x and 0.8.x | `find grails-app/migrations -iname "*document*"` returned files including create-table-invoice-document, alter-table-document-change-file-uri, add-requisition-template-document-type |
| P16 | sig | `Document.groovy` field surface confirmed: `String id` (UUID-generated), `name`, `filename`, `extension`, `contentType`, `byte[] fileContents`, `dateCreated`, `lastUpdated`, `fileUri`, `documentNumber`, `DocumentType documentType` | `Read Document.groovy` lines 1-60 |
| P17 | sig | `DocumentType.groovy` field surface confirmed: `String id` (UUID-generated), `name`, `description`, `Integer sortOrder`, `dateCreated`, `lastUpdated`, `DocumentCode documentCode` | `Read DocumentType.groovy` lines 1-40 |
| P18 | sig | `document_type.id` column has MIXED types in seed data: integers `1,2,3,...` AND a UUID string `66762f6c61e34cfd9297ecb0fcee2df2`. JPA `@Id` MUST be `String` (not `Long`) to accommodate both | `mysql -e "SELECT id FROM document_type LIMIT 10"` returned both forms |
| P19 | sig | **SPEC CORRECTION (§9):** `DataExportController` has actions named `index()` and `render()`, NOT `list/download` as spec §9 implies. Spec lines 23 + 28 are inside these actions | `grep "def [a-z]+" DataExportController.groovy` returned `def index()`, `def render()` |
| P20 | sig | `DocumentService.groovy` is ~1200 lines and contains BOTH Document-entity methods AND unrelated file/Excel/PDF/image utility methods (`generateExcel`, `findFile`, `scaleImage`, `generatePackingList`, `convertToPdf`, `generateInventoryTemplate`, `generateChecklistAsDocx`, `generateStocklistCsv`, `generateCertificateOfDonation`, `generatePartialPackingList`) | `grep "^\s*(def\|String\|List\|void) [a-zA-Z]" DocumentService.groovy` enumerated method surface |
| P21 | sig | `documentService.*` has ~25+ caller sites across 20+ controllers/services. The Document-entity-facing callers (currently `getNonTemplateDocumentTypes` from 5 controllers; further entity-facing methods TBD via Task 1 audit) need migration to use `documentClient`; the 20+ file-utility callers stay calling Grails DocumentService unchanged. `getAllDocumentsBySupplierOrganization` is excluded — it's an Order-rooted query, see File Structure note above | `grep -rn "documentService\." grails-app/` returned the full caller list |
| P22 | sig | `apiClient` in `src/js/utils/apiClient.js` forwards cookies automatically (Phase 0 retrospective confirmed) | Phase 0 retrospective §"Code-level" gotchas |
| P23 | sig | `OPENBOXES_JWT_SECRET` env var is wired in `docker-compose-base.yml` `app` service (Phase 0 Task 2 added it); plan adds same env var to `document-service` entry so both sides validate with shared secret | Phase 0 commit `79ca66e` diff |
| P24 | sig | Grails 3.3.16 supports a Groovy service class using `groovy.json.JsonSlurper` + `java.net.HttpURLConnection` for outbound HTTP. Plan uses these for `DocumentClient.groovy` (no new dependency) | Groovy stdlib; no spec issue |
| P25 | cmd | `./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties` builds the Grails WAR (Phase 0 retrospective confirmed) | Phase 0 retrospective §"Build & deploy" |
| P26 | cmd | `cd services && ./gradlew :document-service:bootJar` will build the Spring Boot fat jar once `services/` is bootstrapped in Task 2 | Standard Spring Boot Gradle plugin behavior |
| P27 | cmd | docker-compose v1 (1.29.2) supports the `build:` directive syntax `build: { context: ../build/docker, dockerfile: Dockerfile }` and supports multi-service stacks with Java 8 + Java 21 containers in parallel | docker-compose v1 spec; `docker-compose -v` confirms 1.29.2 |
| P28 | cmd | Playwright tests run via `cd e2e && E2E_LOCATION_ID=1 npm test` (Phase 0 verified) | Phase 0 plan Task 5 Step 1 |
| P29 | cmd | nginx 1.13 (per `docker-compose-base.yml`) supports `location` block with `proxy_pass` upstream `http://document-service:8081/` | nginx 1.13 stable feature |
| P30 | cmd | Spring Boot 3.3.5 is stable as of plan-write date 2026-05-26 with Java 21 support; jjwt 0.12.5 stable for HMAC-HS256; springdoc-openapi 2.5.0 compatible with Spring Boot 3.3.x; mariadb-java-client 3.4.x compatible with MariaDB 10; Gradle 8.5 supports Java 21 toolchain | Maven Central + Spring Initializr metadata as of plan-write |
| P31 | cmd | Commit message convention: `phase N: <description>` lowercase | `git log --oneline -10` shows Phase 0's pattern: `phase 0: complete done-gate ...`, `phase 0: add Playwright E2E harness ...` |
| P32 | order | Task 1 audit (pure investigation) has no implementation dependencies — can run first | self-evident |
| P33 | order | Task 2 (module skeleton) imports nothing from later tasks — empty Spring Boot app | self-evident |
| P34 | order | Task 3 (JPA entities) maps to EXISTING schema (Grails Liquibase created tables historically); Task 6 only RELOCATES ownership; so Task 3 before Task 6 is correct per template | Schema already exists in MariaDB; Task 6 only changes which service's Liquibase scope owns it |
| P35 | order | Tasks 8a → 8b → 9 — 8a is a no-op (no React Document API exists, see P36); 8b's code changes are dormant until 9 wires nginx routes; 9 activates 8b. Sequential ok per template | self-evident |
| P36 | path | **No React Document API code exists** — `grep -rn "/document\|/api/documents\|DocumentApi" src/js` returned ZERO results. Task 8a is a no-op for Phase 1, retained as a template-alignment placeholder | `grep` output empty; `ls src/js/api/services/` shows no DocumentApi.js |
| P37 | order | Task 10 deletion (Document.groovy etc.) MUST be after Task 8b (all callers migrated) AND Task 9 (nginx serving) | self-evident |
| P38 | impact | After Task 8b + 10, all 11+ direct `Document.*` static caller files migrate or their containing controllers are deleted. Enumerated: DataExport, Template (via Invoice flow), StockMovement (service + controller), Invoice, Product, Order, Shipment, ShipmentWorkflow, DocumentUpload (shipping), Migration (data service) | `grep -rn "Document\.\(get\|findBy\|findAll\|list\|count\|create\)" grails-app/` enumerated all sites |
| P39 | impact | Three GSPs call `Document.findAllByDocumentCode(...)` directly: `views/inventoryItem/_actionsCurrentStock.gsp:45`, `views/order/_summary.gsp:242`, `views/order/_orderDocuments.gsp:2`. Each is a `_*.gsp` partial included via `<g:render template="..."/>` from a wrapping GSP. Task 8b traces each include chain back to the wrapping controller action, moves the lookup there, and passes the result via model into the partial | `grep -rn` enumerated the 3 GSP sites; manual trace required per partial |
| P40 | impact | No domain class declares `hasMany Document` or `belongsTo Document` — deleting Document.groovy creates NO schema-cascade issues in the Grails domain layer | `grep -rnE "hasMany.*Document\b\|belongsTo.*Document\b"` returned empty |
| P41 | impact | Three domain classes (`Invoice.groovy`, `ShipmentWorkflow.groovy`, `Product.groovy`) `import org.pih.warehouse.core.Document` for type references (method parameters, join-table sides). After Task 10 deletes Document.groovy these imports must go too — replacing with HTTP-fetched `Map<String,Object>` or `String documentId` typed parameters | `grep -rn "import org.pih.warehouse.core.Document" grails-app/domain/` returned 3 files |
| P42 | impact | nginx `location /api/documents` (no trailing slash; covers both bare `POST /api/documents` and `/api/documents/{id}` suffix paths) MUST be inserted BEFORE existing `location /api/` block. Current `app.conf` ordering: `/api/`, `/openboxes/`, `/`. New ordering after Task 9: `/api/documents`, `/api/`, `/openboxes/`, `/` | `cat docker/nginx/conf.d/app.conf` confirmed current 3-block ordering |
| P43 | impact | The Phase 0 `:latest` image-retag workaround becomes obsolete once compose has the `build:` directive. Plan Task 2 updates compose; Phase 0 plan Task 5 Step 1 remains accurate as a fallback if user reverts the `build:` change but is otherwise superseded | self-evident |
| P44 | impact | `docker-compose up` semantics change after Task 2: it now rebuilds the local image instead of pulling `:latest`. CI workflow (`.github/workflows/e2e-tests.yml`) also benefits — the explicit `docker build` step becomes redundant under `docker-compose up --build` | docker-compose v1 docs |
| P45 | impact | seed `document` table is EMPTY (0 rows); `document_type` has 10 rows but only 3 distinct non-NULL `document_code` values (`INVOICE_TEMPLATE` confirmed; others surface in Task 1 audit). E2E tests must seed via the upload endpoint or via direct DB insert before download/list assertions | `mysql -e "SELECT COUNT(*) FROM document"` returned 0 |
| P46 | impact | `/openboxes/document/list` returns HTTP 200 currently; admin@MainWarehouse can reach existing Grails Document UI. The post-extraction GSP smoke check (Task 13 done-gate) must continue to return 200 — since `views/document/` is deleted, this means the URL itself stops working; that's fine because no caller traffic should land on it after Task 8b | live probe: `curl /openboxes/document/list` → 200 |

## §8 per-slice template mapping

Each Task is 1:1 with one spec §8 template step (per user's chosen mapping strategy). Step 12 (Soak) is folded into Task 13 as a verification activity, not a separate task.

| Spec §8 Step | Plan Task |
|---|---|
| 1. Identify scope + cross-context audit | Task 1 |
| 2. Create Spring Boot module | Task 2 |
| 3. Port domain to JPA entities | Task 3 |
| 4. Port services | Task 4 |
| 5. Port controllers | Task 5 |
| 6. Move table ownership (Liquibase) | Task 6 |
| 7. Wire JWT validation (saga deferred to Phase 7) | Task 7 |
| 8a. Update React frontend | Task 8a |
| 8b. Handle external Grails callers | Task 8b |
| 9. Update nginx | Task 9 |
| 10. Delete Grails counterparts (conditional) | Task 10 |
| 11. Tests | Task 11 |
| 12. Soak | (folded into Task 13) |
| 13. Tag | Task 13 |

## Deferred follow-ups

Items surfaced during code reviews that were intentionally deferred rather than fixed in-task. Single source of truth for outstanding work so subagents dispatched against later tasks see the backlog. Delete a row when its fix lands. ID convention: `T<task>-<I|M>` (I = Important, M = Minor).

| ID | Sev | Target | Item | Where |
|---|---|---|---|---|
| T3-M2 | Minor | Task 4 | Guard `findByDocumentType_IdIn` against null/empty list in service layer (some DBs balk on `IN ()`; Hibernate 6 + MariaDB handles, but explicit guard is portable) | `service/DocumentService.java` (Task 4) |
| T3-M6 | Minor | Task 4 | Add `@Column(updatable=false)` to `dateCreated` on both entities; ensure service `create()` sets it explicitly (no `@CreationTimestamp` — would conflict with Grails-side GORM auto-stamp) | `entity/Document.java:63`, `entity/DocumentType.java:49` |
| T3-I3 | Important | Any time (recommend pre-Task 11) | Pin `TZ=UTC` env on both `app` and `document-service` to prevent latent wall-clock drift between Hibernate 6 (`Instant` → UTC) and Grails Hibernate 5 (`Date` → JVM-default-zone) if ops sets non-UTC timezone | `docker/docker-compose-base.yml` |
| T2-M4 | Minor | Any time | Add `.dockerignore` to trim `src/`, `.gradle/`, `build/classes`, `build/tmp`, `build/reports` from build context sent to daemon | `services/document-service/.dockerignore` |
| T2-M1 | Minor | Any time (optional perf) | CI `docker compose up --build` redundantly rebuilds Grails image after `prepareDocker`; could split into `compose build document-service` + `compose up` to skip ~30-60s per run | `.github/workflows/e2e-tests.yml` |
| T3-M3 | Minor | Any time | Make `@ManyToOne(fetch = FetchType.LAZY, optional = true)` explicit to match Grails `documentType(nullable: true)` constraint and guard against future-dev tightening | `entity/Document.java:81` |
| T3-M4 | Minor | Any time | Remove explicit `hibernate.dialect: org.hibernate.dialect.MariaDBDialect` — Hibernate 6 auto-detects from JDBC URL; logs `HHH90000025` deprecation warning on every boot | `services/document-service/src/main/resources/application.yml:15` |
| T3-M5 | Minor | Any time | `@Size(max = 255)` on entity fields only triggers on Bean Validation via `@Valid` DTO binding; either remove (DB enforces via varchar(255)) or document intent | `entity/Document.java`, `entity/DocumentType.java` |
| T2-M6 | Minor | Phase 2+ | Reconcile `services/` module `0.1.0-SNAPSHOT` versioning vs Grails release cadence once a second service ships | `services/build.gradle:8` |
| T4-M2 | Minor | Any time | `findFirstByName` returns DB-natural row when name has duplicates (no unique constraint on `name`); consider `findFirstByNameOrderByDateCreatedAsc` for deterministic "oldest" semantics matching clustered-PK iteration. Current data has one template per name; low risk | `repository/DocumentRepository.java:27` |
| T4-M4 | Minor | Bundle with T3-I3 | `Instant.now()` correctness depends on `serverTimezone=UTC` in JDBC URL (`application.yml:8`); add comment or pair with T3-I3 container TZ pin for full coupling guarantee | `service/DocumentService.java:94` |
| T4-M5 | Minor | Any time | `nullsLast(naturalOrder())` defensive on `@NotNull` field can never trigger; add `// defensive` comment or remove for clarity | `service/DocumentService.java:72` |
| T4-M6 | Minor | Any time hygiene | Dead `List<Document> findByName(String)` carried from Task 3 — unused after Task 4 switched the service to `findFirstByName`. Remove or `@Deprecated` | `repository/DocumentRepository.java:18` |
| T5-M1 | Minor | Any time | `SecurityConfig.csrf(csrf -> csrf.disable())` lacks intent comment; future dev flipping to session-based UI auth must remember to re-enable. Two-word fix | `security/SecurityConfig.java:23` |
| T5-M2 | Minor | Any time | `IllegalArgumentException` `@ExceptionHandler` returns raw `ex.getMessage()` in body. Scope is controller-only, but a future endpoint throwing one with a sensitive message would leak. Consider structured `{"error":..,"message":..}` body + server-side log | `controller/DocumentController.java:141-143` |
| T5-M3 | Minor | Any time | `getByName` 400 on blank name returns empty body, while 400 on unknown enum `?code=NOT_REAL` returns a Spring-default body. Inconsistent error-response shape across the controller | `controller/DocumentController.java:89-93` |
| T5-M4 | Minor | Any time | `?documentTypeId=` (empty string) currently flows to `typeRepo.findById("")` → empty Optional → 400 via T4-M3 path. Add one-line `isBlank()` guard in `DocumentService.create()` for symmetry with the `?name=` blank check | `service/DocumentService.java:98` |
| T5-M5 | Minor | Any time | `DocumentType.version` (optimistic-lock) still serialized in nested `documentType` JSON; I-1 fix only ignored `Document.version`. Add `@JsonIgnore` to `DocumentType.version` for symmetry | `entity/DocumentType.java` |
| T5-M6 | Minor | Task 7 hardening | `ContentDisposition.attachment().filename(name, UTF_8)` always emits the encoded-word fallback even for ASCII filenames (`filename="=?UTF-8?Q?..."?="; filename*=UTF-8''...`). Cosmetic; modern browsers prefer `filename*`. Standards-compliant | `controller/DocumentController.java:63-67` |
| T6-M1 | Minor | Any time hygiene | Mixed dbchangelog XSD namespaces across the 5 relocated/shadow files: master + shadow + file-uri on 4.5, three template-insert files on 1.9. Liquibase parses per-file XSDs independently so no runtime issue, but future devs touching the 1.9 files with 4.5-only elements (e.g., `addColumn` with `defaultValueComputed` variants) will hit parse errors. Bundle a uniformity pass with the next document-service changelog touchpoint | `services/document-service/src/main/resources/db/changelog/changelog-{2018,2022,2023}-*.xml` |
| T6-M2 | Important | Pre-production deploy | Production deploy runbook for Task 6 relocation: the 4 relocated FILENAMEs already exist in production DATABASECHANGELOG under the old `grails-app/migrations/0.8.x/...` paths (because those files DID run during 0.8.x upgrades). Post-Task-6 deploy: removing them from `grails-app/migrations/0.8.x/changelog.xml` is safe (Grails Liquibase ignores already-applied changesets not in current changelog); document-service creates NEW rows under `db/changelog/...` FILENAMEs (no collision, FILENAME differs). Net production effect per file: TWO rows in DATABASECHANGELOG (old + new MARK_RAN). Schema unchanged. Capture this in the production deploy runbook when one is written | runbook (not yet authored) |
| T7-M2 | Minor | Phase 2 (when authorization design lands) | `roles → SimpleGrantedAuthority` mapping is currently dead code: no `@PreAuthorize`/`@Secured`/`hasAuthority` exists anywhere in `services/document-service/src/main/java/`. Either wire authorization on `DocumentController` endpoints or delete lines 47-50. Keeping it now is defensible (matches Grails claim shape; Phase 2 likely needs it) | `services/document-service/src/main/java/org/openboxes/document/security/JwtCookieAuthFilter.java:47-50` |
| T7-M3 | Minor | Phase 2 (when authorization rules are written) | Role-claim format mismatch risk: Grails `JwtService.groovy:37` issues raw entity IDs (e.g., `R001`), NOT Spring-convention `ROLE_*` prefixed strings. Phase 2 implementers must use `hasAuthority("R001")` not `hasRole("R001")` (which auto-prefixes to `ROLE_R001`). Document this wherever Phase 2 auth rules are written | `grails-app/services/org/pih/warehouse/auth/JwtService.groovy:37` |
| T7-M4 | Minor | Any time | Actuator scoping correct today but implicit: only `/actuator/health` is permitAll while `management.endpoints.web.exposure.include` is unset (Spring Boot defaults to `health,info`). If ops later sets `include=*`, `/actuator/env|configprops|heapdump` will be auth-gated by `anyRequest().authenticated()` — accessible to any valid-token holder. Add a one-line comment in `application.yml` documenting the assumption | `services/document-service/src/main/resources/application.yml` |
| T8b-M1 | Minor | When all in-process callers confirmed migrated (post-Task 10) | Tighten `def document` → `Map` in 6 method signatures: `DocumentService.scaleImage` (line 91), `TemplateService.renderTemplate` (line 28), `DocumentTemplateService.{renderGroovyServerPageDocumentTemplate, renderInvoiceTemplate, renderOrderDocumentTemplate, renderRequisitionDocumentTemplate}`. Spec specified `Map`; implementer chose `def` for transitional Document↔Map coexistence. Restore static typing for refactoring safety | `grails-app/services/org/pih/warehouse/core/DocumentService.groovy:91`, `TemplateService.groovy:28`, `DocumentTemplateService.groovy` |
| T8b-M2 | Minor | Any time | Add `Map fetchByIdWithContent(String id)` helper to `DocumentClient` to collapse the 3 `documentInstance + [fileContents: documentClient.fetchContent(id)]` repetitions at `OrderController.groovy:957,977` and `ProductController.groovy:613`. One-shot helper would also amortize the 2 HTTP calls into a single round-trip if a server-side `?includeContent=true` flag is added later | `grails-app/services/org/pih/warehouse/core/DocumentClient.groovy` |
| T8b-M4 | Minor | Any time | Add null/format guard on `id` param in `DocumentClient.delete()` and `fetchContent()` for symmetry with `fetchById()`. Currently raw `${id}` interpolation in path; 32-char UUID-hex IDs are safe but defensive guard prevents misuse | `grails-app/services/org/pih/warehouse/core/DocumentClient.groovy:50-54,107-112` |
| T8b-M5 | Minor | Any time hygiene | Add `// TODO ... Phase 2+` marker to `InvoiceController.groovy:168` and to the 5 other bridge sites that currently lack one — only 2 of the 8 `Document.load(id)` sites (ProductController:463, StockMovementService:3458) carry explicit Phase 2+ markers today. See plan §"Phase X" `:1492` for the full enumeration | `grails-app/controllers/org/pih/warehouse/invoice/InvoiceController.groovy:168` (+ 5 others per Phase X note) |
| T8b-M6 | Minor | Any time hygiene | Add Javadoc to `DocumentClient.create()` documenting the return-body Map shape (`{id, name, filename, ...}`) — callers use `.id` from the returned Map (e.g., `Document.load(created.id)` bridge pattern) but the shape is implicit | `grails-app/services/org/pih/warehouse/core/DocumentClient.groovy:97` |
| T11-I1 | Important | Phase 2 / CI hardening | `DocumentServiceIntegrationTest` does NOT catch entity-vs-Liquibase divergence: it disables Liquibase (`spring.liquibase.enabled=false`) and uses `hibernate.ddl-auto=create-drop`, so Hibernate emits the schema from the `@Entity` then the test reads back via the same entity — a tautology for that concern. The production master changelog is a SHADOW over Grails-built tables and its preConditions fail against pristine MariaDB, so it can't be enabled here as-is. Either (a) extract the Grails-side prerequisite DDL into a test bootstrap fixture, (b) split the master changelog into a "bootstrap" + "shadow" pair so the bootstrap runs in test mode, or (c) keep the entity-vs-Liquibase check at app-boot Hibernate-validate level and assert it via a Playwright probe that exercises the live compose stack. The Playwright E2E specs cover this transitively today (any divergence breaks app startup), but a dedicated test would catch regressions before deploy | `services/document-service/src/test/java/org/openboxes/document/DocumentServiceIntegrationTest.java:18-32`, `services/document-service/src/main/resources/db/changelog/document-changelog-master.xml` |
| T11-M2 | Minor | Any time | Playwright specs accumulate rows in the dev DB across re-runs: upload/download/list-by-code each POST a Document without `afterEach` cleanup (only `delete.spec.ts` clears its own row). Test names contain timestamps so collisions are avoided, but the DB grows monotonically. Add `afterEach` that DELETEs the uploaded doc via the same authenticated session | `e2e/tests/document-{upload,download,list-by-code}.spec.ts` |
| T11-M3 | Minor | Any time | `DocumentUploadController.upload` null-guard (T8b-I5 landed) redirects to `view` with `id: command.shipmentId` — but if `shipmentId` is also missing/null the redirect lands on a broken page. Pre-existing problem now reachable from the new guard path. Either return HTTP 400 with a structured error, or redirect to a `/shipping/list` fallback when `shipmentId` is absent | `grails-app/controllers/org/pih/warehouse/shipping/DocumentUploadController.groovy:32` |
| T11-M4 | Minor | Any time hygiene | `JwtCookieAuthFilter.logger` is the inherited Apache commons `Log` from `OncePerRequestFilter` — no `{}` placeholder support, so the debug log uses string concatenation (`"JWT cookie rejected: " + e.getClass().getSimpleName()`). Switch to a private SLF4J `Logger` via `LoggerFactory.getLogger(JwtCookieAuthFilter.class)` for placeholder support, lazy evaluation, and consistency with other Spring Boot 3 code in the module | `services/document-service/src/main/java/org/openboxes/document/security/JwtCookieAuthFilter.java:58` |
| T11-M5 | Minor | Any time | `DocumentUploadController.upload` flash message "No file uploaded" is hardcoded English. Codebase has mixed i18n patterns (`SecurityInterceptor` uses literals; `RequisitionController` uses `g.message`). If shipping uploads continue to grow, key this through `i18n/messages.properties` for consistency with the multilingual deploy targets (locales include `fr`, `es`, `pt`, `tet`, etc. per Phase 0 audit) | `grails-app/controllers/org/pih/warehouse/shipping/DocumentUploadController.groovy:29` |
| T11-M6 | Minor | Any time hygiene | Playwright login boilerplate is duplicated ~10 lines × 5 specs. Extract a `loginFixture` (Playwright fixture pattern) or a `login()` helper in `e2e/tests/_helpers.ts` so each spec calls `await login(request)` instead of 10 lines of `request.post('/api/login', ...)` | `e2e/tests/document-{upload,download,list-by-code,delete,callers-regression}.spec.ts` |
| T9-M2 | Minor | Phase 2+ (when TLS lands) | Add `proxy_set_header X-Forwarded-Proto $scheme;` to `/api/documents`, `/api/`, and `/openboxes/` blocks when TLS termination is introduced. Currently all 3 blocks lack it (parity preserved). document-service does not consume it today but Spring Security URL building and any future redirect logic will rely on it once HTTPS is in play | `docker/nginx/conf.d/app.conf` (all 3 location blocks) |

## Tasks

### Task 1: Scope audit + live-smoke-probe (§8 Step 1)

**Files:**
- Create: `docs/audits/2026-05-26-phase-1-document-scope-audit.md` (audit notes; gitignored or committed per user preference — defaulting to committed for traceability)

- [ ] **Step 1: Run the §8 four-grep cross-context audit.**
```bash
# a. addTo/removeFrom on Document instances
grep -rn "\.addToDocuments\|\.removeFromDocuments" grails-app/

# b. Document.delete patterns
grep -rn "Document\.delete\|documentInstance\.delete\|document\?\.delete" grails-app/

# c. new Document( + .save( patterns
grep -rn "new Document(" grails-app/ ; grep -rnB2 "documentInstance\.save\|document\.save" grails-app/

# d. documentService injections crossing γ boundaries
grep -rn "def documentService\|@Autowired.*DocumentService\|documentService\." grails-app/ | grep -v "DocumentService\.groovy\|DocumentController\.groovy"
```
For each finding, classify against §4.3 policy:
- M:N link write on owning side → local (none expected for Document; it's a simple entity)
- Atomic across 2 contexts → 2-step saga (deferred to Phase 7 per spec; Phase 1 uses sync HTTP with cookie forwarding instead — acceptable because Document writes are admin-scale, not high-throughput)
- Read-only cross-context → HTTP call
Write findings to `docs/audits/2026-05-26-phase-1-document-scope-audit.md`.

- [ ] **Step 2: Confirm the caller-file list matches plan's "Modify" section.**
Expected (from plan-level verification): 11 controller/service files + 3 GSPs + ~6 documentService entity-facing callers. If audit surfaces MORE than 2 ADDITIONAL files, **stop and surface to user** per the scope-creep guardrail (a plan revision is preferable to silent scope expansion).

- [ ] **Step 3: Live-smoke-probe the existing Grails Document flows.**
With the current `:latest` running stack:
```bash
# Login + warehouse
JAR=$(mktemp); curl -s -c "$JAR" -X POST http://localhost/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password","location":"1"}' -o /dev/null

# Probe each caller flow's reachability (expect 200 or known-acceptable redirect):
for url in \
  "/openboxes/dataExport/index" \
  "/openboxes/dataExport/render?id=1" \
  "/openboxes/document/list" \
  "/openboxes/document/show/1" \
  "/openboxes/invoice/list" \
  "/openboxes/product/list" \
  "/openboxes/shipment/list"; do
  echo "$url -> $(curl -s -b "$JAR" -L -o /dev/null -w '%{url_effective} HTTP %{http_code}' "http://localhost${url}")"
done
rm -f "$JAR"
```
Record results in the audit doc. If any flow returns 4xx/5xx that the plan assumed works, surface to user.

- [ ] **Step 4: Enumerate the Liquibase changesets to relocate (Task 6 input).**
```bash
find grails-app/migrations -iname "*document*" -o -iname "*shipment-document*" -o -iname "*invoice-document*"
```
Record exact file list in the audit doc. Distinguish:
- Document-table-owned changesets (these move to document-service)
- Join-table changesets (`shipment_invoice`, `order_invoice`, etc. — these stay with their owning service; for `invoice_document`-style join tables, billing-service owns per spec §4.3 — those move with billing-service in Phase 10, NOT now; flag any that ambiguously affect both `document` and a join table)

- [ ] **Step 5: Commit.**
```bash
git add docs/audits/2026-05-26-phase-1-document-scope-audit.md
git commit -m "phase 1: cross-context audit + scope confirmation for Document slice"
```

### Task 2: Bootstrap services/ Gradle 8 build + document-service module + add build: directive (§8 Step 2)

**Files:**
- Create: `services/build.gradle`, `services/settings.gradle`, `services/gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}`, `services/gradlew`, `services/gradlew.bat`
- Create: `services/document-service/build.gradle`, `services/document-service/src/main/java/org/openboxes/document/DocumentServiceApplication.java`, `services/document-service/Dockerfile`, `services/document-service/src/main/resources/application.yml`
- Modify: `docker/docker-compose-base.yml` — add `document-service` entry; add `build:` directive to `app` service
- Modify: `.github/workflows/e2e-tests.yml` — if compose `build:` directive handles things, remove the explicit `docker build` step

- [ ] **Step 1: Initialize Gradle 8 wrapper in `services/`.**
```bash
mkdir -p services
cd services
# Use a temporary container or system gradle to generate the 8.5 wrapper:
# If you have Gradle 8.5+ on host: gradle wrapper --gradle-version 8.5
# Otherwise: docker run --rm -v "$PWD":/work -w /work gradle:8.5-jdk21 gradle wrapper --gradle-version 8.5
```

- [ ] **Step 2: Create `services/settings.gradle`.**
```groovy
rootProject.name = 'openboxes-services'
include 'document-service'
```

- [ ] **Step 3: Create `services/build.gradle`.**
```groovy
plugins {
    id 'org.springframework.boot' version '3.3.5' apply false
    id 'io.spring.dependency-management' version '1.1.6' apply false
}

allprojects {
    group = 'org.openboxes'
    version = '0.1.0-SNAPSHOT'

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply plugin: 'java'
    apply plugin: 'org.springframework.boot'
    apply plugin: 'io.spring.dependency-management'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
}
```

- [ ] **Step 4: Create `services/document-service/build.gradle`.**
```groovy
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
}
```

- [ ] **Step 5: Create `services/document-service/src/main/java/org/openboxes/document/DocumentServiceApplication.java`.**
```java
package org.openboxes.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DocumentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }
}
```

- [ ] **Step 6: Create `services/document-service/src/main/resources/application.yml`.**
```yaml
server:
  port: 8081

spring:
  application:
    name: document-service
  datasource:
    url: ${DATASOURCE_URL:jdbc:mariadb://db:3306/openboxes?serverTimezone=UTC&useSSL=false}
    username: ${DATASOURCE_USERNAME:openboxes}
    password: ${DATASOURCE_PASSWORD:openboxes}
  jpa:
    hibernate:
      ddl-auto: validate  # Liquibase owns schema; JPA only validates the entity-to-table mapping
    properties:
      hibernate.dialect: org.hibernate.dialect.MariaDBDialect
  liquibase:
    change-log: classpath:db/changelog/document-changelog-master.xml

openboxes:
  jwt:
    secret: ${OPENBOXES_JWT_SECRET:dev-secret-only-for-local-please-rotate-in-prod}
```

- [ ] **Step 7: Create `services/document-service/Dockerfile`.**
```dockerfile
FROM eclipse-temurin:21-jre-jammy
EXPOSE 8081
WORKDIR /app
COPY build/libs/document-service-*.jar /app/document-service.jar
ENTRYPOINT ["java", "-jar", "/app/document-service.jar"]
```

- [ ] **Step 8: Modify `docker/docker-compose-base.yml`.**
Add `document-service` to `services:`:
```yaml
    document-service:
      build:
        context: ../services/document-service
        dockerfile: Dockerfile
      container_name: openboxes-document-service
      expose:
        - "8081"
      environment:
        DATASOURCE_URL: ${DATASOURCE_URL:-jdbc:mariadb://db:3306/openboxes?serverTimezone=UTC&useSSL=false}
        DATASOURCE_USERNAME: ${DATASOURCE_USERNAME:-openboxes}
        DATASOURCE_PASSWORD: ${DATASOURCE_PASSWORD:-openboxes}
        OPENBOXES_JWT_SECRET: ${OPENBOXES_JWT_SECRET:-dev-secret-only-for-local-please-rotate-in-prod}
      depends_on:
        db:
          condition: service_healthy
      healthcheck:
        test: "curl --fail --silent localhost:8081/actuator/health | grep UP || exit 1"
        interval: 10s
        timeout: 5s
        retries: 5
        start_period: 30s
```
Also add `build:` directive to the existing `app` service (Phase 0 retrospective follow-up):
```yaml
    app:
      build:
        context: ../build/docker
        dockerfile: Dockerfile
      image: ${OB_IMAGE_REPOSITORY-ghcr.io/}openboxes/openboxes:${OB_VERSION:-latest}
      # ... existing config below unchanged ...
```
Note: keep the `image:` line so the locally-built image gets that tag for traceability. `docker-compose up --build` rebuilds; `docker-compose up` (no `--build`) uses cached. Update docker-compose.yml flow in docs/ as needed.

- [ ] **Step 9: Build + boot the empty service via compose.**
```bash
# Build the Grails WAR (still required, even with build: directive)
./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain

# Build the document-service jar
cd services && ./gradlew :document-service:bootJar && cd ..

# Build + start the stack
cd docker
sudo docker-compose down
sudo docker-compose up --build -d
for i in {1..60}; do
  curl -sf http://localhost/openboxes/health && \
  curl -sf http://localhost:8081/actuator/health 2>/dev/null || \
  (sleep 5; continue)
  break
done
cd ..

# Verify: document-service responds on 8081 (via container port)
sudo docker exec openboxes-document-service curl -sf localhost:8081/actuator/health
```
Expect: both services come up healthy. If `document-service` fails to start, investigate before proceeding (likely DB connectivity or port conflict).

- [ ] **Step 10: Simplify `.github/workflows/e2e-tests.yml` build step (Phase 0 follow-up).**
Now that `docker-compose up --build` rebuilds the app image automatically, the explicit `docker build -t ghcr.io/openboxes/openboxes:latest build/docker/` step in the CI workflow can be removed. Replace with `--build` flag on `docker-compose up`:
```yaml
      - name: Build local image with current source
        run: ./gradlew prepareDocker -Dgrails.env=prod --console=plain
      - name: Build document-service jar
        run: cd services && ./gradlew :document-service:bootJar
      - name: Boot docker-compose stack
        working-directory: docker
        run: |
          docker-compose up --build -d
          for i in {1..60}; do
            curl -sf http://localhost/openboxes/health && curl -sf http://localhost:8081/actuator/health && break
            sleep 5
          done
```

- [ ] **Step 11: Commit.**
```bash
git add services/ docker/docker-compose-base.yml .github/workflows/e2e-tests.yml
git commit -m "phase 1: bootstrap services/ Gradle 8 build + document-service skeleton + permanent compose build directive"
```

### Task 3: Port Document + DocumentType to JPA entities (§8 Step 3)

**Files:**
- Create: `services/document-service/src/main/java/org/openboxes/document/entity/Document.java`, `DocumentType.java`, `DocumentCode.java` (Java enum mirroring Grails)
- Create: `services/document-service/src/main/java/org/openboxes/document/repository/DocumentRepository.java`, `DocumentTypeRepository.java`

- [ ] **Step 1: Create `Document.java` entity.**
```java
package org.openboxes.document.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@Entity
@Table(name = "document")
public class Document {
    @Id
    @Column(name = "id", length = 255)
    private String id;  // UUID, generated app-side at create

    @Size(max = 255)
    @Column(name = "name")
    private String name;

    @Size(max = 255)
    @Column(name = "filename")
    private String filename;

    @Size(max = 255)
    @Column(name = "extension")
    private String extension;

    @Size(max = 255)
    @Column(name = "content_type")
    private String contentType;

    @Lob
    @Column(name = "file_contents")
    private byte[] fileContents;

    @Column(name = "date_created")
    private Instant dateCreated;

    @Column(name = "last_updated")
    private Instant lastUpdated;

    @Column(name = "file_uri")
    private String fileUri;

    @Size(max = 255)
    @Column(name = "document_number")
    private String documentNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_type_id")
    private DocumentType documentType;

    // getters/setters omitted for brevity — generate via IDE; keep constructor public no-arg for JPA
}
```
**Important:** verify column names against the actual DB schema using `sudo docker exec openboxes-db mysql -u openboxes -popenboxes openboxes -e "DESCRIBE document"` before final commit. The plan's column-name guesses may need correction (e.g., `file_contents` vs `fileContents` vs `file_uri`).

- [ ] **Step 2: Create `DocumentType.java` entity.**
```java
package org.openboxes.document.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

@Entity
@Table(name = "document_type")
public class DocumentType {
    @Id
    @Column(name = "id", length = 255)
    private String id;  // mixed type per P18: integer-string AND UUID — JPA String accommodates both

    @NotNull
    @Size(max = 255)
    @Column(name = "name", nullable = false)
    private String name;

    @Size(max = 255)
    @Column(name = "description")
    private String description;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "date_created")
    private Instant dateCreated;

    @Column(name = "last_updated")
    private Instant lastUpdated;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_code")
    private DocumentCode documentCode;

    // getters/setters
}
```

- [ ] **Step 3: Create `DocumentCode.java` enum.**
Mirror the Grails `DocumentCode` enum. After Task 1 audit identifies the enum values from `grails-app/domain/org/pih/warehouse/core/DocumentCode.groovy` (if it's a separate file) or the nested enum in Document.groovy:
```java
package org.openboxes.document.entity;

public enum DocumentCode {
    DATA_EXPORT,
    REQUISITION_TEMPLATE,
    INVOICE_TEMPLATE,
    PURCHASE_ORDER_TEMPLATE,
    ZEBRA_TEMPLATE
    // ... full list determined by Task 1 audit
}
```

- [ ] **Step 4: Create repository interfaces.**
```java
// DocumentRepository.java
package org.openboxes.document.repository;

import org.openboxes.document.entity.Document;
import org.openboxes.document.entity.DocumentCode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByDocumentType_DocumentCode(DocumentCode code);
    List<Document> findByName(String name);
    List<Document> findByDocumentType_IdIn(List<String> typeIds);
}

// DocumentTypeRepository.java
package org.openboxes.document.repository;

import org.openboxes.document.entity.DocumentCode;
import org.openboxes.document.entity.DocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, String> {
    List<DocumentType> findByDocumentCodeIsNull();  // for getNonTemplateDocumentTypes
    List<DocumentType> findByDocumentCodeNotNull();
}
```

- [ ] **Step 5: Live-probe entity mapping.**
```bash
cd services && ./gradlew :document-service:bootJar && cd ..
cd docker && sudo docker-compose up -d --build document-service && cd ..
sleep 30
# Hibernate will run validate at startup — if column names don't match, the service fails to start with a clear error.
sudo docker logs openboxes-document-service 2>&1 | grep -iE "error\|hibernate\|column" | head -20
# Verify document_type rows are loadable via direct JPA query:
sudo docker exec openboxes-document-service curl -sf http://localhost:8081/actuator/health
```
If schema validation fails, correct column names per actual DESCRIBE output and retry. Plan-level P16/P17 surfaced the Grails field shape but JPA column mapping requires DB-side verification.

- [ ] **Step 6: Commit.**
```bash
git add services/document-service/src/main/java/org/openboxes/document/entity/ \
        services/document-service/src/main/java/org/openboxes/document/repository/
git commit -m "phase 1: port Document + DocumentType to JPA entities + repositories"
```

### Task 4: Port DocumentService business logic (§8 Step 4)

**Files:**
- Create: `services/document-service/src/main/java/org/openboxes/document/service/DocumentService.java`

- [ ] **Step 1: Implement service with the 6 entity-facing methods.**
```java
package org.openboxes.document.service;

import org.openboxes.document.entity.Document;
import org.openboxes.document.entity.DocumentCode;
import org.openboxes.document.entity.DocumentType;
import org.openboxes.document.repository.DocumentRepository;
import org.openboxes.document.repository.DocumentTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentService {
    private final DocumentRepository docRepo;
    private final DocumentTypeRepository typeRepo;

    public DocumentService(DocumentRepository docRepo, DocumentTypeRepository typeRepo) {
        this.docRepo = docRepo;
        this.typeRepo = typeRepo;
    }

    public Optional<Document> findById(String id) { return docRepo.findById(id); }

    public List<Document> findByCode(DocumentCode code) {
        return docRepo.findByDocumentType_DocumentCode(code);
    }

    public List<Document> findByName(String name) { return docRepo.findByName(name); }

    public List<Document> findByTypeIds(List<String> typeIds) {
        return docRepo.findByDocumentType_IdIn(typeIds);
    }

    public List<DocumentType> getNonTemplateDocumentTypes() {
        return typeRepo.findByDocumentCodeIsNull();
    }

    public List<DocumentType> getAllDocumentTypes() { return typeRepo.findAll(); }

    @Transactional
    public Document create(String name, String filename, String contentType, byte[] fileContents, String documentTypeId) {
        Document d = new Document();
        d.setId(UUID.randomUUID().toString().replace("-", ""));
        d.setName(name);
        d.setFilename(filename);
        d.setContentType(contentType);
        d.setFileContents(fileContents);
        if (documentTypeId != null) {
            typeRepo.findById(documentTypeId).ifPresent(d::setDocumentType);
        }
        return docRepo.save(d);
    }

    @Transactional
    public void delete(String id) { docRepo.deleteById(id); }
}
```
Beyond `getNonTemplateDocumentTypes`, port any additional Document-entity-facing methods that Task 1 audit surfaced (excluding `getAllDocumentsBySupplierOrganization`, which is an Order-rooted query and stays Grails-side per File Structure note).

- [ ] **Step 2: Commit.**
```bash
git add services/document-service/src/main/java/org/openboxes/document/service/
git commit -m "phase 1: port DocumentService entity-facing methods to Spring @Service"
```

### Task 5: Port REST controllers + springdoc-openapi (§8 Step 5)

**Files:**
- Create: `services/document-service/src/main/java/org/openboxes/document/controller/DocumentController.java`

- [ ] **Step 1: Implement REST controller.**
```java
package org.openboxes.document.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.openboxes.document.entity.Document;
import org.openboxes.document.entity.DocumentCode;
import org.openboxes.document.entity.DocumentType;
import org.openboxes.document.service.DocumentService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService docService;

    public DocumentController(DocumentService docService) { this.docService = docService; }

    @Operation(summary = "Fetch document metadata")
    @GetMapping("/{id}")
    public ResponseEntity<Document> getById(@PathVariable String id) {
        return docService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Stream document content")
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> getContent(@PathVariable String id) {
        return docService.findById(id).map(d -> ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, d.getContentType() != null ? d.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.getFilename() + "\"")
                .body(d.getFileContents()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "List documents by document code")
    @GetMapping(params = "code")
    public List<Document> listByCode(@RequestParam DocumentCode code) {
        return docService.findByCode(code);
    }

    @Operation(summary = "Find document by name")
    @GetMapping(params = "name")
    public List<Document> listByName(@RequestParam String name) {
        return docService.findByName(name);
    }

    @Operation(summary = "List documents whose document_type is in the given set")
    @GetMapping(params = "typeIds")
    public List<Document> listByTypeIds(@RequestParam List<String> typeIds) {
        return docService.findByTypeIds(typeIds);
    }

    @Operation(summary = "Upload document (multipart)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Document create(@RequestParam("file") MultipartFile file,
                           @RequestParam(value = "name", required = false) String name,
                           @RequestParam(value = "documentTypeId", required = false) String documentTypeId) throws java.io.IOException {
        return docService.create(name != null ? name : file.getOriginalFilename(),
                                  file.getOriginalFilename(),
                                  file.getContentType(),
                                  file.getBytes(),
                                  documentTypeId);
    }

    @Operation(summary = "Delete document")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        docService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List non-template document types (for caller forms)")
    @GetMapping("/types/non-template")
    public List<DocumentType> nonTemplateTypes() { return docService.getNonTemplateDocumentTypes(); }
}
```

- [ ] **Step 2: Live-probe endpoint shapes (with cookie auth still TODO in Task 7 — expect 401 until then if security is wired before; for now permitAll).**
For this task, configure Spring Security temporarily with `permitAll()` so live-probe works; Task 7 will tighten. Or skip the live-probe until Task 7 and verify both at once.

- [ ] **Step 3: Commit.**
```bash
git add services/document-service/src/main/java/org/openboxes/document/controller/
git commit -m "phase 1: add DocumentController REST endpoints + springdoc annotations"
```

### Task 6: Move Liquibase changesets into document-service (§8 Step 6)

**Files:**
- Move: `grails-app/migrations/0.X.x/changelog-*-document-*.xml` (and DocumentType-affecting changesets) → `services/document-service/src/main/resources/db/changelog/`
- Create: `services/document-service/src/main/resources/db/changelog/document-changelog-master.xml`

- [ ] **Step 1: Use the Task 1 audit's Liquibase file list to plan the move.**
Per audit: relocate ONLY pure document/document_type changesets. Join-table changesets (e.g., `invoice_document` join) STAY in grails-app/migrations/ — billing-service will own those at Phase 10 per spec §4.3 link-table policy.

- [ ] **Step 2: Move files.**
```bash
# example — actual list from Task 1 audit
mkdir -p services/document-service/src/main/resources/db/changelog
git mv grails-app/migrations/0.8.x/changelog-2021-01-30-1530-alter-table-document-change-file-uri-column-type.xml \
       services/document-service/src/main/resources/db/changelog/
git mv grails-app/migrations/0.8.x/changelog-2023-05-22-1800-add-requisition-template-document-type.xml \
       services/document-service/src/main/resources/db/changelog/
# ... + the original create-table-document, create-table-document-type changesets (likely in earlier 0.7.x or 0.6.x)
```

- [ ] **Step 3: Update the Grails master changelog to remove the relocated files.**
Edit `grails-app/migrations/changelog.groovy` (or whichever is the master) — remove `<include>` entries for the moved files. Grails Liquibase no longer tries to re-run them.

- [ ] **Step 4: Create document-service master changelog.**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.5.xsd">
    <!-- include all relocated document/document_type changesets in original chronological order -->
    <include file="db/changelog/changelog-YYYY-MM-DD-HHMM-name.xml"/>
    <!-- ... -->
</databaseChangeLog>
```

- [ ] **Step 5: Add `<preConditions onFail="MARK_RAN">` to each relocated changeset, then verify shared DATABASECHANGELOG.**

A17's SUBSTRING reference is misleading for relocation: that SUBSTRING usage in `src/main/groovy/util/LiquibaseUtil.groovy:108` lives inside `getCurrentVersionsByFolderName()` and extracts the **version-folder name** (FILENAME segment before the first `/`) for backward-compat upgrade-path logic — it is NOT per-changeset dedup. Standard Spring Boot Liquibase (document-service's runner) deduplicates via the `(ID, AUTHOR, FILENAME)` tuple with exact FILENAME match. After Task 6 Step 2's `git mv`, the relocated changeset's FILENAME changes from `grails-app/migrations/0.8.x/changelog-…xml` to `db/changelog/changelog-…xml` (classpath-relative); document-service sees it as **new** and tries to re-execute against an already-populated schema → fails.

Add `<preConditions onFail="MARK_RAN">` to every relocated changeset before first `docker-compose up` of document-service. Per changeset type:

- **`<createTable>`** changesets:
```xml
<preConditions onFail="MARK_RAN">
    <not><tableExists tableName="document"/></not>
</preConditions>
```
- **`<addColumn>`** changesets:
```xml
<preConditions onFail="MARK_RAN">
    <not><columnExists tableName="document" columnName="file_uri"/></not>
</preConditions>
```
- **`<modifyDataType>` / `<alterColumn>`** changesets — check via `<sqlCheck>`:
```xml
<preConditions onFail="MARK_RAN">
    <not><sqlCheck expectedResult="VARCHAR(2000)">
        SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME='document' AND COLUMN_NAME='file_uri'
    </sqlCheck></not>
</preConditions>
```
- **`<insert>`** seed-data changesets — `<sqlCheck>` on row presence.

`onFail="MARK_RAN"` records the changeset as ran in DATABASECHANGELOG (under the new FILENAME) without executing the body. document-service Liquibase startup completes; future additive migrations apply normally.

Verify:
```bash
# First, capture pre-move count for comparison
sudo docker exec openboxes-db mysql -u openboxes -popenboxes openboxes -Nse \
  "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE FILENAME LIKE '%document%';"
# (record this number as PRE_COUNT)

# Bring up the stack with the relocated + precondition-armed changesets
cd docker && sudo docker-compose up -d --build && cd ..
sleep 30

# Re-count
sudo docker exec openboxes-db mysql -u openboxes -popenboxes openboxes -e \
  "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE FILENAME LIKE '%document%';"
# Expect ≈ 2 × PRE_COUNT (each relocated changeset has both the old-FILENAME row from Grails history AND a new-FILENAME row from document-service MARK_RAN). Any count materially below 2×PRE_COUNT means a changeset re-executed against existing schema — investigate before proceeding.
```

- [ ] **Step 6: Apply additive-only constraint (per §8 Step 6).**
Until Task 10 deletes Grails Document.groovy, no schema-breaking changes can be added to the relocated changesets. Future `add-column` to document table is OK; `rename-column`, `drop-column`, `add-not-null-constraint` are NOT. Add a comment to `document-changelog-master.xml`:
```xml
<!-- ADDITIVE-ONLY until Phase 1 Task 10 deletes Grails Document.groovy.
     New tables / nullable columns / indexes OK. No renames, drops, or NOT NULL on existing columns.
     Constraint lifts once grails-app/domain/org/pih/warehouse/core/Document.groovy is gone. -->
```

- [ ] **Step 7: Commit.**
```bash
git add services/document-service/src/main/resources/db/changelog/ grails-app/migrations/
git commit -m "phase 1: relocate Document Liquibase changesets to document-service (filename-namespace shared with Grails)"
```

### Task 7: Wire JWT cookie validation (§8 Step 7 — saga deferred to Phase 7 per spec §4.5)

**Files:**
- Create: `services/document-service/src/main/java/org/openboxes/document/security/JwtCookieAuthFilter.java`, `SecurityConfig.java`

- [ ] **Step 1: Create the filter.**
```java
package org.openboxes.document.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtCookieAuthFilter extends OncePerRequestFilter {
    private final SecretKey signingKey;

    public JwtCookieAuthFilter(@Value("${openboxes.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if ("obx_token".equals(c.getName())) {
                    try {
                        Claims claims = Jwts.parser()
                                .verifyWith(signingKey)
                                .build()
                                .parseSignedClaims(c.getValue())
                                .getPayload();
                        String userId = claims.getSubject();
                        @SuppressWarnings("unchecked")
                        List<String> roles = (List<String>) claims.get("roles", List.class);
                        var authorities = roles == null ? List.<SimpleGrantedAuthority>of()
                                : roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
                        var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    } catch (JwtException ignored) {
                        // invalid token — leave SecurityContext empty; downstream rejects
                    }
                    break;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
```

- [ ] **Step 2: Create SecurityConfig.**
```java
package org.openboxes.document.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtCookieAuthFilter jwtFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
```

- [ ] **Step 3: Live-probe authentication.**
```bash
# Rebuild + restart
cd services && ./gradlew :document-service:bootJar && cd ..
cd docker && sudo docker-compose up -d --build document-service && cd ..
sleep 30

# Probe (1) no cookie → 401, (2) valid cookie → 200, (3) bad cookie → 401
echo "--- no cookie ---"
curl -s -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8081/api/documents/types/non-template

JAR=$(mktemp)
curl -s -c "$JAR" -X POST http://localhost/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password","location":"1"}' -o /dev/null

echo "--- valid cookie ---"
curl -s -b "$JAR" -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8081/api/documents/types/non-template

echo "--- tampered cookie ---"
sed -i 's/obx_token=eyJ/obx_token=eyJTAMPERED/g' "$JAR"
curl -s -b "$JAR" -o /dev/null -w "HTTP %{http_code}\n" http://localhost:8081/api/documents/types/non-template
rm -f "$JAR"
```
Expect: 401, 200, 401.

- [ ] **Step 4: Commit.**
```bash
git add services/document-service/src/main/java/org/openboxes/document/security/
git commit -m "phase 1: wire obx_token JWT cookie validation in document-service"
```

### Task 8a: Update React frontend (§8 Step 8a — NO-OP for Phase 1)

**Files:** (none)

- [ ] **Step 1: Confirm no React Document API code exists.**
```bash
grep -rnE "/document\|/api/documents\|DocumentApi" src/js/
```
Expect: zero output. If anything surfaces, surface to user — plan-level P36 needs revision.

- [ ] **Step 2: No commit.**
This task is a template-alignment placeholder. The Document slice currently has no React API surface to migrate. Future Document UI work (e.g., a new React upload page) would land in Phase 12 frontend decoupling or in a dedicated React-Document-UI mini-phase if/when prioritized.

### Task 8b: Migrate Grails callers to HTTP via DocumentClient (§8 Step 8b)

**Files:**
- Create: `grails-app/services/org/pih/warehouse/core/DocumentClient.groovy`
- Modify: ~11 controller/service files + 3 GSPs (via wrapping actions) + 6 documentService.* entity-facing call sites (full list in plan File Structure section)

- [ ] **Step 1: Create `DocumentClient.groovy`.**
```groovy
package org.pih.warehouse.core

import groovy.json.JsonSlurper
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

import javax.servlet.http.HttpServletRequest
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin HTTP client that proxies Document-entity operations to document-service.
 * Forwards the current request's obx_token cookie per spec §4.4 so document-service
 * validates with the same identity as the originating user. Dies when document-service
 * becomes the only consumer of these methods (i.e., when Grails callers themselves migrate
 * in their own slices — most by Phase 8-11).
 */
class DocumentClient {

    String baseUrl = System.getenv('DOCUMENT_SERVICE_URL') ?: 'http://document-service:8081'

    private String currentObxToken() {
        HttpServletRequest req = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).request
        req.cookies?.find { it.name == 'obx_token' }?.value
    }

    private HttpURLConnection openConn(String path, String method = 'GET') {
        HttpURLConnection conn = (HttpURLConnection) new URL("${baseUrl}${path}").openConnection()
        conn.requestMethod = method
        String token = currentObxToken()
        if (token) conn.setRequestProperty('Cookie', "obx_token=${token}")
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        return conn
    }

    Map fetchById(String id) {
        HttpURLConnection conn = openConn("/api/documents/${id}")
        if (conn.responseCode == 404) return null
        if (conn.responseCode != 200) throw new RuntimeException("document-service GET /${id} returned ${conn.responseCode}")
        return (Map) new JsonSlurper().parse(conn.inputStream)
    }

    byte[] fetchContent(String id) {
        HttpURLConnection conn = openConn("/api/documents/${id}/content")
        if (conn.responseCode != 200) throw new RuntimeException("document-service GET /${id}/content returned ${conn.responseCode}")
        return conn.inputStream.bytes
    }

    List<Map> findByCode(String code) {
        HttpURLConnection conn = openConn("/api/documents?code=${URLEncoder.encode(code, 'UTF-8')}")
        if (conn.responseCode != 200) throw new RuntimeException("document-service GET ?code=${code} returned ${conn.responseCode}")
        return (List<Map>) new JsonSlurper().parse(conn.inputStream)
    }

    List<Map> findByName(String name) {
        HttpURLConnection conn = openConn("/api/documents?name=${URLEncoder.encode(name, 'UTF-8')}")
        if (conn.responseCode != 200) throw new RuntimeException("document-service GET ?name=${name} returned ${conn.responseCode}")
        return (List<Map>) new JsonSlurper().parse(conn.inputStream)
    }

    List<Map> findByTypeIds(List<String> typeIds) {
        String csv = typeIds.collect { URLEncoder.encode(it, 'UTF-8') }.join(',')
        HttpURLConnection conn = openConn("/api/documents?typeIds=${csv}")
        if (conn.responseCode != 200) throw new RuntimeException("document-service GET ?typeIds returned ${conn.responseCode}")
        return (List<Map>) new JsonSlurper().parse(conn.inputStream)
    }

    List<Map> nonTemplateDocumentTypes() {
        HttpURLConnection conn = openConn("/api/documents/types/non-template")
        if (conn.responseCode != 200) throw new RuntimeException("document-service GET /types/non-template returned ${conn.responseCode}")
        return (List<Map>) new JsonSlurper().parse(conn.inputStream)
    }

    Map create(String name, String filename, String contentType, byte[] fileContents, String documentTypeId = null) {
        def headers = new org.springframework.http.HttpHeaders()
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
        String token = currentObxToken()
        if (token) headers.add('Cookie', "obx_token=${token}")
        def body = new org.springframework.util.LinkedMultiValueMap<String, Object>()
        body.add('file', new org.springframework.core.io.ByteArrayResource(fileContents) {
            @Override String getFilename() { filename }
        })
        body.add('name', name)
        if (documentTypeId) body.add('documentTypeId', documentTypeId)
        def rest = new org.springframework.web.client.RestTemplate()
        def resp = rest.exchange(
            "${baseUrl}/api/documents",
            org.springframework.http.HttpMethod.POST,
            new org.springframework.http.HttpEntity<>(body, headers),
            Map
        )
        return resp.body
    }

    void delete(String id) {
        HttpURLConnection conn = openConn("/api/documents/${id}", 'DELETE')
        if (conn.responseCode != 204) throw new RuntimeException("document-service DELETE /${id} returned ${conn.responseCode}")
    }
}
```
**Note:** `create()` uses Spring `RestTemplate` for multipart upload (already in Grails Spring context; zero new dependencies). Decision made during plan-update Round 1 per CIR §3 Forced Decision 1.

- [ ] **Step 2: Wire `DocumentClient` as a Grails Spring bean.**
Add to `grails-app/conf/spring/resources.groovy`:
```groovy
beans = {
    documentClient(org.pih.warehouse.core.DocumentClient)
}
```

- [ ] **Step 3: Migrate `DataExportController` (2 sites).**
- Line 23: `List<Document> documents = Document.findAllByDocumentCode(DocumentCode.DATA_EXPORT)` → `List<Map> documents = documentClient.findByCode('DATA_EXPORT')`
- Line 28: `Document document = Document.get(params.id)` → `Map document = documentClient.fetchById(params.id)`
Adjust GSP / model to consume Map instead of Document where needed.

- [ ] **Step 4: Migrate `TemplateService.renderTemplate(Document, Map)`.**
Method signature change: `String renderTemplate(Map document, Map model)` (Document → Map). Caller (`InvoiceController`) pre-fetches the document via `documentClient.fetchContent(id)` for `fileContents` bytes and passes via a Map or as separate args.

- [ ] **Step 5: Migrate `StockMovementService` (lines 3301, 3454-3458).**
Replace `Document.findAllByDocumentCode(DocumentCode.REQUISITION_TEMPLATE)` with `documentClient.findByCode('REQUISITION_TEMPLATE')`. Replace `new Document(...).save()` upload with `documentClient.create(...)`.

- [ ] **Step 6: Migrate `InvoiceController` (lines 142, 161).**
Replace `Document.get(params.id)` with `documentClient.fetchById(params.id)`. Update view model.

- [ ] **Step 7: Migrate `ProductController` (8 sites).**
Lines 462, 498, 548, 607, 617, 628, 1118, 1122 — same pattern.

- [ ] **Step 8: Migrate `OrderController` (4 sites).**
Lines 526, 546, 943, 961 — same pattern. Note: line 943 uses `Document.findByName(...)` → `documentClient.findByName(...)` (method added to client in Step 1; endpoint added to `DocumentController` in Task 5 Step 1).

- [ ] **Step 9: Migrate `ShipmentController` (4 sites).**
Lines 816, 820, 835, 926 — same pattern.

- [ ] **Step 10: Migrate `ShipmentWorkflowController:113`.**
`Document.findAllByDocumentTypeInList(...)` → `documentClient.findByTypeIds(...)` (method added to client in Step 1; endpoint added to `DocumentController` in Task 5 Step 1; the caller passes the list of `DocumentType.id` values).

- [ ] **Step 11: Migrate `StockMovementController` (3 sites).**
Lines 502, 505, 507 — `getNonTemplateDocumentTypes` + `Document.get` + `new Document`.

- [ ] **Step 12: Migrate `DocumentUploadController:20` (the 8th caller per spec A21).**
Per scope-creep guardrail, this is the 8th expected caller. Migrate same as others.

- [ ] **Step 13: Migrate `MigrationService:1192`.**
`new Document()` → `documentClient.create(...)`.

- [ ] **Step 14: Migrate the 3 GSP partials.**
- `views/inventoryItem/_actionsCurrentStock.gsp:45` — trace to wrapping controller action (likely `InventoryItemController.showStockCard` or similar). Move the `Document.findAllByDocumentCode(ZEBRA_TEMPLATE)` into the controller action; pass via model; update `<g:render template="..."/>` invocation.
- `views/order/_summary.gsp:242` — trace to `OrderController` action that renders the wrapping page (probably `show` or `edit`). Pre-fetch in the action; pass via model.
- `views/order/_orderDocuments.gsp:2` — same trace, same migration.

- [ ] **Step 15: Convert `DocumentService.groovy`'s entity-facing methods to delegations.**
The methods `getNonTemplateDocumentTypes()` plus any further entity-facing methods identified by Task 1 audit become thin shims (`getAllDocumentsBySupplierOrganization` is NOT in this set — see File Structure note):
```groovy
List<Map> getNonTemplateDocumentTypes() {
    return documentClient.nonTemplateDocumentTypes()
}
// etc.
```
The 20+ file/Excel/PDF utility methods stay as-is, unchanged.

- [ ] **Step 16: Commit.**
```bash
git add grails-app/services/.../DocumentClient.groovy grails-app/conf/spring/resources.groovy \
        grails-app/controllers/ grails-app/services/ grails-app/views/
git commit -m "phase 1: migrate ~20 Grails callers to document-service via DocumentClient.groovy"
```

### Task 9: Add nginx route (§8 Step 9)

**Files:**
- Modify: `docker/nginx/conf.d/app.conf`

- [ ] **Step 1: Insert `/api/documents` block BEFORE existing `/api/` block.**
```nginx
server {
    listen 80;
    access_log /var/log/nginx/reverse-access.log;
    error_log /var/log/nginx/reverse-error.log;

    location /api/documents {
        proxy_pass http://document-service:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Cookie $http_cookie;
    }

    location /api/ {
        proxy_pass http://app:8080/openboxes/api/;
        # ... unchanged ...
    }
    # ... rest unchanged ...
}
```
Ordering is critical per P42: more-specific path must come first in nginx so `/api/documents/foo` and bare `/api/documents` (the multipart upload POST) both match `/api/documents` before `/api/`. The `location` directive intentionally omits the trailing slash and the `proxy_pass` URL intentionally has no path suffix — this makes nginx pass the original request URI verbatim, covering both `POST /api/documents` (collection root) and `GET /api/documents/{id}` (suffix paths). Spring Boot 3.x defaults `useTrailingSlashMatch=false`, so the controller at `@RequestMapping("/api/documents")` only handles the no-slash form; the no-slash nginx prefix matches both forms but only forwards what callers actually send.

- [ ] **Step 2: Reload nginx (no restart needed since config is volume-mounted).**
```bash
sudo docker exec openboxes-nginx nginx -s reload
# Probe path-suffix endpoint
curl -s -o /dev/null -w "GET /api/documents/types/non-template HTTP %{http_code}\n" http://localhost/api/documents/types/non-template
# Probe bare collection root (catches the trailing-slash routing regression specifically)
curl -s -o /dev/null -w "POST /api/documents (multipart) HTTP %{http_code}\n" \
  -X POST -F "file=@/etc/hostname" -F "name=smoke-probe" http://localhost/api/documents
# Expect 401 on both (auth required) — confirms nginx routes both forms to document-service
```

- [ ] **Step 3: Commit.**
```bash
git add docker/nginx/conf.d/app.conf
git commit -m "phase 1: route /api/documents/ to document-service via nginx"
```

### Task 10: Delete Grails Document counterparts (§8 Step 10) — **DEFERRED to Phase X**

> **Status:** Deferred from Phase 1. Pre-dispatch recon (post-Task-9) surfaced 6 architectural questions the spec does not pin down, plus a much larger consumer surface than the spec enumerates (4 domains own `hasMany Document` not 3 — adding **Order** and **Shipment**, both with `all-delete-orphan` cascade; 7 `Document.load(id)` bridge sites added by Task 8b; 15+ GSPs read `parent.documents`; `DocumentService.getAllDocumentsBySupplierOrganization()` joins through `Document`; `DocumentController.groovy` provides live template-rendering surface — Zebra, Invoice, Requisition — that document-service does not yet replicate; top-nav "Documents" menu in `conf/runtime.groovy:453` points at the to-be-deleted controller). The plan's "Conditional — only if Task 8b migration is clean" guardrail applies: 8b's `Document.load(id)` bridges are deliberate technical debt, not a clean migration.
>
> See **Phase X: Document slice decoupling** below for the deferred scope, the 6 architectural questions, and the work units that flow into that phase. The original spec below is preserved as starting material for whoever picks up Phase X.
>
> ---

**Files:**
- Delete: `grails-app/domain/org/pih/warehouse/core/Document.groovy`, `DocumentType.groovy`, `DocumentCode.groovy` (if separate)
- Delete: `grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy`
- Delete: `grails-app/views/document/` (entire directory)
- Modify: 3 domain class imports (`Invoice.groovy`, `ShipmentWorkflow.groovy`, `Product.groovy` per P41)
- Modify: `grails-app/controllers/org/pih/warehouse/UrlMappings.groovy` if it has document mappings (per Task 1 audit)

- [ ] **Step 1: Final consumer-impact grep — confirm no remaining Document.* refs.**
```bash
grep -rn "Document\.\(get\|findBy\|findAll\|list\|count\|create\)\|new Document(" grails-app/ | grep -v "_orderDocuments\|DocumentService\.groovy\|DocumentController\.groovy\|Document.groovy\|DocumentType.groovy\|DocumentClient.groovy"
```
Expect: zero output. If anything matches, the migration in Task 8b was incomplete — fix before deleting.

- [ ] **Step 2: Delete the files.**
```bash
git rm grails-app/domain/org/pih/warehouse/core/Document.groovy
git rm grails-app/domain/org/pih/warehouse/core/DocumentType.groovy
git rm -r grails-app/views/document/
git rm grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy
# If DocumentCode is a separate file:
git rm grails-app/domain/org/pih/warehouse/core/DocumentCode.groovy 2>/dev/null || true
```

- [ ] **Step 3: Remove `import org.pih.warehouse.core.Document` from the 3 domain classes.**
- `grails-app/domain/org/pih/warehouse/invoice/Invoice.groovy` — remove import; if Invoice has `hasMany Document` join-table, that join-table is owned by billing-service per spec §4.3 + so the field declaration changes from `static hasMany = [documents: Document]` to a `List<Map> documents` lookup via `documentClient.findByInvoice(id)` — OR if no callers rely on `invoice.documents` post-Task-8b, just delete the hasMany. Use the audit to decide.
- `grails-app/domain/org/pih/warehouse/shipping/ShipmentWorkflow.groovy` — same pattern
- `grails-app/domain/org/pih/warehouse/product/Product.groovy` — same pattern

- [ ] **Step 4: Remove document-related UrlMappings entries.**
Per Task 1 audit, if any `/document/...` mappings exist in `UrlMappings.groovy`, remove them.

- [ ] **Step 5: Verify Grails still boots.**
```bash
./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain
cd docker && sudo docker-compose up -d --build app && cd ..
for i in {1..30}; do curl -sf http://localhost/openboxes/health && break; sleep 5; done
```
Expect: health endpoint returns UP. If compile errors surface, fix the residual Document refs.

- [ ] **Step 6: Commit.**
```bash
git add -u  # captures the rm + modify
git commit -m "phase 1: delete Grails Document.groovy + DocumentType.groovy + DocumentController.groovy + views/document/"
```

### Task 11: Playwright E2E + JUnit integration tests (§8 Step 11)

**Files:**
- Create: `e2e/tests/document-upload.spec.ts`, `document-download.spec.ts`, `document-list-by-code.spec.ts`, `document-delete.spec.ts`
- Create: `e2e/tests/document-callers-regression.spec.ts`
- Create: `services/document-service/src/test/java/org/openboxes/document/DocumentServiceIntegrationTest.java`

- [ ] **Step 1: Playwright `document-upload.spec.ts`.**
```typescript
import { test, expect } from '@playwright/test';

test('POST /api/documents uploads a multipart file', async ({ request }) => {
  const loginRes = await request.post('/api/login', {
    data: { username: process.env.E2E_USER || 'admin', password: process.env.E2E_PASSWORD || 'password', location: process.env.E2E_LOCATION_ID || '1' },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);

  const upload = await request.post('/api/documents', {
    multipart: {
      file: { name: 'test.txt', mimeType: 'text/plain', buffer: Buffer.from('hello phase 1') },
      name: 'phase-1-test-doc',
    },
  });
  expect(upload.status()).toBe(200);
  const doc = await upload.json();
  expect(doc.id).toBeTruthy();
  expect(doc.name).toBe('phase-1-test-doc');
});
```

- [ ] **Step 2: Playwright `document-download.spec.ts`, `document-list-by-code.spec.ts`, `document-delete.spec.ts`.**
Pattern as above. For list-by-code, first POST a document with a known DocumentType+code; then GET `/api/documents?code=...`; verify it appears. For delete, POST then DELETE then GET 404.

- [ ] **Step 3: Playwright `document-callers-regression.spec.ts`.**
Single spec with multiple `test()` blocks covering the migrated caller flows: DataExport list, Invoice document attachment view (verify InvoiceController.show still works), Product attachment view, Shipment attachment view. Each test logs in, navigates to the GSP-rendered page, asserts 200 + key UI element present.

- [ ] **Step 4: JUnit integration test (document-service side).**
```java
package org.openboxes.document;

import org.junit.jupiter.api.Test;
import org.openboxes.document.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)  // use real MariaDB
@Transactional  // rollback per-test
class DocumentServiceIntegrationTest {
    @Autowired DocumentService docService;

    @Test
    void createAndFetchById() {
        var d = docService.create("test", "test.txt", "text/plain", "hi".getBytes(), null);
        var fetched = docService.findById(d.getId()).orElseThrow();
        assertThat(fetched.getFilename()).isEqualTo("test.txt");
        assertThat(fetched.getFileContents()).isEqualTo("hi".getBytes());
    }
}
```

- [ ] **Step 5: Run all tests.**
```bash
cd services && ./gradlew :document-service:test && cd ..
cd e2e && E2E_LOCATION_ID=1 npm test
```
Expect: all green. The existing 4 Phase 0 Playwright specs also still pass.

- [ ] **Step 6: Commit.**
```bash
git add e2e/tests/document-*.spec.ts services/document-service/src/test/
git commit -m "phase 1: Playwright + JUnit tests for document-service + caller regressions"
```

### Task 13: Done-gate verification (incl. soak) + tag (§8 Steps 12, 13)

**Files:** (none — verification + tag only)

- [ ] **Step 1: Full clean rebuild + boot.**
```bash
./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain
cd services && ./gradlew :document-service:bootJar && cd ..
cd docker
sudo docker-compose down
sudo docker-compose up --build -d
for i in {1..60}; do
  curl -sf http://localhost/openboxes/health && \
  curl -sf http://localhost:8081/actuator/health 2>/dev/null && break
  sleep 5
done
cd ..
```

- [ ] **Step 2: Verify spec §6 Phase 1 done-gate items.**
  - [ ] document-service serves `Document + DocumentType` (verified by Task 11 tests)
  - [ ] All ~20 Grails callers migrated to HTTP (verified by Task 11 caller-regression spec)
  - [ ] Grails `Document.groovy`, `DocumentType.groovy`, `DocumentController.groovy`, `views/document/` deleted (verified by `git ls-files`)
  - [ ] Grails `DocumentService.groovy` remains (file-utility methods still serve their 20+ callers) — by design
  - [ ] All Phase 0 + Phase 1 Playwright tests green (~10 tests total)
  - [ ] document-service `/v3/api-docs` returns valid OpenAPI spec
```bash
curl -s http://localhost:8081/v3/api-docs | python3 -m json.tool | head -20
```

- [ ] **Step 3: 1-hour soak.**
Run the full E2E suite twice with a 10-minute pause in between; in parallel, manually exercise the Document UI flows (upload via Invoice page, view in Product page, download a Data Export) for ~15 minutes. Monitor `sudo docker logs openboxes-document-service` for unexpected errors. Verify no `Document` or `DocumentType` errors in `sudo docker logs openboxes-app`.

- [ ] **Step 4: Tag and push.**
```bash
git tag phase-1-document
git push origin main
git push origin phase-1-document
```

- [ ] **Step 5: Update Phase 0 retrospective with Phase 1 followup notes.**
Add a short addendum section to `docs/retrospectives/2026-05-26-phase-0-foundations-retrospective.md` (or create a new `docs/retrospectives/2026-05-27-phase-1-document-retrospective.md` per the natural cadence) capturing:
- What worked
- New gotchas surfaced (e.g., DocumentService mixed-purpose discovered at TWP-time, caller graph 2-3x spec estimate, GSP-direct-static-call pattern)
- Forward to Phase 2 (identity-service): note the JWT-validation-on-Spring-Boot pattern established here is reusable

## Tasks NOT in this plan

(Inherited verbatim from spec §7.6, which lists Phase 0 exclusions, and from the per-slice template's Step 10 conditional ("delete only artifacts no remaining Grails code references"). Phase 1 specifically does NOT:)

- **Delete `DocumentService.groovy`.** It contains ~20+ file/Excel/PDF utility methods unrelated to Document entity. Stays in Grails until those callers migrate in their own slices (most by Phase 8–11; some may live until Phase 12 cleanup).
- **Delete Grails-side Document.groovy / DocumentType.groovy / DocumentController.groovy / views/document/.** Originally planned as Task 10; **deferred to Phase X** (see below) because the actual consumer surface is much larger than the spec enumerates and 6 architectural questions are unresolved.
- **Migrate billing-owned join tables.** `invoice_document` join-table changesets stay in `grails-app/migrations/`; billing-service owns them at Phase 10 per spec §4.3.
- **Add OpenAPI client generation for React.** No React Document API code exists; springdoc-openapi serves machine-readable docs but no auto-generated client is added in Phase 1.
- **Add saga / outbox / event subscriber infrastructure.** Deferred to Phase 7 per spec §4.5; Document writes from Grails use sync HTTP with cookie forwarding instead (acceptable because Document write traffic is admin-scale).
- **Add Sentry / metrics / dashboards.** Spring Boot Actuator alone per spec §11 known issues.
- **Migrate the 3 GSPs to React.** Out of Phase 1 scope; the partials retain GSP form, just with controller-fetched models.
- **Update upstream-published `ghcr.io/openboxes/openboxes:latest` image.** No external consumers; local `docker-compose up --build` covers the development workflow.

## Phase 1 hybrid state (intentional)

Phase 1's goal — per spec §8 — was to extract a working HTTP-routed Document slice. That has landed (Tasks 1–9 + I1/I2 + T4-I1). It deliberately stops short of fully decoupling Grails from the `document` table. The codebase ends Phase 1 in a **hybrid state**:

- **document-service is authoritative for Document CRUD.** All write paths (upload, delete) flow through `DocumentClient` → nginx `/api/documents` → Spring Boot. Java-side JWT validation owns the security envelope.
- **Grails-side `Document.groovy` still exists** as a leaf domain class. It is queried only via two patterns: (a) `Document.load(id)` bridges in 7 sites that feed Grails-managed join-table mutators (`parent.addToDocuments` / `removeFromDocuments`), and (b) GSP-side iteration of `parent.documents` collections backed by GORM. No `Document.get` / `Document.findBy*` / `new Document(...)` patterns remain — those were migrated in Task 8b.
- **`*_document` join tables remain Grails-owned** (`invoice_document`, `order_document`, `product_document`, `shipment_document`, `shipment_workflow_document`). All are empty in dev DB; production carries pre-existing rows.
- **Grails Hibernate L2 cache is OFF** on `Document.groovy` (T4-I1 closed) so write-from-document-service then read-from-Grails returns fresh rows.
- **`DocumentController.groovy` (Grails)** still serves template-rendering URLs (`renderInvoiceTemplate`, `renderZebraTemplate`, `buildZebraTemplate`, `printZebraTemplate`, `exportZebraTemplate`, `download`, `preview`) referenced by 28+ GSPs. These do real work (Word/Excel/Zebra output) not yet ported to document-service.

This is the conventional strangler-fig stopping point: the new service owns the slice; the old code is reduced to a thin bridging surface that can be deleted as a future, independent unit of work.

## Phase X: Document slice decoupling (deferred from Phase 1)

**Trigger to dispatch:** This phase should run once the following are answered (likely as part of a focused brainstorming + design-spec cycle):

1. **Join-table ownership.** Keep `invoice_document` / `order_document` / `product_document` / `shipment_document` / `shipment_workflow_document` Grails-side with non-GORM accessors, OR migrate them into document-service's schema with new `?parentType=&parentId=` query API, OR drop them and reverse the link direction (FK from the document side).
2. **`findByParent` API surface.** If parent-link queries move to document-service, what does `DocumentController.java` expose? `?invoiceId=`, `?orderId=`, `?productId=`, `?shipmentId=`, `?shipmentWorkflowId=` all need to be designed and tested.
3. **Template-rendering surface.** `DocumentController.groovy` actions `renderInvoiceTemplate`, `renderZebraTemplate`, `buildZebraTemplate`, `printZebraTemplate`, `exportZebraTemplate`, `renderRequisitionTemplate` do server-side Word/Excel/Zebra output. Port to document-service first, OR retain as a `DocumentTemplateController` (renamed, CRUD-stripped), OR split per template type.
4. **`getAllDocumentsBySupplierOrganization()`** in `DocumentService.groovy:1606` is an Order-rooted Criteria query that joins through `Document`. Either ports to a new document-service endpoint (`?supplierOrgId=`), or stays Grails-side IFF the Document domain class survives this phase (it won't, by definition).
5. **Top-nav "Documents" menu** (`conf/runtime.groovy:453` → `/document/list`). Stub Grails page, redirect to a future React page, or remove from menu config.
6. **DocumentType domain ownership.** `DocumentType.groovy` is currently a Grails domain used by GSPs comparing `documentInstance?.documentType?.documentCode`. Migrate to document-service (and add a `findByCode` style API), keep Grails-side (and accept the duplication with the Java entity), or split (Java owns persistence, Grails has a read-only enum/lookup).

**Work units that flow into Phase X:**

- The 8 `Document.load(id)` bridge sites (ProductController:467,505,538; InvoiceController:168; ShipmentController:930; OrderController:561; DocumentUploadController:41; StockMovementService:3465) — only 2 (ProductController:463, StockMovementService:3458) carry explicit `// TODO ... Phase 2+` markers; the others have adjacent context-only comments. A marker-parity pass should bring all 8 to parity at Phase X dispatch time.
- The 15+ GSPs that read `parent.documents` directly (enumerated in pre-dispatch recon for Task 10).
- `Invoice.getOrderDocuments()` cross-domain Document iteration.
- `Product.getImages()` filtering on `documents`.
- All deferred follow-ups whose Target column points at "Phase 2" or later — re-classify under Phase X if they touch the Document slice.
- The original Task 10 spec at this plan's lines `1279-1334` is preserved as starting material; do NOT take it at face value (it under-counts the consumer surface).

**Owner:** TBD. Likely paired with the parent-entity slice extractions (Phase 8–11 Order/Shipment/Invoice/Product slices), or a dedicated mini-phase.

## Known issues inherited from spec

(From spec §11.)

- **Java 8 EOL on Grails container.** Stays until Phase 12.
- **Gradle 4.10.3 on Grails container.** Same.
- **Liquibase `LiquibaseUtil` versioning lives until Grails dies.** document-service uses standard Spring Boot Liquibase; LiquibaseUtil keeps owning Grails-side changelog discovery.
- **`SupportButton.jsx` calls HelpScout directly with raw axios.** Out of scope; third-party API.
- **Webpack continues to write GSPs through Phase 11.** Cosmetic.
- **No observability / metrics infrastructure called out.** Out of scope. Spring Boot Actuator on document-service is enough.
- **No multi-tenant / external OIDC consideration.** HMAC JWT fit-for-purpose.
- **Slice phase ordering after Phase 2 is provisional.** Plan's Phase-1-then-Phase-2 ordering matches spec; later phases may re-order based on Phase 1 experience.
- **Cross-context atomic-write coverage is policy-based, not exhaustive.** Task 1 audit applies the §4.3 policy to any new findings.
