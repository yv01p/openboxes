# Phase 6 (core) — Inventory Service Slice — Design Spec

**Date**: 2026-06-03
**Parent design**: `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` §6 row 6 (Inventory slice, "largest single-service extraction"), §4.3 (entity ownership), §8 (per-slice template)
**Predecessor retrospectives**: Phase 3 location (`docs/retrospectives/2026-05-28-phase-3-location-retrospective.md`, the read-only shape), Phase 5 catalog (`docs/retrospectives/2026-05-30-phase-5-catalog-retrospective.md`), Phase 5.5 catalog-deferred (`docs/retrospectives/2026-06-02-phase-5.5-catalog-deferred-retrospective.md`)
**Codified process inputs**: `docs/process/synthetic-payload-blind-spot.md` (RC-43/44/45 — real-payload ground-truth, cutover-is-verification, empty-DB-hides-write-paths), `docs/process/sdd-reviewer-checklist.md` (RC-1 CHAR(38)/TINYINT, RC-2 `@ElementCollection` inner-column names, RC-56 no-`@EntityGraph`-on-`Pageable`-collection), `docs/process/plan-ordering-rules.md` (Rule-3 nginx prefix-vs-Grails-sub-route), `docs/process/dev-env-setup.md` (RC-55 Temurin-8/JDK-21 split)
**Tag**: `phase-6-inventory`
**Carry-forwards landed**: RC-16 (relocate the working, live-consumed `/api/facilities/$facilityId/products/classifications` read to inventory-service — a behavior-preserving migration, not a bug fix; see FD#5). **Carry-forwards explicitly deferred**: RC-13 remainder (CategoryApiController deletion / `runtime.groovy:757` fillRate categoryApi dependency).

---

## 1. TL;DR

Phase 6 is decomposed (per the established Phase 5 → 5.5 pattern). This spec covers the **Phase 6 core**: stand up `inventory-service` — the **6th** Spring Boot 3.3.5 / Java 21 service — as a **pure read-only** slice (Phase 3 location-service shape), and have it **own the foundational inventory tables** so the bounded context exists and Phases 7/8 can build their saga writes on it.

`inventory-service` ports **8 foundational entities** to JPA and takes Liquibase shadow-changelog ownership of their tables (`Inventory`, `InventoryItem`, `InventoryLevel`, `ProductAvailability`, `Transaction`, `TransactionEntry`, `TransactionType`, `TransactionSource`), proving every mapping against the live schema via `ddl-auto=validate`. **`ProductAvailability` is owned-read-now / refresh-deferred** — inventory-service maps and may read the `product_availability` table; the 1386-LOC Grails `ProductAvailabilityService` (the sole writer) stays in Grails and keeps refreshing it (transition). This resolves the Phase-5 deferral of `ProductAvailability` to Phase 6.

The HTTP surface is **consumed-only (ruthless YAGNI)**: empirical verification found the foundational inventory entities have essentially **no React GET callers** (the only React inventory reads — `InventoryItem`, `ProductAvailability` — are served by Grails' cross-context `ProductApiController`, which stays). So inventory-service exposes GET endpoints **only where a consumer exists**: the **RC-16** classification endpoint (the real bite), plus any T1-confirmed React read (candidate: inventory-transactions-summary). No speculative entity GET endpoints.

**RC-16 lands in core** as a **behavior-preserving migration** of a *working, live-consumed* endpoint — it is **not** a bug fix (the earlier "500" was the invalid-facility guard), and it is consumed today by the React CycleCount product-classification filter. It relocates the classification query to inventory-service: `InventoryLevel.abcClass` is read locally; `facility → inventory` resolves locally via the `Inventory.warehouse` FK; the all-products distinct-`abcClass` set comes from a **new tiny read-only catalog-service endpoint** (owner-exists → HTTP, per parent §4.3). The existing Grails integration tests are ported as the behavior contract. This deletes the Grails `ProductClassificationApiController` + `ProductClassificationService` — a real strangler bite.

All inventory **write** paths (record stock, adjustments, transactions, cycle count, transfers, bulk import, product merge, availability refresh) **stay on Grails** and migrate in Phase 6.5+. Both parent-design restructures (ProductMergeService move; InventoryService bulk-import move) are **deferred to 6.5**. Tag `phase-6-inventory` on `main`.

## 2. Done state (one paragraph)

`inventory-service` runs in compose as the **9th** container at port **8086** (`expose:` only, mirroring services 8081–8085); validates `obx_token` cookies via the shared `jwt-auth-common` starter (no hand-copied JWT triple); owns 8 inventory tables via per-table Liquibase shadow changelogs (`tableExists` precondition + empty body — existing data preserved) and proves all 8 JPA mappings with `spring.jpa.hibernate.ddl-auto=validate` against the live schema; serves the **RC-16** classification endpoint (`GET /api/facilities/{facilityId}/products/classifications`) by unioning local distinct `InventoryLevel.abcClass` with distinct `Product.abcClass` fetched from a new read-only catalog-service endpoint, with `facility → inventory` resolved locally; serves any additional T1-confirmed consumed GET (candidate: inventory-transactions-summary); exposes **no** speculative entity GET endpoints. Grails `ProductClassificationApiController` + `ProductClassificationService` (+ test spec) deleted; catalog-service gains one new read-only `abcClasses` GET. Flat FK-only DTOs (no nested inflation, no cross-service name resolution — accepted degradation per Phase 5 RC-48); no `@EntityGraph`/`JOIN FETCH` on any `Pageable` collection (RC-56). All Grails consumers of inventory tables (`ProductApiController` productAvailability/productSummary/productAvailabilityAndDemand/availableItems, `StockMovementService`, the `fillRate`/`IndicatorDataService` dashboard reads) keep reading via direct-JDBC against the shared DB during transition — not rewired in core — so Hibernate-5 Grails writes and Hibernate-6 inventory-service reads coexist (parent A15). nginx routes only the specific consumed GET paths to inventory-service (insertion-order convention, `include /etc/nginx/conf.d/proxy_params`, Rule-3 prefix-vs-sub-route audit on the facility-scoped path); everything else falls through to the Grails `/api/` catch-all → non-breaking. `InventoryServiceIntegrationTest` (TestContainers, `ddl-auto=validate`, seeded reads incl. the RC-16 round-trip); real-payload ground-truth + seeded read-through e2e through nginx for the RC-16 endpoint; done-gate: `nginx -t` + reload, real-JWT curl-through-nginx 2xx on each new GET, Playwright re-run, Temurin-8 Grails build + JDK-21 services build (RC-55); tagged `phase-6-inventory` on `main`; CI green.

## 3. Forced design decisions

### FD#1 — Posture: pure read-only (Phase 3 location-service shape)

Every migrated surface is GET-only. **All** inventory write paths stay on Grails and migrate in 6.5+: record stock, stock adjustments, transactions, cycle count, local/stock transfers, bulk CSV import, product merge, and `product_availability` refresh. No outbox/saga in Phase 6 (parent §6 row 6: "none yet"); Phase 7 introduces saga infrastructure. inventory-service has exactly one outbound HTTP dependency in core (catalog-service, for RC-16, read-only).

**Rationale**: read-only is the largest non-breaking step for the biggest context; it stands the service up, transfers table ownership, and unblocks Phase 7/8 without introducing any cross-service write pattern. Mirrors Phase 3 exactly.

### FD#2 — Entity scope: 8 foundational IN (own tables); the rest DEFER

**IN — ported to JPA + Liquibase shadow ownership + `ddl-auto=validate` (8):**

| Entity | Package | Notes |
|---|---|---|
| `Inventory` | `inventory/` | `belongsTo warehouse: Location` (`:19,26`); `hasMany configuredProducts: InventoryLevel` (`:27`) — collection NOT inflated (flat DTO) |
| `InventoryItem` | `inventory/` | lot-level; FK Product (catalog); heavily related; Phase 7 saga target (`OrderReceivedEvent` → create InventoryItem) |
| `InventoryLevel` | `inventory/` | FK Inventory + Product + bin Locations; has `abcClass` column (`:72`) — local source for RC-16 |
| `ProductAvailability` | `product/` | derived table; **own-read-now / defer-refresh** per FD#4; resolves the Phase-5 deferral |
| `Transaction` | `inventory/` | FK TransactionType + TransactionSource + Inventory + Location; `hasMany transactionEntries` (`:119`) — collection NOT inflated |
| `TransactionEntry` | `inventory/` | FK Transaction + InventoryItem + binLocation |
| `TransactionType` | `inventory/` | reference data (real domain class/table) |
| `TransactionSource` | `inventory/` | reference data (real domain class/table) |

These 8 are exactly what Phases 7/8 build on (InventoryItem creation via saga; Transaction/TransactionEntry cross-context writes). Owning them now proves the mappings and establishes the additive-only schema constraint early.

**DEFER to Phase 6.5 / 6.x** (no current read consumer; writers stay Grails; not needed for Phase 7/8 saga writes):

- **CycleCount family** (`CycleCount`, `CycleCountCandidate`, `CycleCountDetails`, `CycleCountItem`, `CycleCountRequest`, `CycleCountSummary`, `PendingCycleCountRequest`) + `CycleCountApiController` + React CycleCount writes.
- **Snapshot / count / audit read-models**: `InventorySnapshot`, `InventoryItemSnapshot`, `InventoryCount`, `InventoryAuditDetails`, `InventoryAuditRollup`, `InventoryAuditSummary` (computed/audit; no React read; `InventorySnapshot` is read by the Grails fillRate path which stays direct-JDBC). *T1 may pull one in only if it finds a consumed read or a hard Phase-7/8 dependency; default DEFER.*
- **`LocalTransfer`, `OutboundStockMovement`, `OutboundStockMovementListItem`, `Requirement`** — transfer/outbound/audit-rollup read-models over deferred concepts.

**No entity exists (parent-design correction — see Verified assumption V3):** `StockMovement`, `StockMovementItem`, `StockTransfer`, `StockTransferItem`, `Replenishment` have **no Grails domain class or table** — they are service-layer orchestration concepts over Requisition/Shipment/Order. Parent design §4.3 row 6 / §6 list them as entities whose "Liquibase ownership transfers at Phase 6"; empirically there is nothing to transfer. Their *services* are already parent-deferred (ReplenishmentService → Phase 7; StockTransferService + StockMovementService writes → Phase 8). This spec records the correction; no core-scope impact.

T1 audit re-verifies the IN/DEFER membership before T2 (Phase 5 pattern).

### FD#3 — Own tables now, expose GET consumed-only (YAGNI)

All 8 IN entities are **owned** (JPA entity + per-table shadow changelog + `ddl-auto=validate`) regardless of read surface — table ownership is the foundational value and the additive-only-constraint anchor. But GET **endpoints** are built **only where a consumer exists**:

- **RC-16** classification endpoint (FD#5) — the one definite consumed GET.
- **T1-confirmed React reads** — candidate: `/api/reports/inventory-transactions-summary` (React `useInventoryTransactionsTab.jsx:72` calls it; `InventoryTransactionSummaryApiController` is GET-only). T1 confirms the path/owner; if pure-inventory and consumed, migrate + delete the Grails controller.
- **No speculative endpoints** for `Inventory`/`InventoryLevel`/`Transaction`/`TransactionEntry`/`TransactionType`/`TransactionSource` — verification found zero React GET callers. Building them would be YAGNI.

**Rationale**: the only React reads that touch inventory data (`InventoryItem`, `ProductAvailability`) are served by Grails' cross-context `ProductApiController` (Product + forecasting + inventory), which Phase 5 deliberately kept in Grails and which cannot move to a read-only inventory-service without the forecasting/Product context. So "extract the read model" means **own the tables**, not **migrate React GET endpoints**.

### FD#4 — ProductAvailability: own-read-now, defer-refresh

inventory-service **owns** `product_availability` (JPA entity + shadow changelog + `ddl-auto=validate`) and may read it. The Grails `ProductAvailabilityService` (1386 LOC — the **sole** writer: raw SQL DELETE/INSERT/`ON DUPLICATE KEY UPDATE` + merge saves) and its refresh triggers (`RefreshProductAvailabilityEventService`, `RefreshProductAvailabilityJob`) **stay in Grails** and keep writing the table during transition. Refresh logic moves to inventory-service in 6.5+ (with the ProductMergeService restructure, since the merge UPDATEs to `product_availability` live in the same service). No GET endpoint is exposed for `ProductAvailability` in core (its React consumer is the cross-context `ProductApiController.productAvailabilityAndDemand`, which stays Grails per FD#3).

**Rationale**: a single, well-isolated writer in Grails + a read-owning entity in inventory-service is the minimal way to resolve the Phase-5 deferral while keeping core read-only. Two Hibernate clients on one table is the established coexistence pattern (A15).

### FD#5 — RC-16 relocation to inventory-service (carry-forward landed)

The classification feature is a **working, live-consumed** read (React CycleCount filter → `src/js/api/services/ProductClassificationApi.js:5`; `ProductClassificationApiController` → `ProductClassificationService.list(facilityId)`) — **not** a bug: it returns 200 with the correct union for a valid facility (`ProductClassificationApiCRUDSpec.groovy:42-52`) and 500 only for an invalid facility by design (`:54-58`; the bare `/api/productClassifications` path probed at Phase 5 T12 hit that guard). It is migrated to inventory-service as a **behavior-preserving** read:

1. `facility → inventory`: resolved **locally** — `Inventory` has `warehouse: Location` FK (`Inventory.groovy:19,26`), so inventory-service queries `Inventory WHERE warehouse_id = :facilityId` directly (no location-service HTTP needed).
2. `InventoryLevel.abcClass` distinct (for that inventory): read **locally** (inventory-service owns InventoryLevel).
3. `Product.abcClass` distinct (across all products): fetched via a **new read-only catalog-service endpoint** that returns the distinct `abcClass` set (owner-exists → HTTP, per parent §4.3 policy). This is inventory-service's first outbound HTTP read client (catalog-service) and a small read-only addition to the already-shipped catalog-service.
4. The two sets are unioned, sorted, and returned — same functional result as the Grails service.

**Deletes** Grails `ProductClassificationApiController.groovy` + `ProductClassificationService.groovy` + `ProductClassificationDto.groovy` + the `UrlMappings.groovy:139-140` mapping + the `ProductClassificationServiceSpec` unit test. The two integration specs (`ProductClassificationApiCRUDSpec`, `ProductClassificationApiListFiltersOutEmptySpec`) are **ported** to inventory-service as the behavior contract — they pin the global-Product ∪ facility-scoped-InventoryLevel union, dedup, sort, and the invalid-facility error. Verified: the service has no caller other than its controller, so this is a clean delete.

`abcClass` stays a **column on `Product`** (read via catalog) — NOT the "abcClass → its own entity" refactor (that remains deferred per the Phase 5 service-doc note). The real mapped path is `/api/facilities/$facilityId/products/classifications` (`UrlMappings.groovy:139`); nginx routes that path (with Rule-3 prefix/sub-route audit — it nests under `/api/facilities/`); T1 confirms whether a bare alias also resolves and routes it too.

### FD#6 — Flat FK-only DTOs (carry forward Phase 3/5 FD#3)

No nested entity inflation; no cross-service name resolution. Cross-context references (`Product`, `Location`) are carried as flat FK ids; collections (`Transaction.transactionEntries`, `Inventory.configuredProducts`) are NOT inflated. Consumers fetch related entities by id. Accepted degradation: any denormalized cross-service *names* are not resolved (Phase 5 RC-48). For core this is near-moot — the only response payload is RC-16's classification string set.

### FD#7 — No JPA inheritance (verified); standard flat `@Entity` each; schema-divergence discipline

Verification found **zero** `extends <DomainClass>` / `tablePerHierarchy` / discriminator usage across all 24 inventory domain classes — so the SINGLE_TABLE nullability rule (Phase 4 RC-1 inheritance variant) does **not** apply. Each entity is a direct flat `@Entity`. The CHAR(38)-id / TINYINT-boolean divergence (RC-1) and the `@ElementCollection` inner-column-name trap (RC-2) **do** apply to inventory tables: per the codified `sdd-reviewer-checklist.md`, run `SHOW COLUMNS` / `DESCRIBE` against the live table **before** writing each entity/`@ElementCollection` mapping. `ddl-auto=validate` is the backstop (catches type/column divergence at startup; silently passes nullability — so nullability is matched to the live DB, not the Grails domain).

### FD#8 — Audit fields are read-only columns in core (no AuditorAware)

Core entities carry `dateCreated`/`lastUpdated` (verified on Inventory/Item/Level) and some carry `createdBy`/`updatedBy` User FKs. Because core is **read-only**, these are just readable columns — **no** `@EntityListeners`/`AuditorAware` write-side machinery is needed (unlike Phase 5.5's write-cluster FD#8). The `JwtAuditorAware` pattern is wired only when inventory writes land (6.5+/7+).

### FD#9 — Service template: shared starter, port 8086, expose-only, nginx conventions

`jwt-auth-common` starter consumed via `implementation project(':jwt-auth-common')` (verified pattern; no 6th hand-copy). Spring Boot 3.3.5 / Java 21 (`services/build.gradle:2,22`). Port **8086** (`expose:` only; 8081–8085 taken). docker-compose entry across `docker-compose-base.yml` + `docker/docker-compose.yml` (both files — Phase 5 RC-3 plan defect). nginx routes added at end of the location stack (insertion-order, Phase 4.1 RC-10), `include /etc/nginx/conf.d/proxy_params` (RC-11), Rule-3 prefix-vs-Grails-sub-route audit for every new `location` block. CI workflow (`.github/workflows/e2e-tests.yml`) builds the inventory-service jar + probes `/actuator/health` — **same commit as, or strictly before, the nginx/compose change** (Phase 4.1 RC-2 / plan-ordering Rule 1).

### FD#10 — Cross-context read policy + OSIV/lazy disciplines

Grails consumers of inventory tables keep **direct-JDBC** during transition (parent policy) — `ProductApiController` (productAvailability/productSummary/productAvailabilityAndDemand/availableItems/getInventoryItem), `StockMovementService`, and the `fillRate`/`IndicatorDataService` dashboard reads are **not** rewired to HTTP in core. This is what keeps the bite small and the phase non-breaking. inventory-service's **only** outbound HTTP read is catalog-service (RC-16). Flat DTOs avoid `LazyInitializationException`; **no `@EntityGraph`/`JOIN FETCH` on any `Pageable` collection** (RC-56 — second-query batch-load if a paginated collection read is ever needed; none is, in core).

### FD#11 — RC-13 stays deferred to 6.5 (separate dependency)

RC-13's remaining blocker is `runtime.groovy:757 endpoint = "/categoryApi/list"` inside the `fillRate{}` dashboard-widget config (a **Category** dependency) + the `CategoryApiController.groovy` file. Verified: this is a **different** cross-context dependency than RC-16 (productClassifications). It is **not** resolved by this phase and lands in 6.5 when the fillRate categoryApi consumer is relocated/removed.

## 4. Architecture

```
                ┌──────────────────────────────────────────────────────────────┐
                │  inventory-service (NEW, port 8086, expose: only)             │
                │  ┌────────────────────────────────────────────────────────┐   │
                │  │ Controllers (consumed-only per FD#3):                    │   │
                │  │ - ProductClassificationController (RC-16)                │   │
                │  │   GET /api/facilities/{facilityId}/products/classifications│ │
                │  │ - (T1) InventoryTransactionSummaryController? if consumed │   │
                │  ├────────────────────────────────────────────────────────┤   │
                │  │ Services:                                                │   │
                │  │ - ProductClassificationService (ported; local Inventory  │   │
                │  │   + InventoryLevel reads; catalog HTTP for Product abc)  │   │
                │  │ - CatalogReadClient (RestClient → catalog-service)       │   │
                │  ├────────────────────────────────────────────────────────┤   │
                │  │ JPA Entities (FLAT per FD#6; 8 owned tables):            │   │
                │  │  Inventory · InventoryItem · InventoryLevel              │   │
                │  │  ProductAvailability (read; refresh stays Grails FD#4)   │   │
                │  │  Transaction · TransactionEntry                          │   │
                │  │  TransactionType · TransactionSource                     │   │
                │  ├────────────────────────────────────────────────────────┤   │
                │  │ Security: jwt-auth-common starter (FD#9)                 │   │
                │  │ Liquibase: 8 shadow changelogs (tableExists + empty body)│   │
                │  │ spring.jpa.hibernate.ddl-auto=validate                   │   │
                │  └────────────────────────────────────────────────────────┘   │
                └──────────────────────────────────────────────────────────────┘
                          │ JPA reads (8 tables, R/O)        │ HTTP GET (RC-16 only)
                          ▼                                  ▼
                  ──────── SHARED MariaDB ──────────   catalog-service :8085
                          ▲   (NEW read-only GET: distinct abcClasses)
                          │ Grails continues to WRITE (transition, Hibernate 5):
   openboxes-app (Grails)─┤   - ProductAvailabilityService (sole product_availability writer) — refresh→6.5
       │ Stays alive:     │   - record stock / adjustments / transactions / cycle count
       │ - ProductApiController (productAvailability, productSummary,
       │   productAvailabilityAndDemand, availableItems, getInventoryItem)
       │   — cross-context; direct-JDBC; NOT rewired (FD#10)
       │ - StockMovementService (reads InventoryItem; direct-JDBC)
       │ - fillRate / IndicatorDataService (reads ProductAvailability +
       │   InventorySnapshot; direct-JDBC; RC-13 categoryApi dep → 6.5)
       │ - InventoryApiController (importCsv WRITE → bulk-import restructure 6.5)
       │ DELETED this phase:
       │ - ProductClassificationApiController + ProductClassificationService (RC-16)
       │
   React ── only consumed GET paths repoint to inventory-service via nginx;
            InventoryItem/ProductAvailability reads stay on Grails ProductApiController
```

## 5. Tasks (provisional; thorough-writing-plans finalizes)

| # | Task | Notes |
|---|------|-------|
| **T1** | **Empirical audit**: finalize FD#2 IN/DEFER (confirm snapshot/count/audit default-DEFER; confirm no consumed read for them); finalize FD#3 consumed-GET surface (confirm/deny the inventory-transactions-summary React consumer + its owning controller + exact path; confirm zero other React inventory GETs via `grep src/js/**/*.{js,jsx,ts,tsx}` for inline `/api/*`); confirm RC-16 exact URL path(s) + Rule-3 sub-route audit under `/api/facilities/`; per-controller delete/keep matrix (ProductClassification delete; InventoryApi stays; InventoryLevelApi / InventoryTransactionSummaryApi decide); cross-context atomic-write audit per parent §8 Step 1. **User approval gate before T2.** | Output: final entity table, consumed-GET surface, RC-16 path + nginx plan, per-controller matrix |
| **T2** | Spring Boot module skeleton `services/inventory-service/` (Gradle sub-module, main class, `jwt-auth-common` dep, `application.yml` with `ddl-auto=validate`) | Phase 5 T2 pattern; both compose files; jammy/non-root Dockerfile convention (RC-4) |
| **T3** | 8 JPA entities + 8 Liquibase shadow changelogs (one per table). **`DESCRIBE`/`SHOW COLUMNS` each table first** (RC-1 CHAR(38)/TINYINT, RC-2 `@ElementCollection`, nullability-to-live-DB) | Heaviest mapping task; `ddl-auto=validate` proves each |
| **T4** | DTOs (flat FK-only per FD#6) for the consumed surface only (RC-16 classification DTO; T1-confirmed reads) | Minimal — core has near-zero response payloads beyond RC-16 |
| **T5** | RC-16: port `ProductClassificationService` (local Inventory + InventoryLevel reads; `CatalogReadClient` for Product distinct abcClass) + controller; **catalog-service new read-only distinct-`abcClasses` GET** | Two-service change (inventory + catalog); both read-only |
| **T6** | nginx routes (RC-16 path + Rule-3 audit; any T1 consumed GET) + docker-compose entry + healthcheck; CI workflow same-commit-or-prior (Rule 1) | inventory-service = 9th container |
| **T7** | Delete Grails `ProductClassificationApiController` + `ProductClassificationService` + `ProductClassificationDto` + `UrlMappings.groovy:139-140` mapping + unit spec; **port** the 2 integration specs to inventory-service as the behavior contract; grep-verify no dangling refs | Real strangler bite |
| **T8** | TestContainers `InventoryServiceIntegrationTest` (`ddl-auto=validate`; seed.sql for 8 entities; RC-16 classification round-trip incl. union semantics) | Mapping-validation is the core proof |
| **T9** | Real-payload ground-truth for RC-16 (capture the real CycleCount-filter request/response shape `{data:[{name}]}`; RC-43) + seeded read-through e2e through nginx — seed a valid facility + `abcClass` rows on **both** Product (global) and InventoryLevel (facility-scoped, incl. a second facility's row that must be excluded) and assert the real payload (RC-45); Playwright spec(s) for the consumed GET surface | Empty-DB self-skip caveat applies — seeded round-trip mandatory; this is a **live** endpoint, regressions break the CycleCount filter |
| **T10** | Done-gate: `nginx -t` + reload; real-JWT curl-through-nginx 2xx on each new GET; Playwright re-run; Temurin-8 Grails build + JDK-21 services build (RC-55); 9-route nginx smoke | Phase 5 T12/T14 pattern |
| **T11** | Retro: A–F triage; Phase 6.5 forward pointer (deferred entities + both restructures + write clusters + RC-13 + ProductAvailability refresh move); tag `phase-6-inventory` | Per Phase 5 T13 pattern |

**Process discipline carry-forward**: per-task STOP gate; light-SDD direct-apply calibration (plan-verbatim AND no business logic AND <20 LOC); thorough-brainstorming → thorough-writing-plans → CDR/CIR rounds; real-payload ground-truth before any cutover-style task (RC-43/44); follow-up commits not amends; bundle push at done-gate.

**Estimated pace** (one developer): core is read-only with a tiny consumed surface but 8 entity mappings against a large, mature schema (the `DESCRIBE`-first discipline dominates). ~Phase 5 size on the mapping axis, far smaller on the write/cutover axis. The heavy inventory write surface is what makes the *parent* §6 "4–6 weeks" estimate — that lives in 6.5+, not here.

## 6. Known issues / accepted as out of scope

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

## 7. Risks

- **`ddl-auto=validate` against 8 mature inventory tables surfaces schema divergence** (CHAR(38)/TINYINT RC-1, `@ElementCollection` column names RC-2, nullability drift). *Mitigation*: `DESCRIBE`/`SHOW COLUMNS` each table before writing the mapping (codified discipline); validate is the startup backstop; budget T3 accordingly.
- **InventoryItem / Transaction are huge, heavily-related entities** — lazy/pagination traps (RC-56). *Mitigation*: flat DTOs + no `@EntityGraph` on `Pageable`; and the actual consumed surface (RC-16) doesn't read these collections, so the trap barely arises in core.
- **Small-deletion-bite optics** — core deletes one controller+service (ProductClassification). *Mitigation*: name the 6.5 deletions up front; the phase's value is standing up inventory-service + owning 8 tables + resolving ProductAvailability + fixing RC-16 + unblocking 7/8, not LOC removed (same framing as Phase 3/5).
- **RC-16 touches an already-shipped service** (new catalog-service GET). *Mitigation*: read-only, additive, single small endpoint; covered by catalog integration tests + the RC-16 round-trip e2e.
- **RC-16 is a behavior-preserving migration of a live endpoint** — the classification endpoint works today and is consumed by the React CycleCount filter, so the cutover must preserve the exact response shape `{data:[{name}]}`, the union/facility-scoping semantics, and the invalid-facility error contract; a divergence silently breaks the filter (the RC-43/44/45 trap, in its lower-risk read form). *Mitigation*: port the existing integration tests as the contract; real-payload ground-truth (RC-43) + seeded round-trip asserting the union and facility-scoping (RC-45).
- **Two Hibernate clients on `product_availability`** (Grails writes, inventory-service reads). *Mitigation*: established coexistence (A15); core never writes it; additive-only schema constraint holds.
- **Empty dev DB hides read correctness** (RC-45). *Mitigation*: seeded round-trip e2e mandatory; don't accept skip-heavy green.

## 8. Verified assumptions

Enumerated cold against the design, then verified against the codebase before this spec was committed. Where verification surfaced a forced decision or correction, it was resolved with the user (V1, V2) or folded in (V3, V4) before writing.

| # | Assumption | Result | Key evidence |
|---|---|---|---|
| A1–A5 | 24 inventory domain classes; core entities + snapshots/count exist; ProductAvailability in `product/` | ✅ | `ls grails-app/domain/.../inventory/` = 24; `Transaction{,Entry,Type,Source}.groovy`, `Inventory{,Item,Level,Snapshot,Count}.groovy`, `InventoryItemSnapshot.groovy`; `product/ProductAvailability.groovy` |
| A6/A7 | StockMovement*/StockTransfer*/Replenishment have no domain class | ✅ (parent-design correction) | `find grails-app/domain` returns none of those; only `LocalTransfer`, `Requirement`, `OutboundStockMovement{,ListItem}` exist among transfer/outbound concepts → **V3** |
| A8 | `product_availability` is a real table, not a view | ✅ decisive | `grails-app/migrations/install/changelog-create-tables.groovy:2045` createTable; no view def (contrast `product_summary` view) |
| A9/A10 | ProductAvailabilityService is the sole writer; stays Grails | ✅ | `ProductAvailabilityService.groovy` = 1386 LOC; writes at `:322,361,1284,1308,1346,1353,1365`; no `new ProductAvailability(` elsewhere; `RefreshProductAvailabilityEventService` + `RefreshProductAvailabilityJob` |
| A11 | classification endpoint is a broken (500) endpoint to fix | ❌ **corrected (CDR R1 §1)** | The facility-scoped endpoint **works** (200 + correct union) for a valid facility — `ProductClassificationApiCRUDSpec.groovy:42-52` (live integration test); the 500 is the **invalid-facility** guard only (`:54-58`; `ProductClassificationService.groovy:37-40`). The Phase 5 T12 "500" was the bare `/api/productClassifications` path (no facilityId) hitting that guard. RC-16 is therefore a **behavior-preserving migration of a working, live-consumed endpoint** (React CycleCount filter — `useCycleCountFilters.js:149`), **not** a bug fix. See FD#5 / §7. |
| A12–A14 | Single `list(facilityId)` action; service has only the controller as caller | ✅ | `ProductClassificationApiController.groovy:12-15`; `ProductClassificationService` referenced only by that controller (`:10,13`) + 1 unit spec |
| A13 | Service reads Location + InventoryLevel(by facility.inventory) + Product.abcClass | ✅ | `ProductClassificationService.groovy:37` `Location.read`, `:50-58` `eq("inventory", facility.inventory)`, `:42-48` distinct `Product.abcClass` |
| A15 | abcClass is a plain Product column, no entity | ✅ | `Product.groovy:160` `String abcClass`, constraint `:326`; no `AbcClass`/`AbcAnalysis` entity; also `InventoryLevel.abcClass:72` |
| A16 | RC-16 cross-service reads | ⚠️ refined → **V1** | facility→inventory resolves **locally** via `Inventory.warehouse` FK (`Inventory.groovy:19,26`); Product side is a **distinct-aggregate**, not by-id → resolved as new catalog GET endpoint |
| A17 | RC-13 fillRate dependency is separate from RC-16 | ✅ | `runtime.groovy:757 "/categoryApi/list"` inside `fillRate{}` widget block (`:753`) — a Category dep, unrelated to productClassifications |
| A18/A22 | port 8086 free; services expose-only | ✅ | `docker-compose-base.yml` expose 8081–8085; Grails alone maps `8080:8080` |
| A19 | jwt-auth-common starter exists + consumed via project dep | ✅ | `services/jwt-auth-common`; `catalog-service/build.gradle:14 implementation project(':jwt-auth-common')` |
| A20 | Spring Boot 3.3.5 / Java 21 | ✅ | `services/build.gradle:2` boot `3.3.5`, `:22` `JavaLanguageVersion.of(21)` |
| A23 | per-table shadow-changelog pattern | ✅ | `catalog-service/.../changelog-shadow-create-attribute.xml` — `tableExists` precondition (onFail MARK_RAN) + empty body + namespaced `logicalFilePath` |
| A24 | strict Jackson is service default | ✅ (precedent) | Spring Boot default `FAIL_ON_UNKNOWN_PROPERTIES=true`; Phase 5.5 RC-46 — keep DTO-exact |
| A25 | no JPA inheritance in inventory | ✅ (simpler) | zero `extends <DomainClass>`/`tablePerHierarchy`/discriminator across all 24 → RC-1 inheritance rule N/A |
| A26 | audit fields present | ✅ (simpler) | `dateCreated/lastUpdated` on Inventory(`:22-23`)/InventoryLevel(`:78-79`)/InventoryItem(`:59-60`); read-only core needs no AuditorAware (FD#8) |
| A27 | CHAR(38)/TINYINT divergence applies | ✅ (precedent) | RC-1, 5-of-5 services; handled by DESCRIBE-first + columnDefinition (FD#7) |
| A28 | core entity collections | ✅ (bounded) | only `Transaction.transactionEntries` (`:119`) + `Inventory.configuredProducts` (`:27`); neither has a React read → RC-56 trap doesn't arise; flat DTOs anyway |
| A29/A30 | React GET surface + deletable controllers | ⚠️ sharpened → **V2** | React reads only InventoryItem + ProductAvailability, both via cross-context `ProductApiController`; `Inventory/InventoryLevel/Transaction/TransactionEntry` have zero React GET; `InventoryApiController.importCsv` is a write (not cleanly deletable) |
| A31 | Grails inventory consumers stay direct-JDBC, non-breaking | ✅ | `ProductApiController` productAvailability/productSummary/productAvailabilityAndDemand/availableItems; `StockMovementService` InventoryItem reads; fillRate via `IndicatorDataService` (reads ProductAvailability + InventorySnapshot) |

**Verification-driven decisions:**
- **V1** (forced, user-decided): RC-16's Product-side distinct-`abcClass` set comes from a **new tiny read-only catalog-service GET endpoint**, unioned with inventory-service's local InventoryLevel distinct (facility→inventory resolved locally). Owner-exists → HTTP, per parent §4.3.
- **V2** (forced, user-decided): GET surface is **consumed-only (YAGNI)** — own all 8 tables; expose GET only for RC-16 + T1-confirmed consumers; no speculative entity endpoints.
- **V3** (folded in): parent-design entity-list correction — StockMovement*/StockTransfer*/Replenishment have no entities/tables (FD#2).
- **V4** (folded in): RC-16's real path is `/api/facilities/$facilityId/products/classifications` (`UrlMappings.groovy:139`); nginx + Rule-3 target it (FD#5).
- **V5** (CDR Round 1 §1/§3, user-decided): A11 was false — the classification endpoint works for valid facilities and is live-consumed by the CycleCount filter; the "500" is the invalid-facility guard. RC-16 **stays in Phase 6 core** as a **behavior-preserving migration** (not a bug fix), with the existing Grails integration tests ported as the behavior contract (FD#5, §7, T7/T9).

## 9. Phase 6.5 / 6.x forward pointer

**Phase 6.5** (the heavy/schedulable inventory work deferred from core):
- **Deferred entities** — CycleCount family (+ writes + React CycleCountApi), snapshot/count/audit read-models, LocalTransfer, OutboundStockMovement*, Requirement.
- **Restructure (a)** — ProductMergeService → inventory-service (+ catalog-service thin merge endpoint delegating; the `obsolete.active=false` Product update becomes a single sync HTTP call back to catalog per parent A26). Carries `ProductMergeLogger`.
- **Restructure (b)** — InventoryService bulk-import → inventory-service with per-row sync HTTP to catalog-service for product create-or-find (parent A27). Retires `InventoryApiController.importCsv` from Grails.
- **ProductAvailability refresh logic** → inventory-service (moves with restructure (a), shared service).
- **Write clusters** — record stock / adjustments / transactions surfaces, as demand-driven per the Phase 5.5 RC-51 "wait for a write consumer" discipline.
- **RC-13 remainder** — CategoryApiController final deletion once the `runtime.groovy:757` fillRate categoryApi consumer is relocated/removed.

**Phase 6.1** — horizontal cleanup (per the N.1 convention) if the Phase 6 retro accumulates enough A/B/C carry-forward to justify it.

**Phase 7** — ReplenishmentService ports to inventory-service with its OrderItem-deletion saga; saga infrastructure built. **Phase 8** — StockTransferService + StockMovementService writes port with their sagas.
